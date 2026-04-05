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
                72,
                90,
                client!!.loadedEntityModels,
                ::currentSkinTextures,
            ),
        )

        // ── Always visible ──
        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Закрыть")) {
                saveConfig()
                close()
            }.dimensions(contentLeft, btnY + 80, buttonWidth, buttonHeight).build(),
        )

        refreshWidgets()
    }

    override fun tick() {
        val authState = AuthWorkflowManager.currentState()
        val session = ClientSessionStore.load()

        // Auto-transition based on auth state
        when {
            authState.status == AuthFlowStatus.SUCCESS || session.hasUsableAuthSession() -> {
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
        // Dark gradient background
        context.fillGradient(0, 0, width, height, 0xF0101820.toInt(), 0xF0180E1A.toInt())

        // Main panel with mode-specific colors
        val panelTop = height / 2 - 90
        val panelBottom = height / 2 + 120
        val (panelFill, panelBorder) = when (mode) {
            Mode.LOGIN -> Pair(0xD0152428.toInt(), 0xFF2D8A7B.toInt())
            Mode.POLLING -> Pair(0xD0152838.toInt(), 0xFF4A9ED9.toInt())
            Mode.SUCCESS -> Pair(0xD0153028.toInt(), 0xFF3DA57B.toInt())
            Mode.ERROR -> Pair(0xD02A1518.toInt(), 0xFFA54040.toInt())
        }
        drawPanel(context, panelLeft - 10, panelTop - 10, panelRight + 10, panelBottom + 10, panelFill, panelBorder)

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
        // Subtitle
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Играй на серверах с Ely.by"),
            centerX,
            panelTop + 14,
            COLOR_SUBTITLE,
        )

        // Host info
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
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Сервер: $hostName"), centerX, infoY, COLOR_TEXT)
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(hostUrl), centerX, infoY + 12, COLOR_DIM)
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(trustText), centerX, infoY + 24, trustColor(selected))

        // If host needs trust, change login button
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
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Ожидаю авторизацию..."),
            centerX,
            panelTop + 14,
            COLOR_ACCENT,
        )

        // Animated dots
        val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Завершите вход в браузере$dots"),
            centerX,
            panelTop + 36,
            COLOR_DIM,
        )

        val authState = AuthWorkflowManager.currentState()
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal(authState.message),
            centerX,
            panelTop + 56,
            COLOR_SUBTITLE,
        )
    }

    private fun renderSuccess(context: DrawContext, panelTop: Int) {
        val session = ClientSessionStore.load()
        ensurePreviewRequested(session)

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Вы авторизованы ✅"),
            centerX,
            panelTop + 14,
            COLOR_SUCCESS,
        )

        // Skin preview (left side)
        val skinLeft = contentLeft
        val skinTop = panelTop + 34
        skinWidget.visible = true
        skinWidget.setX(skinLeft + 4)
        skinWidget.setY(skinTop + 4)
        skinWidget.setDimensions(64, 80)
        drawMiniPanel(context, skinLeft, skinTop, skinLeft + 72, skinTop + 88)

        // Info (right side)
        val infoLeft = skinLeft + 84
        val infoY = skinTop + 6
        val username = session.elyUsername ?: "неизвестно"
        val uuid = session.elyUuid ?: "-"
        val host = selectedEntry()?.displayName ?: "horni.cc"

        context.drawTextWithShadow(textRenderer, Text.literal("Ник: $username"), infoLeft, infoY, COLOR_TEXT)
        context.drawTextWithShadow(textRenderer, Text.literal("UUID: ${uuid.take(13)}..."), infoLeft, infoY + 14, COLOR_DIM)
        context.drawTextWithShadow(textRenderer, Text.literal("Хост: $host"), infoLeft, infoY + 28, COLOR_DIM)

        // Dynamic session status line with health indicator
        val health = TokenHealthMonitor.currentHealth()
        val remaining = TokenHealthMonitor.remainingTimeText()
        val (statusText, statusColor) = when (health) {
            TokenHealth.HEALTHY -> {
                val text = if (remaining != null) "Сессия: ✅ $remaining" else "Сессия: активна ✅"
                text to COLOR_SUCCESS
            }
            TokenHealth.EXPIRING_SOON -> {
                val text = if (remaining != null) "Сессия: ⚠ $remaining" else "Сессия: скоро истечёт ⚠"
                text to COLOR_WARNING
            }
            TokenHealth.EXPIRED -> "Сессия: истекла ❌" to COLOR_ERROR
            TokenHealth.NOT_AUTHENTICATED -> "Сессия: не активна" to COLOR_DIM
        }
        context.drawTextWithShadow(textRenderer, Text.literal(statusText), infoLeft, infoY + 42, statusColor)

        // Show refresh button only when expiring soon
        refreshSessionButton.visible = health == TokenHealth.EXPIRING_SOON
    }

    private fun renderError(context: DrawContext, panelTop: Int) {
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Ошибка авторизации ❌"),
            centerX,
            panelTop + 14,
            COLOR_ERROR,
        )

        // Wrap error message
        val maxWidth = buttonWidth - 10
        val wrapped = wrapText(errorMessage, maxWidth)
        wrapped.take(4).forEachIndexed { index, line ->
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(line),
                centerX,
                panelTop + 38 + index * 12,
                COLOR_DIM,
            )
        }
    }

    // ══════════════════════════════════════════
    //  WIDGET VISIBILITY
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
            runCatching { URI.create(skinUrl).toURL().openStream().use(NativeImage::read) }.getOrNull()
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

    private fun drawPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, fillColor: Int = 0xD0152428.toInt(), borderColor: Int = COLOR_BORDER) {
        context.fill(left, top, right, bottom, fillColor)
        context.fill(left, top, right, top + 1, borderColor)
        context.fill(left, bottom - 1, right, bottom, borderColor)
        context.fill(left, top, left + 1, bottom, borderColor)
        context.fill(right - 1, top, right, bottom, borderColor)
    }

    private fun drawMiniPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int) {
        context.fill(left, top, right, bottom, 0xCC10272B.toInt())
        context.fill(left, top, right, top + 1, 0xFF2A6B5E.toInt())
        context.fill(left, bottom - 1, right, bottom, 0xFF2A6B5E.toInt())
        context.fill(left, top, left + 1, bottom, 0xFF2A6B5E.toInt())
        context.fill(right - 1, top, right, bottom, 0xFF2A6B5E.toInt())
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
        private val COLOR_DIM = 0xFFD8EFE7.toInt()
        private val COLOR_ACCENT = 0xFF54E4B0.toInt()
        private val COLOR_SUCCESS = 0xFF7CFF9A.toInt()
        private val COLOR_WARNING = 0xFFFFE6A3.toInt()
        private val COLOR_ERROR = 0xFFFFA0A0.toInt()
        private val COLOR_BORDER = 0xFF2D8A7B.toInt()
    }
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
