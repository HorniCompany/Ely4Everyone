package dev.ely4everyone.mod.mixin.client

import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService
import dev.ely4everyone.mod.research26.Research26TraceBus
import dev.ely4everyone.mod.research26.YggdrasilEndpointOverride
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.net.InetAddress
import java.net.URL
import java.util.UUID

@Mixin(YggdrasilMinecraftSessionService::class)
abstract class YggdrasilResearchTraceMixin {
    @Mutable
    @Shadow
    private lateinit var baseUrl: String

    @Mutable
    @Shadow
    private lateinit var joinUrl: URL

    @Mutable
    @Shadow
    private lateinit var checkUrl: URL

    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun `ely4everyone$overrideSessionEndpoints`(
        ci: CallbackInfo,
    ) {
        val roots = YggdrasilEndpointOverride.currentRootsOrNull() ?: return
        val sessionBaseUrl = YggdrasilEndpointOverride.sessionBaseUrl(roots)
        baseUrl = sessionBaseUrl
        joinUrl = URL(sessionBaseUrl + "join")
        checkUrl = URL(sessionBaseUrl + "hasJoined")
        Research26TraceBus.record("constructor.sessionBase", sessionBaseUrl)
    }

    @Inject(method = ["joinServer"], at = [At("HEAD")])
    private fun `ely4everyone$traceJoinServer`(
        profileId: UUID,
        authenticationToken: String,
        serverId: String,
        ci: CallbackInfo,
    ) {
        Research26TraceBus.record("joinServer", joinUrl.toString())
    }

    @Inject(method = ["hasJoinedServer"], at = [At("HEAD")])
    private fun `ely4everyone$traceHasJoinedServer`(
        profileName: String,
        serverId: String,
        address: InetAddress?,
        cir: CallbackInfoReturnable<*>,
    ) {
        Research26TraceBus.record("hasJoinedServer", checkUrl.toString())
    }

    @Inject(method = ["fetchProfile"], at = [At("HEAD")])
    private fun `ely4everyone$traceFetchProfile`(
        profileId: UUID,
        requireSecure: Boolean,
        cir: CallbackInfoReturnable<*>,
    ) {
        Research26TraceBus.record(
            "fetchProfile",
            baseUrl + "profile/" + profileId.toString().replace("-", "") + "?unsigned=" + (!requireSecure),
        )
    }
}
