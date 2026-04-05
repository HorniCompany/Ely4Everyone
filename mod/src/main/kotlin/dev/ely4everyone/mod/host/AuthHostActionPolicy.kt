package dev.ely4everyone.mod.host

import dev.ely4everyone.mod.config.AuthHostTrustState

data class AuthHostActions(
    val canStartAuth: Boolean,
    val canSyncSession: Boolean,
    val canTrust: Boolean,
    val canForget: Boolean,
    val summary: String,
)

object AuthHostActionPolicy {
    fun forEntry(entry: AuthHostEntry?): AuthHostActions {
        if (entry == null) {
            return AuthHostActions(
                canStartAuth = false,
                canSyncSession = false,
                canTrust = false,
                canForget = false,
                summary = "Auth-host не выбран.",
            )
        }

        val trusted = entry.trustState == AuthHostTrustState.TRUSTED
        val canStart = trusted || entry.source == AuthHostSource.PRESET
        val canForget = entry.source != AuthHostSource.PRESET
        val canTrust = entry.trustState == AuthHostTrustState.PENDING
        val summary = when {
            canStart && entry.source == AuthHostSource.PRESET -> "Host готов к использованию без дополнительных шагов."
            canStart && trusted -> "Host доверен и готов к Ely.by login."
            canTrust -> "Перед логином этот host нужно явно добавить в trusted list."
            entry.trustState == AuthHostTrustState.BLOCKED -> "Host заблокирован и не может использоваться."
            else -> "Host сейчас недоступен для Ely.by login."
        }

        return AuthHostActions(
            canStartAuth = canStart,
            canSyncSession = canStart,
            canTrust = canTrust,
            canForget = canForget,
            summary = summary,
        )
    }
}
