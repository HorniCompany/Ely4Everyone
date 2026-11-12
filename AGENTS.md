Будь добрым. Всегда.

# Ely4Everyone Agent Guide

## Collaboration Philosophy & Roles

**User Role:** Product Manager, Visionary, and Lead Tester. The user is responsible for defining *what* the mod should do, how it should look, and testing the results. **The user does not need to be a coder.**

**Agent Role:** Lead Engineer and Developer. The agent is responsible for the "how" (diving into JVM, writing Mixins, refactoring). The agent executes the technical heavy lifting.

**Process:** Never view the project as an overwhelming monolithic mountain. Break every large ambition into tiny, isolated, and safe steps. Implement one core piece, test it, celebrate, and move to the next.

## Mission

`Ely4Everyone` is a mod-first project.

The primary product is the Fabric client mod in `mod/`.

The goal is not "our server stack first". The goal is:

**a Fabric mod that gives the player a practical Ely.by mode without manual `authlib-injector` setup.**

## Strategic Direction

This repository has a hard architectural decision now:

- **continue:** early authlib/Minecraft patching through Fabric Mixins
- **reject:** dynamic JVM hacking, late javaagent attach, Instrumentation tricks, network MITM as a primary path

If a proposed change smells like “runtime magic inside an already running JVM”, treat it as suspect by default.

## Source Of Truth

These documents are the guardrails:

- `docs/architecture.md` - how the mod works today: login, session storage, identity mixins, skins
- `docs/porting.md` - how the mod is built for several Minecraft versions and how to add one
- `README.md` / `README.en.md`

Do not drift away from them without updating them.

## Repository Map

- `mod/` - main Fabric client product. One source tree (`mod/src`) built for every supported
  Minecraft version with Stonecutter: `mod/versions/<minecraft>/` (1.21.11, 26.1.2, 26.2, 26.3).
  See `docs/porting.md`.
- The Velocity/Paper plugins and `shared-auth` moved out on 2026-11-10 into their own repository
  (HorniMine, kept privately). This repository is the client mod only; `mod/.../net/` holds the
  small JSON/HTTP helpers the mod took from `shared-auth`.
- `scripts/release.py` - release from the owner's PC (GitHub + Modrinth + CurseForge project 1799445, one jar per Minecraft version; tokens in `~/.env`)
- `scripts/dev/playtest.py` - invisible in-game checks on Windows (clients, local server, second "pirate" client)
- `scripts/yarn2moj.py` - Yarn -> Mojang name lookup for backports to old versions
- `docs/` - architecture, porting notes, research reports (`research-*.md`, the owner's)

The mod talks to Ely.by directly. Do not bring back a login relay or any custom server in between.

## Product Guardrails

- Keep the project mod-first.
- Do not turn the mod into a thin frontend for a custom server stack.
- Do not promise universal authlib-injector replacement for arbitrary servers.
- Preserve the distinction between:
  - supported product path
  - fragile research path

## Authlib Replacement Rules

### Allowed direction

- Early Mixins into `com.mojang.authlib.*`
- Early Mixins into client-side Minecraft auth/session wrappers
- Ely session persistence and boot-time application

### Disallowed direction

- Dynamic `VirtualMachine.attach`
- Late Instrumentation / runtime javaagent injection
- Global HTTP interception as the main compatibility strategy
- “Seamless hot-swap identity” claims without hard evidence

If a task depends on any of the disallowed directions, stop and say so directly.

## Session And Identity Rules

- In-game Ely login UI is acceptable.
- Local Ely session persistence is acceptable, outside the instance folder (`docs/architecture.md`).
- Never replace the vanilla `User` object by reflection. The mod answers `Minecraft.getUser` and
  friends from its own state through mixins, applied at boot and after login, logout and refresh.
- Vanilla caches built with the old identity (Realms, telemetry, loaded skins) are not chased: if a
  feature needs a clean identity everywhere, ask the player to restart.
- Identity changes belong on the client thread (open: some refresh paths still apply it from a
  background thread, `tasks.md` 3.1).

## Server Rules

Server code (Velocity/Paper plugins, auth host, relay for 0.1.x mods) is no longer in this repository.
The mod must keep working with servers that verify players through Ely.by (authlib-injector with
Ely.by, the HorniMine Velocity plugin) and must not grow a dependency on any custom server.

## UI And UX Rules

- The Ely auth screen must be understandable to a normal player, not only to the author.
- Prefer one clear primary action over multiple equal-looking buttons.
- Success state should show:
  - that the player is authorized
  - 3D skin preview
  - nickname
  - UUID
  - session status

## Testing Rules

Use TDD for:

- parsing Ely.by responses and endpoint mapping
- skin source decisions (whose skin comes from where)
- config parsing (the mod has no config today; this applies once one comes back)
- session and identity behavior
- research helpers that decide patch targets

When doing authlib replacement research:

- first write a failing trace/test
- then patch one narrow point
- then verify with logs or a focused test

## Verification Rules

Before saying work is complete:

1. Run the narrowest command that proves the claim.
2. Read the actual output.
3. Report warnings, transient failures, fallback runs, and residual risks honestly.

Default commands (Gradle runs on Java 25 and downloads the JDKs itself):

- `./gradlew build` - every mod version, all tests
- `./gradlew :mod:1.21.11:build` (or `:mod:26.3:build`, ...) - one Minecraft version
- `./gradlew :mod:1.21.11:runClient` - the game; 1.21.x runs in `mod/run`, newer versions in `mod/run-<version>`
- `./gradlew :mod:<minecraft>:runClientGameTest` - the in-game test scenario (`mod/src/gametest`); CI runs it for
  every version and keeps screenshots as artifacts. Keep it green when changing UI, skins or mixins.

Mixin targets are strings the compiler does not check: when a Minecraft or authlib version changes,
verify every target against the bytecode (`docs/porting.md`).

In-game checks run on the owner's PC, which is also used for gaming: never launch clients while a
game runs or free commit memory is low (`python scripts/dev/playtest.py check`), keep windows
invisible and silent, and stop every java process and Gradle daemon you started.

## Text And Languages

- All player-facing text goes through `ui/Lang.tr("key")` with
  `mod/src/main/resources/assets/ely4everyone/lang/en_us.json` and `ru_ru.json`. English is the
  fallback for every language without a file. `LangFilesTest` checks that both files have the same
  keys and that every key used in code exists.

## Commits

- Plain, descriptive messages in the style of the history. No AI attribution lines
  (`Co-Authored-By`, `Claude-Session` and the like): the owner's rule.

## Documentation Rules

Update docs when any of the following change:

- the Ely.by endpoints the mod talks to
- authlib replacement strategy
- session application model
- what servers need to let mod users in (README, "For server owners")
- supported Minecraft versions (README, `docs/porting.md`)
- product claims in README

## Anti-Patterns

Do not introduce:

- duplicated auth compatibility logic across modules
- “manager” singletons that own UI + transport + storage + state
- undocumented runtime hacks
- broad claims unsupported by logs or tests
- product wording that quietly upgrades a PoC into a “solved problem”

## Completion Standard

The project should move toward one of two clear outcomes:

- a stable, supportable Ely.by Fabric client with early authlib patching
- or an explicitly bounded research branch that remains marked as experimental

Do not blur that line.
