package com.wafflestudio.snutt.core.domain.friend.repository

import com.wafflestudio.snutt.core.domain.friend.model.Friend
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface FriendRepository : JpaRepository<Friend, Long> {
    @Query(
        "SELECT f FROM Friend f WHERE (f.fromUserId = :a AND f.toUserId = :b) OR " +
            "(f.fromUserId = :b AND f.toUserId = :a)",
    )
    fun findByUserPair(
        a: Long,
        b: Long,
    ): Friend?

    @Query(
        "SELECT f FROM Friend f WHERE f.isAccepted = true AND (f.fromUserId = :userId OR f.toUserId = :userId) " +
            "ORDER BY f.createdAt DESC",
    )
    fun findActiveByUserId(userId: Long): List<Friend>

    fun findByFromUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(userId: Long): List<Friend>

    fun findByToUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(userId: Long): List<Friend>
}
