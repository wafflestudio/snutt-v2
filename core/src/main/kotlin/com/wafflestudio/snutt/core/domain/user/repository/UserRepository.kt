package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByIdAndActiveTrue(id: Long): User?

    fun findByLocalIdAndActiveTrue(localId: String): User?

    fun findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email: String): User?

    fun findByNicknameAndNicknameTagAndActiveTrue(
        nickname: String,
        nicknameTag: String,
    ): User?

    fun findByIdAndActiveTrue(id: Long): User?

    fun findAllByIdInAndActiveTrue(ids: Collection<Long>): List<User>

    fun findByEmailContainingIgnoreCaseAndActiveTrue(email: String): List<User>

    @Query(
        value = "SELECT nickname_tag FROM `user` WHERE active_nickname = :nickname",
        nativeQuery = true,
    )
    fun findActiveNicknameTags(nickname: String): List<String>

    fun findAllByEmailAndActiveTrue(email: String): List<User>

    fun existsByLocalIdAndActiveTrue(localId: String): Boolean
}
