package com.wafflestudio.snutt.core.domain.pushpreference.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PushPreferenceDto(
    val pushPreferences: List<PushPreferenceItem>,
)

data class PushPreferenceItem(
    val type: PushPreferenceType,
    val isEnabled: Boolean,
)

@Service
class PushPreferenceService(
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val userRepository: UserRepository,
) {
    fun getPushPreferences(userId: Long): PushPreferenceDto =
        PushPreferenceDto(pushPreferenceRepository.findAllByUserId(userId).map { PushPreferenceItem(it.type, it.isEnabled) })

    @Transactional
    fun savePushPreferences(
        userId: Long,
        dto: PushPreferenceDto,
    ) {
        val items = dto.pushPreferences
        val user = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val existing = pushPreferenceRepository.findAllByUserId(userId).associateBy { it.type }
        val requestedTypes = items.map { it.type }.toSet()
        pushPreferenceRepository.deleteAll(existing.values.filter { it.type !in requestedTypes })
        items.forEach { item ->
            val preference = existing[item.type] ?: PushPreference(user = user, type = item.type)
            preference.isEnabled = item.isEnabled
            pushPreferenceRepository.save(preference)
        }
    }
}
