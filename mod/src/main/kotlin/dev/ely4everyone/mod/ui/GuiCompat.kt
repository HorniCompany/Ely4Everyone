package dev.ely4everyone.mod.ui

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor
*///?} else
import net.minecraft.client.gui.GuiGraphics

/*
 * GUI drawing across versions. 26.1 renamed GuiGraphics to GuiGraphicsExtractor (screens extract a
 * render state instead of drawing) and its text methods to centeredText/text. Our code is written
 * the 26.1 way; for older versions the same names are provided below.
 */

//? if >=26.1 {
/*typealias ElyGraphics = GuiGraphicsExtractor
*///?} else {
typealias ElyGraphics = GuiGraphics

fun ElyGraphics.centeredText(font: Font, text: Component, x: Int, y: Int, color: Int) {
    drawCenteredString(font, text, x, y, color)
}

fun ElyGraphics.text(font: Font, text: Component, x: Int, y: Int, color: Int) {
    drawString(font, text, x, y, color)
}
//?}
