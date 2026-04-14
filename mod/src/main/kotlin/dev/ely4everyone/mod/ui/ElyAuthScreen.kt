package dev.ely4everyone.mod.ui

import dev.ely4everyone.mod.auth.AuthFlowState
import dev.ely4everyone.mod.auth.AuthFlowStatus
import dev.ely4everyone.mod.auth.AuthWorkflowManager
import dev.ely4everyone.mod.config.AuthHostTrustState
import dev.ely4everyone.mod.config.ClientAuthConfig
import dev.ely4everyone.mod.config.ModConfig
import dev.ely4everyone.mod.config.ModConfigStore
import dev.ely4everyone.mod.session.TokenHealthMonitor
import dev.ely4everyone.mod.session.TokenHealthMonitor.TokenHealth
import dev.ely4everyone.mod.host.AuthHostActionPolicy
import dev.ely4everyone.mod.host.AuthHostCatalog
import dev.ely4everyone.mod.host.AuthHostEntry
import dev.ely4everyone.mod.host.AuthHostSource
import dev.ely4everyone.mod.host.DiscoveredAuthHost
import dev.ely4everyone.mod.session.ClientSessionState
import dev.ely4everyone.mod.session.ClientSessionStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.PlayerSkinWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.client.util.DefaultSkinHelper
import net.minecraft.entity.player.PlayerSkinType
import net.minecraft.entity.player.SkinTextures
import net.minecraft.text.Text
import net.minecraft.util.AssetInfo
import net.minecraft.util.Identifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.sin
import kotlin.math.pow

/**
 * Главный экран мода Ely4Everyone.
 *
 * Четыре визуальных режима:
 * - LOGIN:   выбор auth-host + кнопка «Войти через Ely.by»
 * - POLLING: ожидание завершения OAuth в браузере
 * - SUCCESS: авторизован — скин-превью, ник, UUID, статус
 * - ERROR:   что-то пошло не так — сообщение + «Попробовать снова»
 */
