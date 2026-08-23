package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findByIdForUpdate(id: Long): User?

    fun findByLocalIdAndActiveTrue(localId: String): User?

    fun findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email: String): User?

    fun findByNicknameAndActiveTrue(nickname: String): User?

    fun findByIdAndActiveTrue(id: Long): User?

    fun findAllByIdInAndActiveTrue(ids: Collection<Long>): List<User>

    fun findByEmailContainingIgnoreCaseAndActiveTrue(email: String): List<User>

    fun findAllByNicknameStartingWithAndActiveTrue(nickname: String): List<User>

    fun findAllByEmailAndActiveTrue(email: String): List<User>

    fun existsByLocalIdAndActiveTrue(localId: String): Boolean
}
