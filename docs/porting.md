# Porting and multi-version notes

How the mod is built for several Minecraft versions, what differs between them, and how to add
the next one. Keep this file current whenever a version is added or a difference is found.

## Layout

- `mod/src` - the only source tree. [Stonecutter](https://stonecutter.kikugie.dev/) 0.10 copies it
  for each version (node) and switches `//? if` blocks.
- `settings.gradle.kts` - the list of nodes: `stonecutter { create(":mod") { versions(...) } }`.
- `mod/versions/<minecraft>/gradle.properties` - everything that differs per node:

  | Property | Meaning |
  |---|---|
  | `minecraftVersion` | game version to compile against |
  | `fabricApiVersion`, `fabricLoaderVersion` | Fabric versions for the dev environment |
  | `minecraftDependency` | `minecraft` range written into `fabric.mod.json` |
  | `javaVersion` | toolchain (21 up to 1.21.x, 25 from 26.1) |
  | `minecraftObfuscated` | `true` up to 1.21.11: `fabric-loom-remap`, `modImplementation`, `remapJar`; otherwise plain `fabric-loom`, `implementation`, `jar` |
  | `runDir` | game folder of `runClient`; 1.21.x uses `mod/run`, newer versions their own `mod/run-<version>` so their worlds never upgrade the 1.21.x ones |
  | `researchMixins` | `false` drops the research mixins (they hook authlib internals that authlib 10 removed) |
  | `modrinthGameVersions` | optional, comma list for Modrinth; default is `minecraftVersion` |

- `mod/build.gradle.kts` - the per-node template. `mod/stonecutter.gradle.kts` - which node is
  "active" in `mod/src` (1.21.11; the inactive branches of every `//? if` are commented out).
- Jars: `mod/versions/<minecraft>/build/libs/ely4everyone-mod-<version>+<minecraft>.jar`. The same
  string is the `fabric.mod.json` version and the Modrinth version number (`scripts/release.py`).
- Gradle itself runs on Java 25 (`gradle/gradle-daemon-jvm.properties`, Loom 1.15+ needs it) and
  downloads the JDKs it needs through the foojay resolver.

## Writing version-specific code

```kotlin
//? if >=26.2 {
/*minecraft?.gui?.setScreen(parent)
*///?} else
minecraft?.setScreen(parent)
```

Rules that keep it readable:

- Prefer one compat place over many `//? if`. `ui/GuiCompat.kt` gives every version the 26.1 GUI
  names (`ElyGraphics`, `centeredText`, `text`); the screens are written once.
- If something can be done the same way on all versions without the game API, do that
  (`auth/BrowserOpener.kt` instead of `Util.OS.openUri`, `FriendlyByteBuf(Unpooled.buffer())`
  instead of Fabric's removed `PacketByteBufs`).
- Mixin targets are **strings** the compiler does not check. Every port must verify them against
  the bytecode (see below).
- A whole file can be limited to some versions by wrapping it in `//? if <26.3 { ... //?}`.

## What changed between versions

| From | Change | Where we handle it |
|---|---|---|
| 1.21.11 | last obfuscated version; Yarn is gone for newer versions, the mod uses Mojang mappings everywhere | `minecraftObfuscated`, `scripts/yarn2moj.py` for backports |
| 26.1 | `GuiGraphics` became `GuiGraphicsExtractor`; `Screen.render` -> `extractRenderState`, `AbstractButton.renderContents` -> `extractContents`, `drawCenteredString`/`drawString` -> `centeredText`/`text` | `ui/GuiCompat.kt`, `//? if >=26.1` in the screens |
| 26.1 | Fabric: `Screens.getButtons` -> `getWidgets`, `PacketByteBufs` removed | `TitleScreenIntegration`, `LoginQueryResponder` |
| 26.1 | Java 25, Loom 1.15+ without remapping | node properties |
| 26.2 | `Minecraft.setScreen` moved to `Minecraft.gui.setScreen`; authlib 9 (Friends/Presence services) | `//? if >=26.2` |
| 26.3 | `Util.OS.openUri` removed | `BrowserOpener` |
| 26.3 | authlib 10: `com.mojang.authlib.yggdrasil` removed. The session service is `services.MinecraftServicesSessionService`, same `getPackedTextures`/`unpackTextures`. `Environment` only has a discovery URL | `YggdrasilMinecraftSessionServiceMixin` import alias; research mixins off |

Mixin targets checked so far (all present with the same signatures on 1.21.11, 26.1.2, 26.2, 26.3):
`Minecraft.getUser/getGameProfile/getProfileKeyPairManager` + field `user` (final, the accessor is
`@Mutable`), `ClientHandshakePacketListenerImpl.authenticateServer(String)`, `SkinManager.get(GameProfile)`
and `registerTextures(UUID, MinecraftProfileTextures)`, `ProfileKeyPairManager.EMPTY_KEY_MANAGER`, and the
session service's `getPackedTextures/unpackTextures`.

## Adding a Minecraft version

1. Find the versions: game (`piston-meta.mojang.com/mc/game/version_manifest_v2.json`), Fabric API
   for it (`maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`), loader
   (`meta.fabricmc.net/v2/versions/loader`), and which authlib the game ships (the version json).
2. Create `mod/versions/<minecraft>/gradle.properties` (copy the closest node) and add the version
   to `versions(...)` in `settings.gradle.kts`.
3. `./gradlew :mod:<minecraft>:compileKotlin` and fix the errors with `//? if` or a compat helper.
   Find the new API in the deobfuscated game jar in the Loom cache:

   ```bash
   J=$(find .gradle/loom-cache/minecraftMaven -path "*/<minecraft>/*" -name "minecraft-merged-*.jar")
   javap -p -cp "$J" net.minecraft.client.Minecraft | grep -i screen
   ```

4. Check every mixin target string against that jar and the authlib jar
   (`<gradle home>/caches/modules-2/files-2.1/com.mojang/authlib/<version>/`), like the list above.
5. `./gradlew build` (all nodes, tests run on each) and the in-game checks below.
6. Update this file, the README tables and badges.

## Checking a change

- **Unit tests** run on every node: `./gradlew build`.
- **Game tests** (`mod/src/gametest/.../ElyClientGameTest.kt`, Fabric client game tests) start the real
  game on every node in CI (job `game-tests`, xvfb, Mesa software OpenGL): title screen button, the Ely
  screen in English and Russian, Ely.by skins by nick through `SkinManager.get`, odd nicks without a
  crash, a superflat singleplayer world and a dedicated server with mannequins. Screenshots are the
  `game-test-screenshots-<minecraft>` artifacts of each run. Locally: `./gradlew :mod:<minecraft>:runClientGameTest`
  (opens a game window, mind the shared PC). Lessons from setting it up: client and integrated server
  tick in lockstep, so slow frames stall world loading: superflat worlds and no menu blur (the blurred
  background of 1.21.11's loading screen alone timed it out on 2-core runners), 26.3's SDL3 window needs `SDL_VIDEO_FORCE_EGL=1` under Xvfb, and a game
  that cannot open its window hangs instead of crashing (the job has a 25 minute limit). The client in
  CI plays offline, so login, our own skin and Ely-authenticated servers are not covered.
- **Same jar as before** for pure refactors: compare the Minecraft references of every class
  (`javap -v` constant pools, grep `net/minecraft|class_|method_|field_`) between the old and new
  jar. Every build step of the Stonecutter move was verified this way.
- **In game, on Windows, without disturbing the PC owner:** `scripts/dev/playtest.py` (see its
  docstring). Typical skin check:

  ```bash
  python scripts/dev/playtest.py check            # refuses while a game runs or memory is short
  python scripts/dev/playtest.py server 26.3
  python scripts/dev/playtest.py client 26.3 --server
  python scripts/dev/playtest.py pirate ErickSkrauch   # an Ely.by nick with a skin, no Ely login
  python scripts/dev/playtest.py rcon "tp Cokeef 0 -60 0 facing 0 -59.3 3.5" "tp ErickSkrauch 0 -60 3 facing 0 -59.3 -0.5"
  python scripts/dev/playtest.py key f5 && python scripts/dev/playtest.py key f5
  python scripts/dev/playtest.py shot             # both players should wear their Ely.by skins
  python scripts/dev/playtest.py stop
  ```

  Opening an old world in a newer version shows an upgrade/backup prompt; use a copy of the world
  in that version's run dir.
