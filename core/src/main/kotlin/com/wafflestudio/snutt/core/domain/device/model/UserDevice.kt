package com.wafflestudio.snutt.core.domain.device.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "user_device")
class UserDevice(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: User,
    var osType: String? = null,
    var osVersion: String? = null,
    var deviceId: String? = null,
    var deviceModel: String? = null,
    var appType: String? = null,
    var appVersion: String? = null,
    @Column(nullable = false)
    var fcmRegistrationId: String,
    var isDeleted: Boolean = false,
) : ExternalIdEntity()
