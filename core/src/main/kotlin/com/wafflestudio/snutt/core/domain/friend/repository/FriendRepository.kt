package com.wafflestudio.snutt.core.domain.friend.repository

import com.wafflestudio.snutt.core.domain.friend.model.Friend
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface FriendRepository : JpaRepository<Friend, Long> {
    @Query(
        value = "SELECT * FROM friend WHERE user_low = LEAST(:a, :b) AND user_high = GREATEST(:a, :b)",
        nativeQuery = true,
    )
    fun findByUserPair(
        a: Long,
        b: Long,
    ): Friend?

    @Query(
        value =
            "SELECT * FROM (" +
                "(SELECT * FROM friend WHERE from_user_id = :userId AND is_accepted = true) " +
                "UNION ALL " +
                "(SELECT * FROM friend WHERE to_user_id = :userId AND is_accepted = true)" +
                ") f ORDER BY f.created_at DESC",
        nativeQuery = true,
    )
    fun findActiveByUserId(userId: Long): List<Friend>

    fun findByFromUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(userId: Long): List<Friend>

    fun findByToUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(userId: Long): List<Friend>

    @Modifying
    @Query("DELETE FROM Friend f WHERE f.fromUserId = :userId OR f.toUserId = :userId")
    fun deleteByUserId(userId: Long)
}
