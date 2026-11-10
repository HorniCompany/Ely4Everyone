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

When work touches the authlib-replacement idea, these documents are the guardrails:

- `docs/authlib-replacement-roadmap.md`
- `docs/authlib-replacement-notes.md`
- `docs/protocol-spec.md`
- `docs/porting.md` - how the mod is built for several Minecraft versions and how to add one
- `README.md` / `README.en.md`

Do not drift away from them without updating them.

## Repository Map

- `mod/` — main Fabric client product. One source tree (`mod/src`) built for every supported
  Minecraft version with Stonecutter: `mod/versions/<minecraft>/` (1.21.11, 26.1.2, 26.2, 26.3).
  See `docs/porting.md`.
- `shared-auth/` — shared auth/session/ticket/embedded-host core (compiled into the mod and both plugins)
- `velocity-plugin/` — proxy integration and embedded auth-host
- `paper-plugin/` — Paper auth-host and auth-plugin bridge
- `scripts/release.py` — release from the owner's PC (GitHub + Modrinth, one jar per Minecraft version)
- `scripts/dev/playtest.py` — invisible in-game checks on Windows (clients, local server, second "pirate" client)
- `scripts/yarn2moj.py` — Yarn -> Mojang name lookup for backports to old versions
- `docs/` — protocol, roadmap, porting notes

`relay/` is no longer part of the supported architecture. Do not rebuild it as a parallel direction.

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
- Embedded auth-host compatibility bridges
- Ely session persistence and boot-time application

### Disallowed direction

- Dynamic `VirtualMachine.attach`
- Late Instrumentation / runtime javaagent injection
- Global HTTP interception as the main compatibility strategy
- “Seamless hot-swap identity” claims without hard evidence

If a task depends on any of the disallowed directions, stop and say so directly.

## Session And Identity Rules

- In-game Ely login UI is acceptable.
- Local Ely session persistence is acceptable.
- Boot-time application of Ely session is the ONLY product-safe path.
- In-memory hot-swap of a running client session is a fundamentally flawed strategy due to aggressive vanilla caching and must be abandoned.

## Server Rules

- `velocity-plugin/` and `paper-plugin/` should remain thin adapters over `shared-auth/`.
- Shared auth/session/ticket compatibility logic belongs in `shared-auth/`.
- Production (horni.cc) uses LimboAuth through `/api/v1/auth/limboauth`; `FastLogin` and `AuthMe` were the original targets.
- The live proxy runs an older plugin build that old 0.1.x mods still log in through: never remove
  server endpoints or protocol pieces a deployed server or old client may use without the owner's decision.
- Deploying the plugin restarts Velocity and kicks players: only with the owner's explicit yes.
- If platform-specific code starts pulling shared protocol logic back into plugin modules, refactor instead of duplicating.

## UI And UX Rules

- The Ely auth screen must be understandable to a normal player, not only to the author.
- Prefer one clear primary action over multiple equal-looking buttons.
- If a host is discovered but not trusted, the UI must explain that clearly and block login until trust is explicit.
- Success state should show:
  - that the player is authorized
  - 3D skin preview
  - nickname
  - UUID
  - active host / session status

## Testing Rules

Use TDD for:

- protocol and compatibility bridges
- endpoint mapping
- trust/discovery logic
- config parsing
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

- `./gradlew build` — every mod version, both plugins, all tests
- `./gradlew :mod:1.21.11:build` (or `:mod:26.3:build`, ...) — one Minecraft version
- `./gradlew :mod:1.21.11:runClient` — the game; 1.21.x runs in `mod/run`, newer versions in `mod/run-<version>`
- `./gradlew :velocity-plugin:test`, `./gradlew :paper-plugin:test`

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

- protocol version or endpoint shape
- authlib replacement strategy
- trust/discovery UX
- session application model
- server setup or self-host flow
- product claims in README

## Anti-Patterns

Do not introduce:

- duplicated auth compatibility logic across modules
- “manager” singletons that own UI + transport + storage + state
- undocumented runtime hacks
- hidden trust of discovered hosts
- broad claims unsupported by logs or tests
- product wording that quietly upgrades a PoC into a “solved problem”

## Completion Standard

The project should move toward one of two clear outcomes:

- a stable, supportable Ely.by Fabric client with early authlib patching
- or an explicitly bounded research branch that remains marked as experimental

Do not blur that line.
