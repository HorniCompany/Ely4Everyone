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
- `README.md`

Do not drift away from them without updating them.

## Repository Map

- `mod/` — main Fabric client product
- `shared-auth/` — shared auth/session/ticket/embedded-host core
- `velocity-plugin/` — proxy integration and embedded auth-host
- `paper-bridge/` — standalone Paper auth-host and auth-plugin bridge
- `docs/` — protocol, roadmap, notes
- `servers/` — local sandbox only

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

- `velocity-plugin/` and `paper-bridge/` should remain thin adapters over `shared-auth/`.
- Shared auth/session/ticket compatibility logic belongs in `shared-auth/`.
- Official first-party integration targets remain `FastLogin` and `AuthMe`.
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

Default commands:

- `.\gradlew.bat test`
- `.\gradlew.bat :mod:test`
- `.\gradlew.bat :velocity-plugin:test`
- `.\gradlew.bat :paper-bridge:test`
- `.\gradlew.bat build`

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
- System Access: Has sudo rights and access to all servers with sudo rights.

## Advanced Reasoning Access
- The user has access to GEMINI 3 DEEP THINK (a specialized reasoning mode in Gemini 3.1 with inference-time compute for complex problem-solving; Benchmarks: ARC-AGI 84.6%, Codeforces 3455 Elo).
- Prompting rules for Deep Think: Provide rich context, assign a clear persona/role, define tasks precisely, and explicitly request Chain-of-Thought ("think step-by-step").
- **Crucial Rule:** If a task proves too complex or heavy for the standard agent loop, DELEGATE it to Gemini 3 Deep Think. To execute a delegation:
  1. Commit and push the current code state to GitHub.
  2. Provide the user with a tailored, highly-contextual prompt they can paste into their Deep Think interface.
