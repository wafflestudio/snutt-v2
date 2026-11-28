package com.wafflestudio.snutt.core.domain.device.repository

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

data class PushTarget(
    val userId: Long,
    val fcmRegistrationId: String,
    val language: Language,
)

interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    fun findAllByUserIdInAndIsDeletedFalse(userIds: Collection<Long>): List<UserDevice>

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.device.repository.PushTarget(d.user.id, d.fcmRegistrationId, d.language) " +
            "FROM UserDevice d WHERE d.user.id IN :userIds AND d.isDeleted = false",
    )
    fun findPushTargets(userIds: Collection<Long>): List<PushTarget>

    fun findByUserIdAndDeviceIdAndIsDeletedFalse(
        userId: Long,
        deviceId: String,
    ): UserDevice?

    fun findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(
        userId: Long,
        fcmRegistrationId: String,
    ): UserDevice?

    fun findByFcmRegistrationIdAndIsDeletedFalse(fcmRegistrationId: String): UserDevice?

    /** 이 토큰의 활성 기기를 잠근다. 다른 사용자가 같은 토큰을 동시에 등록하면 여기서 줄을 선다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByFcmRegistrationIdAndIsDeletedFalse(fcmRegistrationId: String): UserDevice?

    @Transactional
    @Modifying
    @Query(
        "UPDATE UserDevice d SET d.isDeleted = true " +
            "WHERE d.fcmRegistrationId IN :registrationIds AND d.isDeleted = false",
    )
    fun markDeletedByFcmRegistrationIds(registrationIds: Collection<String>)
}