class ElyAuthScreen(
    private val parent: Screen,
) : Screen(Text.literal("Ely4Everyone")) {

    // ── State ──
    private enum class Mode { LOGIN, POLLING, SUCCESS, ERROR }

    private var mode: Mode = Mode.LOGIN
    private var workingConfig: ModConfig = ModConfigStore.load()
    private var catalog = rebuildCatalog()
    private var selectedIndex: Int = 0
    private var errorMessage: String = ""
    private val openTime = System.currentTimeMillis()

    // ── Widgets (initialized in init()) ──
    private lateinit var loginButton: ButtonWidget
    private lateinit var cancelButton: ButtonWidget
    private lateinit var retryButton: ButtonWidget
    private lateinit var logoutButton: ButtonWidget
    private lateinit var doneButton: ButtonWidget
    private lateinit var closeButton: ButtonWidget
    private lateinit var prevHostButton: ButtonWidget
    private lateinit var nextHostButton: ButtonWidget
    private lateinit var customHostField: TextFieldWidget
    private lateinit var skinWidget: PlayerSkinWidget
    private lateinit var refreshSessionButton: ButtonWidget

    // ── Skin preview ──
    private var previewSourceKey: String? = null
    private var previewFuture: CompletableFuture<NativeImage?>? = null
    private var previewTexture: NativeImageBackedTexture? = null
    private var previewTextureId: Identifier? = null

    // ── Layout constants ──
    private val panelWidth = 280
    private val buttonWidth = 240
    private val buttonHeight = 20
    private val smallButtonWidth = 114

    // ── Computed layout ──
    private val panelLeft get() = width / 2 - panelWidth / 2
    private val panelRight get() = width / 2 + panelWidth / 2
    private val contentLeft get() = width / 2 - buttonWidth / 2
    private val centerX get() = width / 2

    override fun init() {
        // Determine initial mode
        val session = ClientSessionStore.load()
        val authState = AuthWorkflowManager.currentState()
        mode = resolveMode(session, authState)
        catalog = rebuildCatalog()

        val btnY = height / 2 + 20

        // ── LOGIN mode widgets ──
        prevHostButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("◀")) { moveSelection(-1) }
                .dimensions(contentLeft, btnY - 56, 20, buttonHeight)
                .build(),
        )
        nextHostButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("▶")) { moveSelection(1) }
                .dimensions(contentLeft + buttonWidth - 20, btnY - 56, 20, buttonHeight)
                .build(),
        )
        customHostField = TextFieldWidget(
            textRenderer,
            contentLeft,
            btnY - 30,
            buttonWidth,
            buttonHeight,
            Text.literal("URL auth-host"),
        ).apply {
            text = workingConfig.customAuthServerUrl
            setMaxLength(256)
            setChangedListener { value ->
                workingConfig = workingConfig.copy(customAuthServerUrl = value)
                if (selectedEntry()?.id == ClientAuthConfig.CUSTOM_HOST_ID) {
                    catalog = rebuildCatalog()
                }
            }
        }
        addDrawableChild(customHostField)

        loginButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Войти через Ely.by")) {
                saveConfig()
                AuthWorkflowManager.startBrowserLogin()
                mode = Mode.POLLING
                refreshWidgets()
            }.dimensions(contentLeft, btnY, buttonWidth, 24).build(),
        )

        // ── SUCCESS mode: refresh session button ──
        refreshSessionButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Обновить сессию")) {
                val success = AuthWorkflowManager.syncLatestSessionFromAuthHost()
                if (success) {
                    TokenHealthMonitor.forceReevaluate()
                    mode = Mode.SUCCESS
                } else {
                    // If sync failed, start a new browser login
                    saveConfig()
                    AuthWorkflowManager.startBrowserLogin()
                    mode = Mode.POLLING
                }
                refreshWidgets()
            }.dimensions(contentLeft, btnY + 26, buttonWidth, buttonHeight).build(),
        )

        // ── POLLING mode widgets ──
        cancelButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Отмена")) {
                AuthWorkflowManager.cancelCurrentAttempt()
                mode = Mode.LOGIN
                refreshWidgets()
            }.dimensions(contentLeft, btnY + 30, buttonWidth, buttonHeight).build(),
        )

        // ── ERROR mode widgets ──
        retryButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Попробовать снова")) {
                AuthWorkflowManager.resetUiState()
                mode = Mode.LOGIN
                refreshWidgets()
            }.dimensions(contentLeft, btnY + 20, buttonWidth, buttonHeight).build(),
        )

        // ── SUCCESS mode widgets ──
        logoutButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Выйти")) {
                AuthWorkflowManager.clearSession()
                clearPreviewTexture()
                TokenHealthMonitor.forceReevaluate()
                mode = Mode.LOGIN
                refreshWidgets()
            }.dimensions(contentLeft, btnY + 50, smallButtonWidth, buttonHeight).build(),
        )
        doneButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Готово")) {
                saveConfig()
                close()
            }.dimensions(contentLeft + buttonWidth - smallButtonWidth, btnY + 50, smallButtonWidth, buttonHeight).build(),
        )

        skinWidget = addDrawableChild(
            PlayerSkinWidget(
                120,
                150,
                client!!.loadedEntityModels,
                ::currentSkinTextures,
            ),
        )

        // Always invisible close/done
        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Закрыть")) {
                saveConfig()
                close()
            }.dimensions(contentLeft, btnY + 80, buttonWidth, buttonHeight).build(),
        )

        // Pre-fetch skin if session already exists
        ensurePreviewRequested(session)
        
        refreshWidgets()
    }

    override fun tick() {
        val authState = AuthWorkflowManager.currentState()
        val session = ClientSessionStore.load()

        // Auto-transition based on auth state
        when {
            authState.status == AuthFlowStatus.SUCCESS || session.hasUsableAuthSession() -> {
                ensurePreviewRequested(session)
                if (mode != Mode.SUCCESS) {
                    mode = Mode.SUCCESS
                    TokenHealthMonitor.forceReevaluate()
                    refreshWidgets()
                }
            }
            authState.status == AuthFlowStatus.ERROR && mode == Mode.POLLING -> {
                errorMessage = authState.message
                mode = Mode.ERROR
                refreshWidgets()
            }
            authState.status == AuthFlowStatus.POLLING && mode == Mode.LOGIN -> {
                mode = Mode.POLLING
                refreshWidgets()
            }
        }

        // If token expired while on SUCCESS screen, switch to LOGIN
        if (mode == Mode.SUCCESS && TokenHealthMonitor.currentHealth() == TokenHealth.EXPIRED) {
            mode = Mode.LOGIN
            refreshWidgets()
        }

        pollPreviewTexture()
    }

    override fun close() {
        saveConfig()
        client?.setScreen(parent)
    }

    override fun removed() {
        clearPreviewTexture()
    }

    // ══════════════════════════════════════════
