package com.wafflestudio.snutt.core.domain.device.repository

import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import org.springframework.data.jpa.repository.JpaRepository

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
}
