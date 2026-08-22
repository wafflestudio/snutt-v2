package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
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