//  RENDER
// ══════════════════════════════════════════

override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
    val timeNow = System.currentTimeMillis()
    val timeSinceOpen = timeNow - openTime
    val animProgress = (timeSinceOpen / 700f).coerceIn(0f, 1f)
    val easeOutQuart = 1f - (1f - animProgress).pow(4)

    // Pulsing gradient background
    val pulse = ((sin(timeNow / 1500.0) + 1.0) / 2.0).toFloat()
    val topBgColor = mixColor(0xFF030A07.toInt(), 0xFF08140E.toInt(), pulse)
    val bottomBgColor = mixColor(0xFF0A1812.toInt(), 0xFF142D21.toInt(), pulse)

    val alphaMask = (easeOutQuart * 0xF0).toInt() shl 24
    context.fillGradient(0, 0, width, height, topBgColor and 0xFFFFFF or alphaMask, bottomBgColor and 0xFFFFFF or alphaMask)

    // Main panel with mode-specific colors
    val panelTop = (height / 2 - 110) + ((1f - easeOutQuart) * 30).toInt()
    val panelBottom = (height / 2 + 130) + ((1f - easeOutQuart) * 30).toInt()

    val alphaFill = (easeOutQuart * 0xD0).toInt() shl 24
    val alphaBorder = (easeOutQuart * 0xFF).toInt() shl 24

    val (panelFill, panelBorder) = when (mode) {
        Mode.LOGIN -> Pair(0x121A16 or alphaFill, 0x1E654E or alphaBorder)
        Mode.POLLING -> Pair(0x121A16 or alphaFill, 0x38B48B or alphaBorder)
        Mode.SUCCESS -> Pair(0x121A16 or alphaFill, 0x45DEAA or alphaBorder)
        Mode.ERROR -> Pair(0x2A1518 or alphaFill, 0xA54040 or alphaBorder)
    }

    drawTechPanel(context, panelLeft - 10, panelTop - 10, panelRight + 10, panelBottom + 10, panelFill, panelBorder)

    // Title
    context.drawCenteredTextWithShadow(textRenderer, title, centerX, panelTop, COLOR_TITLE)

    when (mode) {
        Mode.LOGIN -> renderLogin(context, panelTop)
        Mode.POLLING -> renderPolling(context, panelTop)
        Mode.SUCCESS -> renderSuccess(context, panelTop)
        Mode.ERROR -> renderError(context, panelTop)
    }

    super.render(context, mouseX, mouseY, delta)
}

private fun renderLogin(context: DrawContext, panelTop: Int) {
    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Играй на серверах с Ely.by"), centerX, panelTop + 14, COLOR_SUBTITLE)

    val selected = selectedEntry()
    val hostName = selected?.displayName ?: "не выбран"
    val hostUrl = selected?.baseUrl ?: "-"
    val trustText = when (selected?.trustState) {
        AuthHostTrustState.TRUSTED -> "✅ доверенный"
        AuthHostTrustState.PENDING -> "⚠ требует подтверждения"
        AuthHostTrustState.BLOCKED -> "❌ заблокирован"
        null -> "-"
    }

    val infoY = panelTop + 36
    drawTechPanel(context, centerX - 110, infoY - 6, centerX + 110, infoY + 40, 0x70000000.toInt(), 0x501E654E.toInt())

    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Сервер: $hostName"), centerX, infoY, COLOR_TEXT)
    context.drawCenteredTextWithShadow(textRenderer, Text.literal(hostUrl), centerX, infoY + 12, COLOR_DIM)
    context.drawCenteredTextWithShadow(textRenderer, Text.literal(trustText), centerX, infoY + 24, trustColor(selected))

    val actions = AuthHostActionPolicy.forEntry(selected)
    if (actions.canTrust && !actions.canStartAuth) {
        loginButton.message = Text.literal("Доверять и войти")
        loginButton.active = true
    } else if (actions.canStartAuth) {
        loginButton.message = Text.literal("Войти через Ely.by")
        loginButton.active = true
    } else {
        loginButton.message = Text.literal("Выберите рабочий хост")
        loginButton.active = false
    }
}

