# Ely4Everyone: Настройка Proxy-сервера и Круги Ада (LimboAuth + LimboFilter + Hybrid Auth Host)

Этот документ описывает рабочую схему `Velocity + authlib-injector + Ely4Everyone hybrid auth-host + LimboAuth + LimboFilter`.

## Архитектура Авторизации

Когда игрок подключается к нашему Velocity серверу, должен происходить следующий "счастливый" путь (Happy Path):

### Для игрока с модом Ely4Everyone (Premium через Ely.by)
1. **Velocity + authlib-injector:** Прокси направляет authlib-проверки не в Ely.by напрямую, а в локальный Ely4Everyone auth-host.
2. **Ely4Everyone `PreLogin`:** Плагин определяет, что игрок должен идти по premium flow, и пинит authority `ELY` на время handshake.
3. **Velocity:** Запрашивает `hasJoined`, а локальный hybrid auth-host проверяет сессию через Ely.by.
4. **LimboAuth:** Видит Premium, пускает без пароля.
5. **LimboFilter:** Online-mode bypass → сразу в хаб.

### Для Mojang игрока (Premium через Mojang)
1. **Ely4Everyone `PreLogin`:** Плагин определяет Mojang authority или оставляет dual-verify candidate.
2. **Velocity:** Запрашивает `hasJoined`, а локальный hybrid auth-host проверяет Mojang sessionserver.
3. **LimboAuth:** Видит Premium, пускает без пароля.
4. **Игрок получает Mojang UUID** и отдельную identity от Ely.by-аккаунта с тем же ником.

### Для пирата (без мода, без лицензии)
1. **Ely4Everyone `PreLogin`:** Если игрок отправляет offline UUID или ник не найден ни в Ely.by, ни в Mojang, прокси сразу отправляет его в offline flow.
2. Если ник premium существует, но session verification проваливается, игрок не проходит premium auth.
3. **LimboAuth:** Просит `/register` или `/login` (оффлайн-авторизация).
4. **Игрок получает offline UUID** → отдельный инвентарь и прогресс от Ely.by-игрока с тем же ником.

> **ВАЖНО:** `FastLogin` больше не является источником истины в этой схеме. Источник истины теперь `ely4everyone` + локальный hybrid auth-host.

Звучит просто? На деле мы споткнулись о несколько глубоких проблем кэширования и логики Limbo-плагинов.

---

## Круг 1: Призрак старых регистраций (LimboAuth & MariaDB)

### Симптомы
Игрок успешно логинится через Ely.by клиент. FastLogin пишет в консоли `Requesting premium login for registered player`. Но после этого сервер всё равно пытается запросить пароль, или игрок застревает в Limbo.

### В чем проблема?
LimboAuth сохраняет локальные профили (обычно в таблицу `AUTH` или `authme`).
Если игрок *когда-либо* заходил на сервер как пират и прописал `/register`, в базе остается его пароль (`$SHA$...`).
**Критическая проблема:** LimboAuth имеет приоритет локальной базы над FastLogin! Если он находит ХЕШ пароля в базе под вашим ником, он **навсегда** блокирует Premium-вход для этого ника из соображений безопасности (вдруг пират зарегистрировал ник лицушника).

### Решение
Если вы переводите игрока на Premium (Ely.by) авторизацию, **необходимо полностью удалить его оффлайн-профиль из базы данных**.

```sql
-- Пример для базы MariaDB, таблица authme
DELETE FROM authme WHERE username='Cokeef';
```
Только после этого LimboAuth увидит, что игрок "чист", и поверит FastLogin-у, пустив его как Premium-пользователя без пароля.

---

## Круг 2: Бездна и Физическая Капча (LimboFilter)

### Симптомы
Игрок подключается, видит "Этап шифрования" (или просто зависает), его закидывает в некий мир (Limbo), и ровно через **7 секунд** он отключается сервером (Connection Lost), так и не дойдя до хаба.

В консоли:
```
[19:17:05] [limbofilter]: [connected player] Cokeef has client brand fabric
[19:17:11] [limboapi]: Cokeef has disconnected from the LimboFilter Limbo
```

### В чем проблема?
LimboFilter — это анти-бот защита до авторизации. Его основной чек — **Falling Check**.
Он кидает игрока в пустоту (void) и ждет 128 тиков (~6.4 секунды). За это время ванильный клиент присылает пакеты падения (координата Y должна уменьшаться).
Если игрок заходит с Fabric клиентом (Ely4Everyone), и в момент захода у него открыт какой-то GUI (или происходит инициализация модов), отправка пакетов физики может прерываться. Игрок проваливает капчу и кикается.

