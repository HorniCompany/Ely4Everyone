package dev.ely4everyone.mod.ui

import dev.ely4everyone.mod.session.TokenHealthMonitor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.screens.TitleScreen

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
                //? if >=26.2 {
                /*client.gui.setScreen(ElyAuthScreen(screen))
                *///?} else
                client.setScreen(ElyAuthScreen(screen))
            }

            //? if >=26.1 {
            /*Screens.getWidgets(screen).add(button)
            *///?} else
            Screens.getButtons(screen).add(button)
        }
    }
}