private fun renderPolling(context: DrawContext, panelTop: Int) {
    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Ожидаю авторизацию..."), centerX, panelTop + 14, COLOR_ACCENT)

    val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Завершите вход в браузере$dots"), centerX, panelTop + 36, COLOR_DIM)

    val authState = AuthWorkflowManager.currentState()
    context.drawCenteredTextWithShadow(textRenderer, Text.literal(authState.message), centerX, panelTop + 56, COLOR_SUBTITLE)

    // Polling spinner animation
    val spinTime = (System.currentTimeMillis() % 2000L).toFloat() / 2000f
    val spinnerCenterX = centerX
    val spinnerCenterY = panelTop + 90
    val radius = 16.0
    val angle = spinTime * Math.PI * 2.0
    context.fill(spinnerCenterX + (kotlin.math.cos(angle) * radius).toInt() - 2, spinnerCenterY + (kotlin.math.sin(angle) * radius).toInt() - 2, spinnerCenterX + (kotlin.math.cos(angle) * radius).toInt() + 2, spinnerCenterY + (kotlin.math.sin(angle) * radius).toInt() + 2, COLOR_ACCENT)
}

private fun renderSuccess(context: DrawContext, panelTop: Int) {
    val session = ClientSessionStore.load()
    ensurePreviewRequested(session)

    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Вы авторизованы ✅"), centerX, panelTop + 14, COLOR_SUCCESS)

    // Skin preview (left side)
    val skinLeft = panelLeft + 10
        val skinTop = panelTop + 34
        
        // tech panel remains at same visual place
        drawTechPanel(context, skinLeft - 4, skinTop - 4, skinLeft + 124, skinTop + 154, 0xAA08140E.toInt(), 0xFF1E654E.toInt())
        drawHologramPlatform(context, skinLeft + 60, skinTop + 148, 80)

        // Make the widget much larger so it doesn't clip, but visually centered over the tech panel
        // Make the widget large enough to not clip its head, but place it properly
        // Make the widget perfectly fitting the tech panel boundaries, fully centered
        skinWidget.visible = true
        skinWidget.setX(skinLeft - 10)
        skinWidget.setY(skinTop + 5)
        skinWidget.setDimensions(140, 140)

    // Info (right side)
    val infoLeft = skinLeft + 134
    val infoY = skinTop + 20
    val username = session.elyUsername ?: "неизвестно"
    val uuid = session.elyUuid ?: "-"
    val host = selectedEntry()?.displayName ?: "horni.cc"

    drawTechPanel(context, infoLeft - 10, infoY - 10, panelRight, skinTop + 154, 0x70000000.toInt(), 0x501E654E.toInt())

    context.drawTextWithShadow(textRenderer, Text.literal("Ник: $username"), infoLeft, infoY, COLOR_TEXT)
    context.drawTextWithShadow(textRenderer, Text.literal("UUID: ${uuid.take(13)}..."), infoLeft, infoY + 14, COLOR_DIM)
    context.drawTextWithShadow(textRenderer, Text.literal("Хост: $host"), infoLeft, infoY + 28, COLOR_DIM)

    val health = TokenHealthMonitor.currentHealth()
    val remaining = TokenHealthMonitor.remainingTimeText()
    val (statusText, statusColor) = when (health) {
        TokenHealth.HEALTHY -> (if (remaining != null) "Сессия: ✅ $remaining" else "Сессия: активна ✅") to COLOR_SUCCESS
        TokenHealth.EXPIRING_SOON -> (if (remaining != null) "Сессия: ⚠ $remaining" else "Сессия: скоро истечёт ⚠") to COLOR_WARNING
        TokenHealth.EXPIRED -> "Сессия: истекла ❌" to COLOR_ERROR
        TokenHealth.NOT_AUTHENTICATED -> "Сессия: не активна" to COLOR_DIM
    }
    context.drawTextWithShadow(textRenderer, Text.literal(statusText), infoLeft, infoY + 46, statusColor)

    refreshSessionButton.visible = health == TokenHealth.EXPIRING_SOON
}

