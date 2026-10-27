package com.wafflestudio.snutt.core.domain.device.model

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToOne

@Entity
class UserDevice(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val user: User,
    var osType: String? = null,
    var osVersion: String? = null,
    var deviceId: String? = null,
    var deviceModel: String? = null,
    var appType: String? = null,
    var appVersion: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var language: Language = Language.KO,
    @Column(nullable = false)
    var fcmRegistrationId: String,
    var isDeleted: Boolean = false,
) : BaseEntity()
