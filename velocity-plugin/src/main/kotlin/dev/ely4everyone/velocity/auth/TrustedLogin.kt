package dev.ely4everyone.velocity.auth

import dev.ely4everyone.shared.auth.AuthProfileProperty
import dev.ely4everyone.shared.ticket.VerifiedAuthTicket
import java.util.UUID

data class TrustedLogin(
    val uuid: UUID,
    val ticket: VerifiedAuthTicket,
    val properties: List<AuthProfileProperty> = emptyList(),
)
