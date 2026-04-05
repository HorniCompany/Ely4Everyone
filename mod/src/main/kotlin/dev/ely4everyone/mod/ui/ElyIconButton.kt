package dev.ely4everyone.mod.ui

import dev.ely4everyone.mod.session.TokenHealthMonitor
import dev.ely4everyone.mod.session.TokenHealthMonitor.TokenHealth
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.PressableWidget
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.input.AbstractInput
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Квадратная Ely.by-кнопка для title screen с индикатором здоровья токена.
 *
 * Цвет рамки:
 * - 🟢 Зелёный — HEALTHY
 * - 🟡 Жёлтый (пульсирующий) — EXPIRING_SOON
 * - 🔴 Красный — EXPIRED
 * - Обычный — NOT_AUTHENTICATED
 */
class ElyIconButton(
    x: Int,
    y: Int,
    private val onPressAction: () -> Unit,
) : PressableWidget(x, y, SIZE, SIZE, BUTTON_LABEL) {

    init {
        updateTooltip()
    }

    fun updateTooltip() {
        setTooltip(Tooltip.of(Text.literal(TokenHealthMonitor.tooltipText())))
    }

    override fun drawIcon(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val drawX = x
        val drawY = y

        // Shadow
        context.fill(drawX - 1, drawY - 1, drawX + width + 1, drawY + height + 1, COLOR_SHADOW)

        // Icon texture
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            ICON_TEXTURE,
            drawX,
            drawY,
            0f,
            0f,
            width,
            height,
            width,
            height,
        )

        // Health indicator border
        val health = TokenHealthMonitor.currentHealth()
        val borderColor = when (health) {
            TokenHealth.HEALTHY -> COLOR_HEALTHY
            TokenHealth.EXPIRING_SOON -> pulsingColor(COLOR_EXPIRING_DIM, COLOR_EXPIRING_BRIGHT)
            TokenHealth.EXPIRED -> COLOR_EXPIRED
            TokenHealth.NOT_AUTHENTICATED -> if (isSelected) COLOR_BORDER_HOVER else return
        }

        drawBorder(context, drawX, drawY, borderColor)

        // Pulsing dot indicator for non-default states
        if (health != TokenHealth.NOT_AUTHENTICATED) {
            val dotColor = when (health) {
                TokenHealth.HEALTHY -> COLOR_HEALTHY
                TokenHealth.EXPIRING_SOON -> pulsingColor(COLOR_EXPIRING_DIM, COLOR_EXPIRING_BRIGHT)
                TokenHealth.EXPIRED -> COLOR_EXPIRED
                TokenHealth.NOT_AUTHENTICATED -> return
            }
            // Small 3x3 dot in top-right corner
            context.fill(drawX + width - 4, drawY - 1, drawX + width + 1, drawY + 4, 0xFF000000.toInt())
            context.fill(drawX + width - 3, drawY, drawX + width, drawY + 3, dotColor)
        }
    }

    private fun drawBorder(context: DrawContext, drawX: Int, drawY: Int, color: Int) {
        context.fill(drawX - 1, drawY - 1, drawX + width + 1, drawY, color)
        context.fill(drawX - 1, drawY + height, drawX + width + 1, drawY + height + 1, color)
        context.fill(drawX - 1, drawY, drawX, drawY + height, color)
        context.fill(drawX + width, drawY, drawX + width + 1, drawY + height, color)
    }

    /**
     * Creates a pulsing effect between two colors based on system time.
     */
    private fun pulsingColor(dimColor: Int, brightColor: Int): Int {
        val phase = (System.currentTimeMillis() % 1500).toFloat() / 1500f
        val t = (kotlin.math.sin(phase * Math.PI * 2).toFloat() + 1f) / 2f
        return lerpColor(dimColor, brightColor, t)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun channel(aVal: Int, bVal: Int, shift: Int): Int {
            val aChannel = (aVal shr shift) and 0xFF
            val bChannel = (bVal shr shift) and 0xFF
            return ((aChannel + (bChannel - aChannel) * t).toInt().coerceIn(0, 255)) shl shift
        }
        return channel(a, b, 24) or channel(a, b, 16) or channel(a, b, 8) or channel(a, b, 0)
    }

    override fun onPress(input: AbstractInput) {
        onPressAction()
    }

    override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
        appendDefaultNarrations(builder)
    }

    companion object {
        const val SIZE = 20

        private val COLOR_BORDER_HOVER = 0xFF54E4B0.toInt()
        private val COLOR_SHADOW = 0x8C041316.toInt()

        // Health colors
        private val COLOR_HEALTHY = 0xFF4ADE80.toInt()       // Green
        private val COLOR_EXPIRING_DIM = 0xFFB8860B.toInt()  // Dark gold
        private val COLOR_EXPIRING_BRIGHT = 0xFFFFD700.toInt() // Bright gold
        private val COLOR_EXPIRED = 0xFFEF4444.toInt()        // Red

        private val BUTTON_LABEL = Text.literal("Ely.by auth")
        private val ICON_TEXTURE = Identifier.of("ely4everyone", "textures/gui/ely_icon.png")
    }
}
