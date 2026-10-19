# Ely4Everyone Protocol Spec v2

Этот документ фиксирует минимальный протокол для новой embedded-auth архитектуры.

## Цели

- дать игроку с Fabric-модом долгоживущую клиентскую `auth session`, а на логине выпускать short-lived Ely ticket под конкретный challenge;
- позволить `velocity-plugin` и standalone `paper-bridge` использовать один и тот же auth-host core;
- отделить discovery, trust и actual auth host selection;
- не передавать в клиент `client_secret`.

## Компоненты

- `mod` - клиентский Fabric-мод, который хранит локальную Ely session, выбирает auth-host, получает login ticket по challenge и отвечает на login challenge;
- `shared-auth` - общее ядро протокола, ticket validation, session stores и embedded auth host;
- `velocity-plugin` - proxy plugin, который валидирует ticket, поднимает встроенный auth-host и выбирает trusted Ely mode или fallback;
- `paper-bridge` - standalone `Paper` plugin, который поднимает встроенный auth-host и может интегрироваться с `AuthMe`-подобным окружением.

## Канал логина

- Channel ID: `ely4everyone:login`
- Транспорт: login plugin message

Plugin инициирует login challenge на стадии логина. Клиент с модом либо получает login ticket через выбранный auth-host и отправляет его, либо отвечает пустым payload.

## Login Flow

### 1. Предварительный Ely login

До подключения к серверу мод должен получить клиентскую Ely auth session через встроенный auth-host. Вход сделан по RFC 8252 (loopback redirect) + PKCE (RFC 7636), реализация в `shared-auth/.../oauth/LoopbackLogin.kt`:

1. мод генерирует `state` и `code_verifier` (по 32 случайных байта, base64url) и поднимает `LocalAuthCallbackServer` на `127.0.0.1:<случайный порт>`;
2. мод открывает в браузере `GET /api/v1/auth/start?state=...&code_challenge=BASE64URL(SHA256(verifier))&code_challenge_method=S256&client_redirect_uri=http://127.0.0.1:<port>/callback`;
3. auth-host принимает только `http://127.0.0.1:<port>/callback` или `http://[::1]:<port>/callback` (без query, fragment и userinfo), запоминает вход (один `state` нельзя использовать дважды) и отправляет браузер в Ely OAuth;
4. `GET /oauth/callback` от Ely: auth-host обменивает Ely-код, создаёт клиентскую session и редиректит **браузер игрока** на его loopback-адрес с `state` и одноразовым `code` (живёт 120 с). Токены через браузер не передаются. При ошибке туда же уходит `error`;
5. мод проверяет `state` и делает `POST /api/v1/auth/exchange` (form body: `state`, `code`, `code_verifier`). Auth-host один раз отдаёт `auth_session_token`, Ely access token и профиль;
6. мод хранит клиентскую session локально до истечения TTL.

Почему не опрос: раньше мод опрашивал `GET /api/v1/auth/poll?state=...`, и токены получал любой, кто знает `state`. Ссылку входа может сделать кто угодно, так что фишинговая ссылка отдавала сессию жертвы автору ссылки. Сейчас одноразовый код попадает только на 127.0.0.1 того компьютера, где игрок подтвердил вход, и без verifier'а из мода он бесполезен.

Старые моды (запрос `start` без `code_challenge`) по умолчанию получают отказ с просьбой обновиться. Ключ `allow_legacy_poll_login` (Velocity) / `auth-host.allow-legacy-poll-login` (Paper) временно возвращает старый вход на время миграции. Тогда `poll` отдаёт токены один раз.

### 2. Server join

При подключении к серверу:

1. plugin отправляет challenge через `ely4everyone:login`;
2. мод по `session_token + nonce + audience + host_id` запрашивает у auth-host login ticket;
3. мод отвечает одним из двух вариантов:
   - `ticket=<signed ticket>`
   - `ticket=` если Ely session отсутствует или ticket не удалось получить;
4. plugin валидирует подпись, issuer, expiry, nonce, `host_id` и replay protection;
5. если ticket валиден, игрок помечается как trusted Ely user;
6. если ticket невалиден или отсутствует, игрок переводится в cracked/AuthMe flow.

## Embedded Auth HTTP API

- `GET /health`
- `GET /api/v2/discovery`
- `GET /api/v1/config`
- `GET /api/v1/auth/start`
- `POST /api/v1/auth/exchange`
- `GET /api/v1/auth/poll` (только старые моды, при включённом `allow_legacy_poll_login`)
- `GET /api/v1/auth/dev/latest-session`
- `GET /api/v1/auth/issue-ticket`
- `GET /oauth/callback`

## Discovery Document

`/api/v2/discovery` возвращает line-based payload:

- `ver=v2`
- `host_id=<stable host id>`
- `display_name=<human friendly name>`
- `base_url=<public base url>`
- `issuer=<ticket issuer>`
- `aud=<default audience>`

Discovery не даёт автоматического доверия. Клиент может показать найденный host пользователю, но должен запросить явное подтверждение перед использованием.

## Формат Ticket

- строка вида `<payload>.<signature>`;
- `payload` - base64url от текстового набора claim-строк;
- `signature` - HMAC-SHA256 от `payload`.

### Claims

- `ver` - версия формата, сейчас `v2`
- `iss` - issuer auth-host
- `aud` - аудитория, обычно id сети или proxy
- `sub` - Ely UUID игрока
- `name` - Ely username
- `iat` - время выпуска в epoch seconds
- `exp` - время истечения в epoch seconds
- `jti` - уникальный ticket id
- `nonce` - nonce login challenge, который должен совпасть на plugin
- `host_id` - стабильный id embedded auth-host, выпустившего ticket

## Правила валидации

- подпись должна совпадать с ключом доверенного auth-host;
- `iss` должен находиться в allowlist;
- `exp` должен быть в будущем;
- `iat` не должен быть слишком далеко в будущем;
- `aud` должен совпадать с id сети, если аудитория включена;
- `jti` не должен приниматься повторно в окне TTL;
- `nonce` должен совпадать с тем challenge, который plugin отправил конкретному клиенту;
- `host_id` должен соответствовать ожидаемому embedded auth-host или trusted discovery result.

## Правила fallback

Если любое из условий ниже не выполнено, Ely trusted mode не включается:

- клиент не ответил на challenge;
- клиент ответил пустым ticket;
- подпись не сошлась;
- ticket истек;
- issuer не доверен;
- replay detection сработал;
- auth-host временно недоступен и мод не может выпустить ticket из своей auth session.

В этом случае игрок остается обычным cracked-user и идет по `AuthMe` или иному fallback сценарию.
