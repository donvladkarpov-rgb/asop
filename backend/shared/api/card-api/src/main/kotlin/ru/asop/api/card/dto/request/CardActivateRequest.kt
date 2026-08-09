package ru.asop.api.card.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CardIdentityDto(
    @field:NotNull val cardId: UUID,
    @field:NotEmpty val uid: String,
    val regionId: UUID? = null,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val auditServiceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotEmpty val roles: List<String>
)

data class CardActivateRequest(
    @field:Valid @field:NotNull val cardIdentity: CardIdentityDto,
    @field:NotBlank val identityJson: String,
    @field:NotBlank val identitySignature: String,
    val operatorRoles: List<String> = emptyList(),
    val authorizedByRoot: Boolean = false,
    val rootUserId: UUID? = null
)
