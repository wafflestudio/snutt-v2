package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface UserRepository : JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByIdAndActiveTrue(id: Long): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByLocalIdAndActiveTrue(localId: String): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByEmailAndIsEmailVerifiedTrueAndActiveTrue(email: String): User?

    fun findByLocalIdAndActiveTrue(localId: String): User?

    fun findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email: String): User?

    fun findByNicknameAndNicknameTagAndActiveTrue(
        nickname: String,
        nicknameTag: String,
    ): User?

    fun findByIdAndActiveTrue(id: Long): User?

    fun findAllByIdInAndActiveTrue(ids: Collection<Long>): List<User>

    fun findByEmailContainingIgnoreCaseAndActiveTrue(email: String): List<User>

    fun findAllByNicknameAndActiveTrue(nickname: String): List<User>

    fun findAllByEmailAndActiveTrue(email: String): List<User>

    fun existsByLocalIdAndActiveTrue(localId: String): Boolean
}