private fun renderError(context: DrawContext, panelTop: Int) {
    context.drawCenteredTextWithShadow(textRenderer, Text.literal("Ошибка авторизации ❌"), centerX, panelTop + 14, COLOR_ERROR)

    val maxWidth = buttonWidth - 10
    val wrapped = wrapText(errorMessage, maxWidth)
    wrapped.take(4).forEachIndexed { index, line ->
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(line), centerX, panelTop + 38 + index * 12, COLOR_DIM)
    }
}


    
    // ══════════════════════════════════════════

    private fun refreshWidgets() {
        val isLogin = mode == Mode.LOGIN
        val isPolling = mode == Mode.POLLING
        val isSuccess = mode == Mode.SUCCESS
        val isError = mode == Mode.ERROR
        val isCustomHost = selectedEntry()?.id == ClientAuthConfig.CUSTOM_HOST_ID

        // LOGIN
        loginButton.visible = isLogin
        prevHostButton.visible = isLogin && catalog.entries.size > 1
        prevHostButton.active = isLogin && catalog.entries.size > 1
        nextHostButton.visible = isLogin && catalog.entries.size > 1
        nextHostButton.active = isLogin && catalog.entries.size > 1
        customHostField.visible = isLogin && isCustomHost
        customHostField.active = isLogin && isCustomHost
        customHostField.setEditable(isLogin && isCustomHost)

        // POLLING
        cancelButton.visible = isPolling

        // SUCCESS
        logoutButton.visible = isSuccess
        doneButton.visible = isSuccess
        skinWidget.visible = isSuccess
        // refreshSessionButton visibility is controlled in renderSuccess based on health
        refreshSessionButton.visible = false

        // ERROR
        retryButton.visible = isError

        // Close button: hide on SUCCESS (use "Готово" instead)
        closeButton.visible = !isSuccess
    }

    // ══════════════════════════════════════════
    //  HOST SELECTION
    // ══════════════════════════════════════════

    private fun moveSelection(delta: Int) {
        if (catalog.entries.isEmpty()) return
        selectedIndex = (selectedIndex + delta).floorMod(catalog.entries.size)
        syncSelectionIntoConfig()
        refreshWidgets()
    }

    private fun selectedEntry(): AuthHostEntry? = catalog.entries.getOrNull(selectedIndex)

    private fun rebuildCatalog(): AuthHostCatalog {
        val config = ClientAuthConfig(
            selectedHostId = workingConfig.selectedAuthServerId,
            customHostUrl = workingConfig.customAuthServerUrl,
            rememberedHosts = workingConfig.rememberedHosts,
            discoveryMode = workingConfig.serverDiscoveryMode,
            preferredLoginMode = workingConfig.preferredLoginMode,
        )
        val built = AuthHostCatalog.build(config, emptyList())
        selectedIndex = built.entries.indexOfFirst { it.id == workingConfig.selectedAuthServerId }
            .takeIf { it >= 0 } ?: 0
        return built
    }

    private fun syncSelectionIntoConfig() {
        selectedEntry()?.let { selected ->
            workingConfig = workingConfig.copy(
                selectedAuthServerId = selected.id,
                relayBaseUrl = if (selected.id == ClientAuthConfig.CUSTOM_HOST_ID) {
                    if (this::customHostField.isInitialized) customHostField.text else workingConfig.customAuthServerUrl
                } else {
                    selected.baseUrl
                },
            )
        }
    }

    private fun saveConfig() {
        if (this::customHostField.isInitialized) {
            workingConfig = workingConfig.copy(customAuthServerUrl = customHostField.text)
        }
        syncSelectionIntoConfig()
        ModConfigStore.save(workingConfig)
    }

    private fun resolveMode(session: ClientSessionState, authState: AuthFlowState): Mode {
        return when {
            authState.status == AuthFlowStatus.SUCCESS || session.hasUsableAuthSession() -> Mode.SUCCESS
            authState.status == AuthFlowStatus.ERROR -> {
                errorMessage = authState.message
                Mode.ERROR
            }
            authState.status == AuthFlowStatus.POLLING || authState.status == AuthFlowStatus.OPENING_BROWSER -> Mode.POLLING
            else -> Mode.LOGIN
        }
    }

    // ══════════════════════════════════════════
    //  SKIN PREVIEW
    // ══════════════════════════════════════════

    private fun currentSkinTextures(): SkinTextures {
        val session = ClientSessionStore.load()
        val textureId = previewTextureId
        if (textureId != null) {
            return SkinTextures.create(
                object : AssetInfo.TextureAsset {
                    override fun id(): Identifier = textureId
                    override fun texturePath(): Identifier = textureId
                },
                null,
                null,
                extractSkinModel(session.elyTexturesValue),
            )
        }
        val uuid = session.elyUuid
            ?.let { rawUuid -> runCatching { UUID.fromString(rawUuid) }.getOrNull() }
            ?: MinecraftClient.getInstance().session?.uuidOrNull
            ?: UUID.randomUUID()
        return DefaultSkinHelper.getSkinTextures(uuid)
    }

    private fun ensurePreviewRequested(session: ClientSessionState) {
        val texturesValue = session.elyTexturesValue ?: return
        if (texturesValue == previewSourceKey || previewFuture != null || previewTextureId != null) return

        previewSourceKey = texturesValue
        val skinUrl = extractSkinUrl(texturesValue) ?: return
        previewFuture = CompletableFuture.supplyAsync {
            runCatching {
                val image = URI.create(skinUrl).toURL().openStream().use(NativeImage::read)
                if (image.height == 32) {
                    val padded = NativeImage(image.format, 64, 64, true)
                    padded.fillRect(0, 0, 64, 64, 0x00000000)
                    
                    fun copyPixel(sx: Int, sy: Int, dx: Int, dy: Int) {
                        if (sx in 0..63 && sy in 0..31 && dx in 0..63 && dy in 0..63) {
                            val argb = image.getColorArgb(sx, sy)
                            val a = (argb ushr 24) and 0xFF
                            val r = (argb ushr 16) and 0xFF
                            val g = (argb ushr 8) and 0xFF
                            val b = argb and 0xFF
                            val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                            padded.setColor(dx, dy, abgr)
                        }
                    }

                    // Manual copy pixel by pixel
                    for (x in 0 until 64) {
                        for (y in 0 until 32) {
                            copyPixel(x, y, x, y)
                        }
                    }
                    
                    // Simple mirroring for left leg (dest: 16,48) from right leg (src: 0,16)
                    for (x in 0 until 4) {
                        for (y in 0 until 16) {
                            // Top/Bottom (4x4)
                            if (y < 4) {
                                copyPixel(4 + x, 16 + y, 16 + x, 48 + y) // top
                                copyPixel(8 + x, 16 + y, 20 + x, 48 + y) // bot
                            }
                        }
                    }
                    for (dx in 0 until 16) {
                        for (dy in 0 until 12) {
                            copyPixel(0 + dx, 20 + dy, 16 + dx, 52 + dy)
                        }
                    }
                    
                    // Direct copy right arm (40,16) to left arm (32,48)
                    for (dx in 0 until 16) {
                        for (dy in 0 until 16) {
                            copyPixel(40 + dx, 16 + dy, 32 + dx, 48 + dy)
                        }
                    }
                    
                    image.close()
                    padded
                } else {
                    image
                }
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    private fun pollPreviewTexture() {
        val future = previewFuture ?: return
        if (!future.isDone) return
        previewFuture = null
        val image = runCatching { future.get() }.getOrNull() ?: return
        val texture = NativeImageBackedTexture({ "ely-auth-preview" }, image)
        val textureId = Identifier.of("ely4everyone", "preview/auth_skin")
        client?.textureManager?.registerTexture(textureId, texture)
        previewTexture?.close()
        previewTexture = texture
        previewTextureId = textureId
    }

    private fun clearPreviewTexture() {
        previewFuture = null
        previewSourceKey = null
        previewTexture?.close()
        previewTexture = null
        previewTextureId = null
    }

    // ══════════════════════════════════════════
    //  DRAWING HELPERS
    // ══════════════════════════════════════════
private fun drawPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, fillColor: Int, borderColor: Int) {
    drawTechPanel(context, left, top, right, bottom, fillColor, borderColor)
}

private fun drawMiniPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int) {
    drawTechPanel(context, left, top, right, bottom, 0xCC0D1411.toInt(), 0xFF1E654E.toInt())
}

private fun mixColor(c1: Int, c2: Int, ratio: Float): Int {
    val a1 = (c1 shr 24) and 0xFF
    val r1 = (c1 shr 16) and 0xFF
    val g1 = (c1 shr 8) and 0xFF
    val b1 = c1 and 0xFF
    val a2 = (c2 shr 24) and 0xFF
    val r2 = (c2 shr 16) and 0xFF
    val g2 = (c2 shr 8) and 0xFF
    val b2 = c2 and 0xFF

    val a = (a1 + (a2 - a1) * ratio).toInt()
    val r = (r1 + (r2 - r1) * ratio).toInt()
    val g = (g1 + (g2 - g1) * ratio).toInt()
    val b = (b1 + (b2 - b1) * ratio).toInt()

    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun drawTechPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, fill: Int, border: Int) {
    // Main body (clipped corners)
    context.fill(left + 2, top, right - 2, bottom, fill)
    context.fill(left, top + 2, right, bottom - 2, fill)

    // Borders
    context.fill(left + 2, top, right - 2, top + 1, border)
    context.fill(left + 2, bottom - 1, right - 2, bottom, border)
    context.fill(left, top + 2, left + 1, bottom - 2, border)
    context.fill(right - 1, top + 2, right, bottom - 2, border)

    // Corners
    context.fill(left + 1, top + 1, left + 2, top + 2, border)
    context.fill(right - 2, top + 1, right - 1, top + 2, border)
    context.fill(left + 1, bottom - 2, left + 2, bottom - 1, border)
    context.fill(right - 2, bottom - 2, right - 1, bottom - 1, border)
}

private fun drawHologramPlatform(context: DrawContext, centerX: Int, centerY: Int, width: Int) {
    val pulse = ((sin(System.currentTimeMillis() / 800.0) + 1.0) / 2.0).toFloat()
    val color1 = mixColor(0x0045DEAA.toInt(), 0xAA45DEAA.toInt(), pulse)
    val color2 = 0x5538B48B.toInt()

    val halfW = width / 2
    // Draw a simulated 3D elliptic or rect platform layer by layer
    context.fill(centerX - halfW, centerY, centerX + halfW, centerY + 1, color1)
    context.fill(centerX - halfW + 4, centerY + 1, centerX + halfW - 4, centerY + 2, color2)
    context.fill(centerX - halfW + 10, centerY + 2, centerX + halfW - 10, centerY + 3, color1)
}

        private fun wrapText(text: String, maxWidth: Int): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) lines += current
                current = word
            }
        }
        if (current.isNotBlank()) lines += current
        return lines.ifEmpty { listOf(text) }
    }

    private fun trustColor(entry: AuthHostEntry?): Int {
        return when (entry?.trustState) {
            AuthHostTrustState.TRUSTED -> COLOR_SUCCESS
            AuthHostTrustState.PENDING -> COLOR_WARNING
            AuthHostTrustState.BLOCKED -> COLOR_ERROR
            null -> COLOR_DIM
        }
    }

    private fun extractSkinModel(texturesValue: String?): PlayerSkinType {
        val decoded = texturesValue?.let {
            runCatching { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }.getOrNull()
        } ?: return PlayerSkinType.WIDE
        val metadata = Regex("\"metadata\"\\s*:\\s*\\{[^}]*\"model\"\\s*:\\s*\"([^\"]+)\"")
            .find(decoded)?.groupValues?.getOrNull(1)
        return PlayerSkinType.byModelMetadata(metadata)
    }

    private fun extractSkinUrl(texturesValue: String): String? {
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(texturesValue), StandardCharsets.UTF_8)
            Regex(""""SKIN"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""")
                .find(decoded)?.groupValues?.getOrNull(1)
        }.getOrNull()
    }

    companion object {
        private val COLOR_TITLE = 0xFFF2FFF8.toInt()
        private val COLOR_SUBTITLE = 0xFF86D9C4.toInt()
        private val COLOR_TEXT = 0xFFF2FFF8.toInt()
        private val COLOR_DIM = 0xFFC0DFD1.toInt()
        private val COLOR_ACCENT = 0xFF38B48B.toInt()
        private val COLOR_SUCCESS = 0xFF45DEAA.toInt()
        private val COLOR_WARNING = 0xFFFFE6A3.toInt()
        private val COLOR_ERROR = 0xFFFFA0A0.toInt()
        private val COLOR_BORDER = 0xFF1E654E.toInt()
    }
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
