package dev.ely4everyone.mod.ui

import dev.ely4everyone.mod.session.TokenHealthMonitor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.screen.TitleScreen

object TitleScreenIntegration {
    fun register() {
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            if (screen !is TitleScreen) {
                return@register
            }

            // Force re-evaluate health when title screen opens
            TokenHealthMonitor.forceReevaluate()

            val button = ElyIconButton(
                x = screen.width / 2 - 124 - ElyIconButton.SIZE - 4,
                y = screen.height / 4 + 48 + 84,
            ) {
                client.setScreen(ElyAuthScreen(screen))
            }

            Screens.getButtons(screen).add(button)
        }
    }
}
