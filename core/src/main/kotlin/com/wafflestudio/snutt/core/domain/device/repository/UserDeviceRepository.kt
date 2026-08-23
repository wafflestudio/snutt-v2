package com.wafflestudio.snutt.core.domain.device.repository

import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    fun findAllByUserIdAndIsDeletedFalse(userId: Long): List<UserDevice>

    fun findAllByUserIdInAndIsDeletedFalse(userIds: Collection<Long>): List<UserDevice>

    fun findByUserIdAndDeviceIdAndIsDeletedFalse(
        userId: Long,
        deviceId: String,
    ): UserDevice?

    fun findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(
        userId: Long,
        fcmRegistrationId: String,
    ): UserDevice?

    fun findByFcmRegistrationIdAndIsDeletedFalse(fcmRegistrationId: String): UserDevice?

    @Transactional
    @Modifying
    @Query(
        "UPDATE UserDevice d SET d.isDeleted = true " +
            "WHERE d.fcmRegistrationId IN :registrationIds AND d.isDeleted = false",
    )
    fun markDeletedByFcmRegistrationIds(registrationIds: Collection<String>)
}
