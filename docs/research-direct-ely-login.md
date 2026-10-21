# Исследование: вход в мод напрямую через Ely.by, без relay

Дата: 2026-10-20. Источник: исходники [elyby/accounts](https://github.com/elyby/accounts) (коммит `8542e78`, 2026-10-19) и [elyby/accounts-frontend](https://github.com/elyby/accounts-frontend), плюс запросы к живому `account.ely.by`.

## Вывод

**Relay для входа в мод не нужен.** У Ely.by есть публичный тип OAuth-клиента для десктопных приложений: без `client_secret` и с redirect на `127.0.0.1`. Мод может логиниться в Ely сам, а сервер с плагином проверяет игрока обычным Ely `hasJoined`. Тогда horni.cc перестаёт быть обязательной точкой для всех пользователей мода.

## Что есть у Ely.by

### Тип клиента `desktop-application` (с 2025-01-15)
- `common/models/OauthClient.php`: типы `application` (web), `desktop-application`, `minecraft-server`.
- `ClientEntity::isConfidential()`: для `desktop-application` возвращает `false`, то есть **публичный клиент, секрет не нужен**.
- `ClientRepository::validateClient()`: desktop-клиент проходит без секрета.
- `ValidateRedirectUriTrait`: для desktop-клиента разрешены любая кастомная схема (`myapp://...`) и `http://localhost`, `http://127.0.0.1`, `http://[::1]` **с любым портом**. Это ровно наш loopback из RFC 8252.
- Библиотека: `league/oauth2-server` 9.x, а для публичных клиентов она по умолчанию требует PKCE (`code_challenge`). PKCE у нас уже есть.
- Регистрация в UI: `accounts-frontend` (коммит «Implemented desktop application type», 2025-01-15), раздел приложений разработчика на account.ely.by.

### Device Code Grant, RFC 8628 (с 2024-12)
- `POST https://account.ely.by/api/oauth2/v1/devicecode` с `client_id` и `scope` выдаёт `device_code`, `user_code`, `verification_uri` (`https://account.ely.by/code`) и `interval`. Код живёт 10 минут.
- `POST /api/oauth2/v1/token` с `grant_type=urn:ietf:params:oauth:grant-type:device_code`.
- Refresh-токены выдаются и device-code клиентам (фикс #41).
- **Живой прод проверен:** с несуществующим `client_id` оба эндпоинта отвечают `401 invalid_client` (то есть они есть и работают), а `https://account.ely.by/code` отвечает 200.

### Scopes для публичных клиентов
Из `PublicScopeRepository`:
- `account_info`, `account_email`, `offline_access`;
- `minecraft_server_session`: токен годится для Ely `session/join` (`JoinForm.php` проверяет это право);
- `change_skin`: в публичной документации его нет. Мод потенциально может менять скин.

### Время жизни токенов
- Без `offline_access`: access token **бессрочный**, `expires_in` и `refresh_token` не приходят (`AccessTokenCest`).
- С `offline_access`: приходят `expires_in` и `refresh_token`. Для мода лучше этот вариант, чтобы утёкший токен не жил вечно.

## Как это может выглядеть в моде

1. **Вход через браузер:** loopback + PKCE, как сейчас, только авторизация и обмен кода идут прямо в `account.ely.by`, а не через relay. Почти весь новый код (`LoopbackLogin`, `LocalAuthCallbackServer`) переиспользуется.
2. **Вход по коду (device code):** игра показывает «зайди на account.ely.by/code и введи ABCD-1234». Не нужен локальный порт, не мешают файрволы, можно подтвердить с телефона. Хорошо подходит как второй способ.
3. Refresh мод делает сам: `refresh_token` и `client_id`, без секрета.
4. Вход на сервер: стандартный Yggdrasil. Мод вызывает Ely `session/join`, сервер вызывает Ely `hasJoined` (у нас это уже делает гибридный хост). Работает на любом сервере с поддержкой Ely, не только на наших.

`client_id` десктопного приложения публичный по своей природе, так и задумано: коды защищает PKCE, а не секрет.

## Что меняется для сервера и relay
- Публичный relay на horni.cc для мода больше не нужен. Проблема «пропихивания horni.cc» и проблема «рестарт Velocity ломает вход всем» уходят вместе с ним.
- Плагин сервера оставляет только локальную часть: гибридную проверку `hasJoined` (Mojang + Ely) для своего Velocity. См. заметку про `-Dmojang.sessionserver` вместо authlib-injector.
- Login tickets (`issue-ticket`, подпись тикетов auth-host'ом) привязывают игрока к конкретному хосту. При прямом входе их стоит заменить стандартным `join`/`hasJoined`. Это отдельный дизайн серверной части, его надо продумать.
- Для старых модов relay ещё какое-то время нужен.

## Живой тест, 2026-10-20

Зарегистрировано desktop-приложение **`ely4everyone1`** на аккаунте Cokeef (имя «Ely4Everyone», сайт - GitHub-репо). Старое веб-приложение `ely4everyone` (302 пользователя, его использует relay) не тронуто. Скрипты теста: `scratchpad/ely-direct/*.py` (токены нигде не печатались и не сохранялись).

### Device code - работает целиком ✅
- `devicecode` выдаёт 8-символьный `user_code`, `interval=5`, `expires_in=600`. `verification_uri` приходит как `http://account.ely.by/code` (без https, в моде показывать https).
- Подтверждение на account.ely.by/code: выбор аккаунта, экран прав (данные аккаунта без e-mail, вход на серверы, офлайн-доступ), «Разрешить».
- Токен получен **без секрета**: `access_token` и `refresh_token`, `expires_in` ≈ 355 млн секунд (~11 лет, фактически бессрочный даже с `offline_access`).
- `account/v1/info` отвечает 200.
- **`authserver.ely.by/session/join` с этим токеном: 204, `hasJoined`: 200 с подписанными текстурами.** С чужим serverId `hasJoined` отвечает 401. Значит, вход на серверы работает напрямую.
- Refresh без секрета: 200, выдаётся новый access token.

### Loopback + PKCE - сервер готов, сайт Ely сломан ❌
- API ведёт себя правильно: `validate` с `http://127.0.0.1:<порт>/callback` и PKCE отвечает 200; без PKCE отвечает 400 `invalid_request`; с чужим redirect отвечает 401.
- **Но веб-страница Ely ломает этот поток:** алерт «Invalid request (null required)». Причина в `accounts-frontend`: коммит `beb3927` (2025-01-15, issue #41) сохраняет `code_challenge` в состояние, а `getOAuthRequest()` в `packages/app/components/auth/actions.ts` его не передаёт в API. Сервер видит публичный клиент без PKCE и отказывает.
- Исправление на 12 строк подготовлено локально: `scratchpad/elyby-src/elyby-frontend-pkce-fix.patch` (не собрано, тесты Ely не запускались, в Ely не отправлено).
- Обмен кода на токен через loopback поэтому вживую не проверен. Серверная часть та же, что в device code (league/oauth2-server), так что ожидается, что заработает сразу после фикса фронтенда.

### Код мода против живого Ely, 2026-10-21 ✅
`ElyDirectLoginLiveTest` (запускается только вручную): код получен, ссылка `account.ely.by/code?user_code=...` сразу открыла выбор аккаунта (ввод кода пропущен), Никита подтвердил, токен и refresh-токен получены, профиль Cokeef со скином получен, refresh без секрета выдал новый токен.

Найдено по ходу: `authserver.ely.by/session/profile/<uuid>` без `?unsigned=false` отдаёт свойства **без подписи**, как и у Mojang. Исправлено в моде и в серверном `ElyOAuthClient`.

## Что ещё не проверено
- [x] Вход в самой игре (2026-10-21): браузер открылся сразу на выборе аккаунта, игра сама перешла в «Вы авторизованы» со скином, заход на horni.cc прошёл как ELY premium.
- [ ] Продление сессии из меню и после перезапуска игры.
- [ ] Loopback-поток целиком, после фикса фронтенда Ely.
- [ ] Лимиты запросов Ely для device code (`slow_down`).
- [ ] Как плагин отличает Ely-игрока от cracked с тем же ником без тикетов (Ely-first + forceOnlineMode уже частично это делает).
