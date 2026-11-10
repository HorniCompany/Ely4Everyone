package dev.ely4everyone.gametest

import com.mojang.authlib.GameProfile
import dev.ely4everyone.mod.ui.ElyAuthScreen
import dev.ely4everyone.mod.ui.ElyIconButton
import java.util.Optional
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.locale.Language
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.level.levelgen.presets.WorldPresets

/**
 * The real game, on every Minecraft version the mod is built for (CI: xvfb on Linux).
 * No Ely.by login here: the client plays offline, so these checks cover what works without one -
 * the mod loads, its UI, other players' Ely.by skins by nick, and names that used to crash.
 * Screenshots end up in build/run/clientGameTest/screenshots.
 */
@Suppress("UnstableApiUsage")
class ElyClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        // CI runners have 2 cores and software OpenGL: keep rendering cheap so world generation keeps up.
        context.runOnClient<RuntimeException> { mc ->
            mc.options.renderDistance().set(2)
            mc.options.simulationDistance().set(5)
            mc.options.framerateLimit().set(30)
        }
        titleScreenHasElyButton(context)
        elyScreenOpens(context, "ely-screen-en")
        skinsByNick(context)
        singleplayer(context)
        dedicatedServer(context)
        russian(context)
    }

    private fun titleScreenHasElyButton(context: ClientGameTestContext) {
        context.waitForScreen(TitleScreen::class.java)
        val found = context.computeOnClient<Boolean, RuntimeException> { mc ->
            currentScreen(mc)?.children()?.any { it is ElyIconButton } == true
        }
        check(found) { "no Ely.by button on the title screen" }
        context.takeScreenshot("title-screen")
    }

    private fun elyScreenOpens(context: ClientGameTestContext, screenshot: String) {
        val title = context.computeOnClient<Screen, RuntimeException> { mc ->
            currentScreen(mc) ?: error("no screen to return to")
        }
        context.setScreen { ElyAuthScreen(title) }
        context.waitForScreen(ElyAuthScreen::class.java)
        context.waitTicks(30) // the panel slides in for 0.7 s
        context.takeScreenshot(screenshot)
        context.setScreen { title }
        context.waitForScreen(TitleScreen::class.java)
    }

    /** SkinManager.get goes through our mixin: Ely.by skin by nick, never a crash on odd names. */
    private fun skinsByNick(context: ClientGameTestContext) {
        val ely = skinOf(context, "ErickSkrauch")
        check(ely.isPresent && isDownloaded(ely.get())) {
            "ErickSkrauch should wear the Ely.by skin, got ${ely.map { it.body().texturePath() }}"
        }
        for (name in listOf("Bad Name", "§cAdmin", "a/b")) {
            val skin = skinOf(context, name)
            check(skin.map { !isDownloaded(it) }.orElse(true)) { "'$name' must not get a downloaded skin" }
        }
    }

    private fun singleplayer(context: ClientGameTestContext) {
        context.worldBuilder().adjustSettings { state ->
            // Superflat generates instantly; the default world timed out on CI (1.21.11).
            state.normalPresetList.firstOrNull { it.preset()?.`is`(WorldPresets.FLAT) == true }?.let(state::setWorldType)
        }.create().use { world ->
            context.waitTicks(100)
            summonMannequins { world.server.runCommand(it) }
            context.waitTicks(100)
            context.takeScreenshot("singleplayer")
        }
    }

    private fun dedicatedServer(context: ClientGameTestContext) {
        val properties = Properties().apply {
            setProperty("level-type", "minecraft:flat")
            setProperty("generate-structures", "false")
            setProperty("view-distance", "3")
            setProperty("simulation-distance", "5")
        }
        context.worldBuilder().createServer(properties).use { server ->
            server.connect().use {
                context.waitTicks(100)
                summonMannequins { server.runCommand(it) }
                context.waitTicks(100)
                context.takeScreenshot("dedicated-server")
            }
        }
    }

    /** Russian client: Russian text; everything else falls back to English. */
    private fun russian(context: ClientGameTestContext) {
        context.runOnClient<RuntimeException> { mc ->
            mc.options.languageCode = "ru_ru"
            mc.languageManager.setSelected("ru_ru")
            mc.reloadResourcePacks()
        }
        context.waitFor({ Language.getInstance().getOrDefault("ely4everyone.button.login") == "Войти через Ely.by" }, 20 * 60)
        context.waitForScreen(TitleScreen::class.java)
        elyScreenOpens(context, "ely-screen-ru")
    }

    private fun skinOf(context: ClientGameTestContext, name: String): Optional<PlayerSkin> {
        val profile = GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()), name)
        val future = context.computeOnClient<CompletableFuture<Optional<PlayerSkin>>, RuntimeException> { mc ->
            mc.skinManager.get(profile)
        }
        context.waitFor({ future.isDone }, 20 * 30)
        check(!future.isCompletedExceptionally) { "skin lookup for '$name' failed" }
        return future.join()
    }

    /** Downloaded skins are registered as minecraft:skins/<hash>, the built-in ones under entity/player. */
    private fun isDownloaded(skin: PlayerSkin): Boolean = skin.body().texturePath().path.startsWith("skins/")

    private fun summonMannequins(run: (String) -> Unit) {
        // Mannequins (1.21.9+) draw a player skin for a profile name; the odd name must not crash rendering.
        run("""execute as @p at @s run summon minecraft:mannequin ^-1 ^ ^3 {profile:{name:"ErickSkrauch"}}""")
        run("""execute as @p at @s run summon minecraft:mannequin ^1 ^ ^3 {profile:{name:"Bad Name"}}""")
    }

    private fun currentScreen(mc: Minecraft): Screen? =
        //? if >=26.2 {
        /*mc.gui.screen()
        *///?} else
        mc.screen
}
