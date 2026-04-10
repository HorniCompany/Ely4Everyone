# Ely4Everyone

[![CI](https://github.com/Cokeef/Ely4Everyone/actions/workflows/ci.yml/badge.svg)](https://github.com/Cokeef/Ely4Everyone/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](./LICENSE)

![Ely4Everyone banner](img/readme-banner.jpg)

> Fabric-мод, который делает Ely.by-режим доступным без ручной установки `authlib-injector`.

> [!WARNING]
> Проект экспериментальный и написан ПОЛНОСТЬЮ с помощью AI.
>
> Это значит:
> - в коде и архитектуре всё ещё возможны грубые ошибки;
> - часть направлений в репозитории является R&D, а не production-ready функциональностью;
> - перед использованием на реальном сервере или в публичной сборке проект нужно аудитить самостоятельно.

## Что это за проект

`Ely4Everyone` — это **mod-first Ely.by client**.

Главная цель не в том, чтобы заставить игроков пользоваться нашим server stack, а в том, чтобы обычный Fabric-клиент:

- логинился через Ely.by;
- получал Ely UUID;
- получал Ely nickname;
- получал Ely skins / textures;
- мог входить на Ely-compatible серверы без отдельного Ely launcher;
- а на собственных серверах дополнительно получал trusted login flow.

## Честная постановка задачи

### Что мы хотим

Мы хотим убрать для пользователя ручную возню с `authlib-injector`.

То есть идеальный UX такой:

1. пользователь ставит Fabric-мод;
2. логинится в Ely.by изнутри игры;
3. Ely.by-профиль применяется как часть клиентского auth flow;
4. для пользователя это выглядит как обычная функция мода, а не как настройка `javaagent`.

### Что мы больше не обещаем

Мы **не** считаем реалистичным путь “невидимого runtime-хакинга JVM” изнутри уже запущенной игры:

- не dynamic javaagent attach;
- не поздний Instrumentation;
- не “бесшовную смену аккаунта на лету в уже запущенной игре” (hot-swap).

Этот путь слишком хрупок и не даёт продуктового результата.

## Новый технический вектор

Проект теперь сознательно смещён в сторону:

- **раннего patching Authlib через Fabric Mixins**;
- **in-game Ely login UI**;
- **сохранения Ely session локально**;
- **применения Ely identity в клиенте**;
- **embedded auth-host на стороне `velocity-plugin` / `paper-bridge`**;
- **authlib-injector-style compatibility bridge**, где это нужно.

Проще говоря:

мы не пытаемся “встраивать javaagent в уже запущенную JVM” или “на лету поднять сессии в клиенте”.

Мы пытаемся сделать **Fabric-мод, который рано патчит Authlib через Mixins** и использует **Boot-Time Injection** сессии при запуске клиента.

## Архитектурная позиция

### 1. Основной продукт — `mod/`

`mod/` — главный продукт.

Именно он должен:

- показывать Ely login UI;
- хранить Ely session;
- выбирать auth-host;
- применять Ely profile;
- работать как Ely-aware client layer.

### 2. `shared-auth/`

`shared-auth/` — общее ядро:

- auth/session модели;
- ticket logic;
- embedded auth-host;
- compatibility endpoints;
- protocol/shared helpers.

### 3. `velocity-plugin/` и `paper-bridge/`

Это официальные server-side интеграции:

- `velocity-plugin/` — proxy-side trusted login и embedded auth-host;
- `paper-bridge/` — standalone Paper auth-host и bridge к auth plugins.

### 4. `relay/`

`relay/` больше не считается целевой архитектурой и выведен из основной линии.

## Для владельцев серверов (Velocity Setup)

Если вы владелец прокси-сервера (Velocity) и хотите впускать игроков с модом Ely4Everyone прозрачно, а также пускать обычных Mojang-игроков по тому же Premium flow, вам **всё еще нужен `authlib-injector` на стороне Proxy**.

Но теперь `authlib-injector` должен смотреть **не прямо в Ely.by**, а в локальный hybrid auth-host, который поднимает `velocity-plugin`. Именно он решает, когда проверять сессию через Ely.by, а когда через Mojang.

**Как настроить прокси:**
1. Скачайте свежую версию [authlib-injector](https://github.com/yushijinhun/authlib-injector/releases);
2. Поместите скачанный `authlib-injector.jar` в корень папки с вашим Velocity;
3. Отредактируйте ваш **start.sh** / **start.bat** и добавьте аргументы JVM:
   ```bash
   java \
     -Dauthlibinjector.ignoredPackages=dev.ely4everyone.velocity,dev.ely4everyone.shared.host \
     -Dauthlibinjector.mojangNamespace=enabled \
     -javaagent:authlib-injector.jar=http://127.0.0.1:18086 \
     -Xms1G -Xmx1G -jar velocity.jar
   ```
4. Убедитесь, что в конфигах ваших бэкенд-серверов (Paper/Purpur) стоит `online-mode=false`!
5. Запускайте сервер. Теперь Velocity будет сверять Ely.by и Mojang сессии через локальный hybrid auth-host Ely4Everyone.

## Что реально достижимо

На сегодня честная цель проекта выглядит так:

### Реально

- Ely UUID / nickname / textures на клиенте;
- Ely login flow внутри мода;
- trusted Ely login на собственном server stack;
- частичная или высокая совместимость с Ely-compatible серверами;
- authlib-injector-like поведение для части сценариев через ранние Mixins и совместимые endpoint bridges.

### Нереалистично как продуктовая цель

- универсальная замена `authlib-injector` для любых серверов вообще;
- “полностью прозрачная магия” без источника auth metadata;
- бесшовная смена account'а (hot-swap) в живом клиенте без перезапуска (это принципиально ломает стейт-менеджмент игры).

## Исследовательская линия

В репозитории уже есть R&D-направление по теме authlib replacement:

- [Authlib Replacement Roadmap](./docs/authlib-replacement-roadmap.md)
- [Authlib Replacement Notes](./docs/authlib-replacement-notes.md)

Смысл этой ветки:

- не обещать невозможное;
- а проверить, где именно ранний authlib patching реально может заменить `authlib-injector`.

## Репозиторий

- `mod/` — основной Fabric-мод
- `shared-auth/` — shared auth/protocol/server core
- `velocity-plugin/` — proxy integration with embedded auth-host
- `paper-bridge/` — standalone Paper integration with embedded auth-host
- `docs/` — протокол, roadmap и исследовательские заметки
- `servers/` — локальный тестовый стенд, не для коммита runtime state

## Безопасность

Главный факт не изменился:

Ely OAuth требует server-side секрета.

Поэтому:

- open-source клиент не должен хранить `client_secret`;
- `client_secret` живёт только на стороне auth-host;
- mod не должен встраивать серверные секреты;
- короткоживущие tickets и локальная session persistence должны использоваться аккуратно и осознанно.

## Текущий фокус разработки

Сейчас проект движется не к “ещё одному костылю вокруг прокси”, а к следующему:

- стабилизировать mod-first Ely login;
- улучшить UX Ely login внутри игры;
- усилить ранний authlib patching research;
- довести embedded auth-host до удобного self-host сценария;
- проверить, где проходит реальная граница между PoC и поддерживаемым продуктом.

## Лицензия

Проект распространяется по лицензии **MIT**.

Полный текст — в [LICENSE](./LICENSE).
