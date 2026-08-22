package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.model.UserSocialAuth
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserSocialAuthRepository : JpaRepository<UserSocialAuth, Long> {
    fun findByUserId(userId: Long): List<UserSocialAuth>

    fun findByUserIdIn(userIds: Collection<Long>): List<UserSocialAuth>

    fun findByUserIdAndProvider(
        userId: Long,
        provider: AuthProvider,
    ): UserSocialAuth?

    fun existsByProviderAndSub(
        provider: AuthProvider,
        sub: String,
    ): Boolean

    fun deleteByUserId(userId: Long)

    fun deleteByUserIdAndProvider(
        userId: Long,
        provider: AuthProvider,
    )

    @Query(
        "SELECT u FROM User u JOIN UserSocialAuth s ON s.userId = u.id " +
            "WHERE s.provider = :provider AND s.sub = :sub AND u.active = true",
    )
    fun findActiveUserByProviderAndSub(
        @Param("provider") provider: AuthProvider,
        @Param("sub") sub: String,
    ): User?

    @Query(
        "SELECT s FROM UserSocialAuth s JOIN User u ON u.id = s.userId " +
            "WHERE s.provider = :provider AND s.transferSub = :transferSub AND u.active = true",
    )
    fun findActiveByProviderAndTransferSub(
        @Param("provider") provider: AuthProvider,
        @Param("transferSub") transferSub: String,
    ): UserSocialAuth?
}
