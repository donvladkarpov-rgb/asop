package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserCreateRequest(
    @field:NotBlank @field:Size(max = 100)
    val firstName: String,
    /**
     * Полная фамилия — опциональна. UI работает с инициалом (lastNameInitial),
     * если полная фамилия не указана, UserAdminService деривирует её из initial.
     */
    @field:Size(max = 100)
    val lastName: String? = null,
    /**
     * Обязателен для UI (промпт 005, формат "Фамилия И.О."). Однобуквенный
     * начальный символ кириллицы/латиницы — используется в таблицах и печатях.
     */
    @field:NotBlank @field:Size(min = 1, max = 1)
    val lastNameInitial: String,
    @field:Size(max = 1)
    val patronymicInitial: String? = null,
    @field:Size(max = 20)
    val phone: String? = null,
    @field:Size(max = 255)
    val email: String? = null,
    val password: String? = null,
    val roleIds: List<String> = emptyList(),
    val carrierIds: List<String> = emptyList(),
    val regionIds: List<String> = emptyList()
)
