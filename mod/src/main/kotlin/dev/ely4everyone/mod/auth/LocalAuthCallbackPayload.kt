package dev.ely4everyone.mod.auth

/** What the auth host sent back through the browser: a one-time code, or an error. Never tokens. */
data class LocalAuthCallbackPayload(
    val state: String,
    val code: String? = null,
    val error: String? = null,
)
