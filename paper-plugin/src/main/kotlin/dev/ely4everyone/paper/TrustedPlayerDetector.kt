package dev.ely4everyone.paper

import java.nio.charset.StandardCharsets
import java.util.UUID

object TrustedPlayerDetector {
    fun isTrustedForwardedUuid(username: String, actualUuid: UUID): Boolean {
        return actualUuid != offlineUuid(username)
    }

    fun offlineUuid(username: String): UUID {
        return UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))
    }
}

