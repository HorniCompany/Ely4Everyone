package dev.ely4everyone.velocity.auth

import dev.ely4everyone.shared.auth.AuthProfileProperty

data class VelocityProfileProperty(
    val name: String,
    val value: String,
    val signature: String,
)

object VelocityProfilePropertyAdapter {
    fun adapt(properties: List<AuthProfileProperty>): List<VelocityProfileProperty> {
        return properties
            .filter { property ->
                property.name.isNotBlank() && property.value.isNotBlank()
            }
            .map { property ->
                VelocityProfileProperty(
                    name = property.name,
                    value = property.value,
                    signature = property.signature.orEmpty(),
                )
            }
    }
}
