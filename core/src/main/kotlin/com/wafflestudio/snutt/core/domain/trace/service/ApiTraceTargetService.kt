package com.wafflestudio.snutt.core.domain.trace.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.trace.event.ApiTraceTargetsChangedEvent
import com.wafflestudio.snutt.core.domain.trace.model.ApiTraceTarget
import com.wafflestudio.snutt.core.domain.trace.repository.ApiTraceTargetRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ApiTraceTargetDisplay(
    val target: ApiTraceTarget,
    val user: User,
)

@Service
class ApiTraceTargetService(
    private val apiTraceTargetRepository: ApiTraceTargetRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun getAll(): List<ApiTraceTargetDisplay> {
        val targets = apiTraceTargetRepository.findAll()
        val users = userRepository.findAllById(targets.map { it.userId }).associateBy { it.id!! }
        return targets.mapNotNull { target -> users[target.userId]?.let { ApiTraceTargetDisplay(target, it) } }
    }

    @Transactional
    fun add(
        userId: Long,
        memo: String?,
    ): ApiTraceTargetDisplay {
        val user = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val target =
            apiTraceTargetRepository.findByUserId(userId)?.apply { this.memo = memo }
                ?: apiTraceTargetRepository.save(ApiTraceTarget(userId = userId, memo = memo))
        eventPublisher.publishEvent(ApiTraceTargetsChangedEvent(userId))
        return ApiTraceTargetDisplay(target, user)
    }

    @Transactional
    fun remove(userId: Long) {
        val target = apiTraceTargetRepository.findByUserId(userId) ?: return
        apiTraceTargetRepository.delete(target)
        eventPublisher.publishEvent(ApiTraceTargetsChangedEvent(userId))
    }
}
