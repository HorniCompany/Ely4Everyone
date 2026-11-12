# How the mod works

What the code does today (0.3.0-alpha). Update this file when one of these pieces changes.

## Login: straight to Ely.by

- OAuth **device code** against `account.ely.by` with the public desktop client `ely4everyone1`
  (no client secret, no server of ours in between): `auth/ElyDirectLogin.kt`,
  `auth/AuthWorkflowManager.kt`.
- The mod opens `https://account.ely.by/code?user_code=...` in the browser itself
  (`auth/BrowserOpener.kt`, https only) and polls for the token.
- Loopback + PKCE (`auth/LoopbackLogin.kt`, `LocalAuthCallbackServer.kt`) is written but parked:
  the Ely.by site does not pass `code_challenge` yet. Nothing starts it.
- Research notes behind the direct login: `docs/research-direct-ely-login.md`.

## Where the session lives

- Tokens: **Windows Credential Manager** on Windows; elsewhere a file readable only by the owner
  (`session/SecretVault.kt`).
- Nick, UUID and skin: a file in the user folder (`%APPDATA%`, `~/Library/Application Support` or
  `$XDG_CONFIG_HOME`, folder `ely4everyone`; `session/SessionDirectory.kt`).
- Never in the instance's `config/`: exporting a modpack must not carry someone's login, and one
  login works in every instance.
- `session/TokenHealthMonitor.kt` renews the token at boot and from the client tick, and refreshes
  the player's own skin every 5 minutes.

## Playing as the Ely account

The vanilla `User` object is never replaced. Mixins answer the getters from the mod's own state
(`identity/ActiveElyIdentityManager.kt`) while an Ely identity is active:

| Mixin | Target | What it does |
|---|---|---|
| `MinecraftClientGameProfileMixin` | `Minecraft.getUser`, `getGameProfile`, `getProfileKeyPairManager` | Ely nick/UUID/token; no chat signing keys (Ely gives none to us yet) |
| `ClientLoginNetworkHandlerMixin` | `ClientHandshakePacketListenerImpl.authenticateServer` | joins online-mode servers through `authserver.ely.by/session/join` (`identity/ElyAuthlibBridge.kt`) |
| `YggdrasilMinecraftSessionServiceMixin` | session service `getPackedTextures`, `unpackTextures` | the player's own signed Ely skin |
| `PlayerSkinProviderMixin` | `SkinManager.get(GameProfile)` | other players' skins from Ely.by by nick, see below |

The identity is applied at boot and again after login, logout and token refresh. Things vanilla
cached with the old identity (Realms, telemetry, already loaded skins) may keep it until a restart;
when a feature depends on that, ask the player to restart rather than chase the caches.

Mixin targets are strings the compiler does not check: `docs/porting.md` lists them per version and
how to verify them against the bytecode.

## Skins

`skin/SkinSourcePolicy.kt` decides per profile:

- **own textures** - the active Ely player, from the stored session;
- **own Ely lookup** - the active player without stored textures, by nick;
- **other Ely lookup** - other players: `skinsystem.ely.by/textures/signed/<nick>`; players without
  an Ely skin keep their usual one;
- **vanilla** - everything else.

Names that cannot be an Ely nick (spaces, `§`, slashes) are never looked up, and any error falls
back to the vanilla skin instead of crashing (`skin/ElySkinResolver.kt`). Tests:
`SkinPipelineTest`, `ElySkinResolverTest`, and the in-game test in `mod/src/gametest`.

## Research mixins

`EnvironmentMixin` and `YggdrasilResearchTraceMixin` (config `ely4everyone.research26.mixins.json`)
redirect or trace authlib endpoints. They are not part of the product and only apply with
`-Dely4everyone.research26.trace=true` or `-Dely4everyone.research26.redirectSessionHost=true`
(`research26/Research26MixinPlugin.kt`). Minecraft 26.3 removed their targets, so that version
builds without them.

## Deliberately not done

- No javaagent, no `VirtualMachine.attach`, no runtime JVM patching, no global HTTP interception.
- No server of ours in the login path.
