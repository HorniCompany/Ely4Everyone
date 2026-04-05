package dev.ely4everyone.shared.host

import dev.ely4everyone.shared.auth.AuthProfileProperty
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class IssuedLoginTicketRecord(
    val ticketId: String,
    val username: String,
    val uuid: String,
    val expiresAtEpochSeconds: Long,
    val properties: List<AuthProfileProperty>,
)

class IssuedLoginTicketStore {
    private val tickets = ConcurrentHashMap<String, IssuedLoginTicketRecord>()

    fun put(record: IssuedLoginTicketRecord, now: Instant = Instant.now()) {
        purgeExpired(now)
        tickets[record.ticketId] = record
    }

    fun consume(ticketId: String, now: Instant = Instant.now()): IssuedLoginTicketRecord? {
        purgeExpired(now)
        return tickets.remove(ticketId)
    }

    private fun purgeExpired(now: Instant) {
        val epoch = now.epochSecond
        tickets.entries.removeIf { (_, record) -> record.expiresAtEpochSeconds <= epoch }
    }
}
