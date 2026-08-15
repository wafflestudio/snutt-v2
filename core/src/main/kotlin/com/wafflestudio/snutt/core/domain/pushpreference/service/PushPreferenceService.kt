package com.wafflestudio.snutt.core.domain.pushpreference.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PushPreferenceDto(
    val pushPreferences: List<PushPreferenceItem>,
)

data class PushPreferenceItem(
    val type: String,
    val isEnabled: Boolean,
)

@Service
class PushPreferenceService(
    private val pushPreferenceRepository: PushPreferenceRepository,
) {
    fun getPushPreferences(user: User): PushPreferenceDto =
        PushPreferenceDto(
            pushPreferences = pushPreferenceRepository.findAllByUserId(user.id!!).map { it.toItem() },
        )

    @Transactional
    fun savePushPreferences(
        user: User,
        dto: PushPreferenceDto,
    ) {
        val userId = user.id!!
        val existing = pushPreferenceRepository.findAllByUserId(userId)
        val requestedTypes = dto.pushPreferences.map { it.type }
        pushPreferenceRepository.deleteAll(existing.filter { it.type.name !in requestedTypes })
        dto.pushPreferences.forEach { item ->
            val type =
                PushPreferenceType.entries.firstOrNull { it.name == item.type }
                    ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
            val preference =
                existing.firstOrNull { it.type == type }
                    ?: PushPreference(user = user, type = type, isEnabled = item.isEnabled)
            preference.isEnabled = item.isEnabled
            pushPreferenceRepository.save(preference)
        }
    }

    private fun PushPreference.toItem() = PushPreferenceItem(type = type.name, isEnabled = isEnabled)
}