**Но почему LimboFilter вообще проверял Premium-игрока?**
Из-за настройки `online-mode-bypass`. По умолчанию она выставлена в `0`. В логике LimboFilter это означает следующее:
> *"Если количество подключений в секунду больше > 0, то мы временно ВЫКЛЮЧАЕМ этот байпасс и кидаем всех лицушников в капчу на всякий случай".*

То есть, даже при малейшей активности, `LimboFilter` заставлял Ely.by игроков падать в бездну 6 секунд!

### Решение
В файле `/plugins/limbofilter/config.yml` необходимо жестко отключить авто-переключатель байпассов (auto-toggle), чтобы Premium игроки **никогда** не попадали в физическую капчу:

```yaml
  filter-auto-toggle:
    # -1 отключает отмену байпасса, делая его ПОСТОЯННО РАБОТАЮЩИМ.
    online-mode-bypass: -1 
    online-mode-verify: 79
```

Только со значением `-1` LimboFilter начинает безусловно доверять LimboAuth/FastLogin и мгновенно прокидывать Ely.by-игроков напрямую в `hub` (Backend Server), выдавая:
`[server connection] Cokeef -> hub has connected`

---

## Круг 3: Пиратов не пускает — "Invalid session" (FastLogin force-premium)

### Симптомы
Игрок подключается с пиратского лаунчера (без мода Ely4Everyone). Сервер кикает с ошибкой `Invalid session` или `bad login`. Даже если ник **не** зарегистрирован на Ely.by, повторные попытки не помогают.

### В чем проблема?
Исходная версия плагина `ely4everyone` агрессивно форсила `profile.isPremium = true` для **любого** ника, найденного в Ely.by API:

```kotlin
// ❌ СТАРЫЙ ОПАСНЫЙ КОД
val isElyBy = checkElyByProfile(username)
if (isElyBy) {
    profile.isPremium = true  // Форсируем premium!
}
```

Это вызывало каскад проблем:
1. Наш плагин находит ник в Ely.by → ставит `isPremium = true`
2. FastLogin запоминает это в таблице `premium` (`Premium=1`)
3. Velocity запрашивает session verification
4. Пиратский клиент без модификаций **не может** пройти Ely.by session verification
5. → `Invalid session`, кик
6. При повторных заходах FastLogin видит `Premium=1` из базы → снова форсит → снова кик
7. `secondAttemptCracked: true` не срабатывает, потому что FastLogin сам "знает" что игрок premium (из своей БД)

### Решение

**Шаг 1.** Убрать `profile.isPremium = true` из плагина `ely4everyone`. Плагин должен быть **только наблюдательным** (observability), не вмешиваясь в решения FastLogin:

```kotlin
// ✅ НОВЫЙ БЕЗОПАСНЫЙ КОД
val isElyBy = checkElyByProfile(username)
if (isElyBy) {
    // НЕ ставим isPremium! FastLogin разберётся сам через authlib-injector.
    logger.info("Player {} exists in Ely.by database. FastLogin will handle.", username)
}
```

**Шаг 2.** Почистить закэшированный premium-статус в таблице `premium`:
```sql
UPDATE premium SET Premium = 0 WHERE Name = 'YourNickname';
```

**Шаг 3.** FastLogin сам определит premium через authlib-injector (который перенаправляет Mojang API → Ely.by). Если у клиента есть мод с валидной Ely.by сессией — пройдёт как premium. Если нет — `secondAttemptCracked: true` пустит при повторном заходе как cracked.

### Результат
- **Ely.by игрок (с модом):** Premium UUID → свой инвентарь/прогресс
- **Пират:** Offline UUID (`OfflinePlayer:nickname`) → свой отдельный инвентарь/прогресс
- **Один ник — два разных игрока** (разные UUID = разные данные)

---

## Итог: Чеклист для новых серверов

1. Установлен `authlib-injector` на прокси: `-javaagent:authlib-injector.jar=https://ely.by`
2. Плагин `ely4everyone` установлен, но **НЕ** форсит `isPremium` — только логирует.
3. FastLogin настроен:
   - `autoRegister: true`
   - `secondAttemptCracked: true` ← позволяет пиратам заходить при повторном подключении
   - `premiumUuid: true` ← Ely.by игроки получают свой online UUID
4. **Нет старых паролей в `authme`:** Если игрок переходит на лицуху/Ely.by, его старый пиратский пароль должен быть стерт из MariaDB.
5. В `limbofilter/config.yml` строго установлено:
   `online-mode-bypass: -1`
6. В `limboauth/config.yml` включено `save-uuid: true`.

После этого связка станет идеальной:
- Ely.by игроки попадают в хаб мгновенно ⚡
- Пираты при первом заходе видят `Invalid session`, при втором — заходят как cracked и регистрируются через `/register`
- У каждого свой инвентарь и прогресс 🎒
