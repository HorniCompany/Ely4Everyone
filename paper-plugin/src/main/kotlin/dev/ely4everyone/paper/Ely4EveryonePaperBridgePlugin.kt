package dev.ely4everyone.paper

import dev.ely4everyone.shared.host.EmbeddedAuthHttpServer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

class Ely4EveryonePaperBridgePlugin : JavaPlugin(), Listener {
    private lateinit var bridgeConfig: PaperBridgeConfig
    private var embeddedAuthHttpServer: EmbeddedAuthHttpServer? = null
    private val slf4jLogger by lazy { LoggerFactory.getLogger("ely4everyone/paper") }

    override fun onEnable() {
        saveDefaultConfig()
        reloadBridgeConfig()
        server.pluginManager.registerEvents(this, this)
        embeddedAuthHttpServer = EmbeddedAuthHttpServer(bridgeConfig.toEmbeddedAuthHostConfig(), slf4jLogger, dataFolder.toPath()).also {
            it.start()
        }
        logger.info("Ely4Everyone Paper bridge enabled.")
    }

    override fun onDisable() {
        embeddedAuthHttpServer?.stop()
        embeddedAuthHttpServer = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!bridgeConfig.enabled) {
            return
        }

        val player = event.player
        if (!TrustedPlayerDetector.isTrustedForwardedUuid(player.name, player.uniqueId)) {
            return
        }

        if (bridgeConfig.logTrustedLogins) {
            logger.info("Detected trusted forwarded player ${player.name} with UUID ${player.uniqueId}. Scheduling backend auto-login command.")
        }

        if (bridgeConfig.autoLoginCommand.isBlank()) {
            return
        }

        val command = bridgeConfig.autoLoginCommand.replace("{player}", player.name)
        server.scheduler.runTaskLater(
            this,
            Runnable {
                server.dispatchCommand(server.consoleSender, command)
            },
            bridgeConfig.autoLoginDelayTicks,
        )
    }

    override fun reloadConfig() {
        super.reloadConfig()
        reloadBridgeConfig()
    }

    private fun reloadBridgeConfig() {
        bridgeConfig = PaperBridgeConfigLoader.load(this)
    }
}
