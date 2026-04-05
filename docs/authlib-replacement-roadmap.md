# Ely4Everyone Authlib Replacement Research Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this roadmap task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove or disprove whether Ely4Everyone can replace most authlib-injector behavior from inside a Fabric mod, without requiring the user to configure a standalone javaagent manually.

**Architecture:** We are using early **Fabric Mixins** into `com.mojang.authlib.*` to elegantly bypass JEP 451 restrictions. 
**CRITICAL PIVOT:** We completely abandon the ambition of "In-Game Session Hot-Swap" (changing accounts in a running game without restart). It is fundamentally flawed due to aggressive vanilla caching (telemetry, ProfileKeys, PlayerSkinProvider). The ONLY product-safe architecture is **Boot-Time Session Injection**:
1. In-Game UI is strictly for Ely Login and saving the token.
2. The user is asked to restart the client to apply the session.
3. The mod injects the Ely session at `MinecraftClient.<init>` (Pre-Launch).

**Tech Stack:** Minecraft 1.21.1, Fabric toolchain, Mixins, current Ely4Everyone shared auth core, official Ely.by metadata/session APIs, targeted logging and trace instrumentation.

---

## What This Gives Us

- A realistic R&D path utilizing early `com.mojang.authlib.*` mixins.
- A clean split between current production work and high-risk research.
- Concrete success criteria for each experiment so we stop guessing early.

## Non-Goals

- Do NOT attempt to build an in-memory hot-swap of the session. It leads to race conditions, 401 errors, and buggy state.
- Do not attempt universal compatibility with arbitrary authlib-injector servers without a trusted source of API metadata.
- Do not build a launcher/wrapper as the primary path for this research track.

## Proposed Branch Shape

- Create a dedicated research branch, for example `authlib-rd`.
- Keep the current stable line on `1.21.1`.
- Isolate the R&D code under a separate package or module boundary such as `mod/src/main/kotlin/dev/ely4everyone/mod/research/`.
- Reuse current server-side/shared logic only when it helps testing.

## File And Ownership Map

### Research Mod Surface

**Files to create**
- `docs/authlib-replacement-roadmap.md`
- `docs/authlib-replacement-notes.md`
- `mod/src/main/kotlin/dev/ely4everyone/mod/research/ResearchBootstrap.kt`
- `mod/src/main/kotlin/dev/ely4everyone/mod/research/YggdrasilEnvironmentPatch.kt`
- `mod/src/main/kotlin/dev/ely4everyone/mod/research/TextureWhitelistPatch.kt`
- `mod/src/main/kotlin/dev/ely4everyone/mod/research/ServicesKeySetPatch.kt`
- `mod/src/main/kotlin/dev/ely4everyone/mod/research/BootTimeSessionInjection.kt`

## Phase 0: Lock The Research Contract

- [ ] Write a short note in `docs/authlib-replacement-notes.md` defining the only acceptable success target for this track:
  - Level B: works on some Ely-compatible servers
  - Level C: works on most Ely-authlib-compatible servers
- [ ] Record the exact upstream assumptions:
  - Minecraft 1.21.1
  - Fabric tooling
  - Ely.by metadata-driven auth model
- [ ] Add a “known blockers” section:
  - no universal server-provided auth API URL in vanilla protocol
  - secure profile / certificate behavior may limit compatibility

## Phase 1: Observation Before Patching

### Task 1: Trace Real Endpoint Usage

**Goal:** identify which auth/session/services endpoints the 1.21.1 client actually touches in a vanilla + Fabric runtime.

- [ ] Add a temporary trace layer that logs every request target relevant to:
  - `com.mojang.authlib.yggdrasil.YggdrasilEnvironment`

## Phase 2: Hypothesis Attack Plan

Instead of writing UI and state logic, verify these 5 hard hypotheses with isolated Fabric mods:

### Task 2: [Authlib] Environment Overwrite
- [ ] Implement `@Overwrite` or `@Inject` into `YggdrasilEnvironment` variables/factories to redirect to `*.ely.by`.
- **Success:** Network sniffer (Wireshark) shows 0 requests to `mojang.com` when logging into a server without javaagent.

### Task 3: [Authlib] Multi-Key Injection
- [ ] Inject Ely's public key into `ServicesKeySet` ALONG WITH the Mojang key. (Do not just replace it, or vanilla skins will break).
- **Success:** Client loads both Ely skins and licensed players' skins without `SignatureVerificationException`.

### Task 4: [Authlib] Domain Whitelist Bypass
- [ ] `@Inject` into `isAllowedTextureDomain` and force it to return true for `.ely.by`.
- **Success:** Game successfully downloads PNGs from the Ely.by domain.

### Task 5: [Client] The Hot-Swap Death Test
- [ ] Launch offline, change `client.session` via reflection on the Title Screen. Connect to server.
- **Falsification/Success:** Skin does not load, telemetry throws 401 errors. This proves "hot-swap" is fatally flawed.

### Task 6: [Client] Boot-Time Session Injection (The Product Path)
- [ ] In a mixin at `MinecraftClient.<init>`, forcefully replace the passed session with a hardcoded Ely session right before game start.
- **Success:** The game boots cleanly, the skin is visible on the main menu instantly, 0 errors in the logs.

## Phase 3: Real Server Validation

### Task 7: Validate Against Controlled Servers

- [ ] Build a controlled Ely-compatible test environment.
- [ ] Validate exact outcomes in increasing order:
  - Ely UUID appears on server
  - Ely nickname appears on server
  - Ely skin/textures resolve correctly
  - Server treats the player as non-cracked in the intended mode

## Phase 4: Decision Gate

### Task 8: Decide If Authlib Mixins Becomes Product Work

At the end of the PoCs, answer only these questions:

- [ ] Did we reach Level B/C?
- [ ] Does Boot-Time injection eliminate state bugs effectively?

## Testing And Verification

For every PoC:

- [ ] Write the failing test or failing trace first
- [ ] Run the smallest verification command that proves the failure
- [ ] Implement one patch only
- [ ] Re-run the same verification
- [ ] Record exact output in the research notes

Expected commands:

- `./gradlew :mod:test`
- `./gradlew :mod:runClient`

## Practical Recommendations

- Do not start by attempting to solve wrapper/relaunch UX.
- Start with the narrowest claim: “Can we redirect the right 1.21.1 authlib paths from inside a Fabric mod?”

## Expected Outcome

- **Good outcome:** Level B/C PoC works for Ely-mode; Boot-Time injection fully stabilizes state issues. Continue research.
- **Bad outcome:** Mixins clash too hard with other mods and Boot-time session injection still fails.
