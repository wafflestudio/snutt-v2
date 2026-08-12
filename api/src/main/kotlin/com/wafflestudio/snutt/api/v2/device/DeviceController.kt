package com.wafflestudio.snutt.api.v2.device

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// FCM 등록 토큰 등록/해제 (v1 /v1/user/device/{id})
@RestController
@RequestMapping("/v2/users/me/devices", "/v1/user/device", "/user/device")
class DeviceController(
    private val deviceService: DeviceService,
) {
    @PostMapping("/{registrationId}")
    fun addRegistrationId(
        @CurrentUser user: User,
        @PathVariable registrationId: String,
        @RequestAttribute clientInfo: ClientInfo,
    ) {
        if (registrationId.isBlank()) throw SnuttException(ErrorType.INVALID_PARAMETER)
        deviceService.addRegistrationId(user, registrationId, clientInfo)
    }

    @DeleteMapping("/{registrationId}")
    fun removeRegistrationId(
        @CurrentUser user: User,
        @PathVariable registrationId: String,
    ) {
        if (registrationId.isBlank()) throw SnuttException(ErrorType.INVALID_PARAMETER)
        deviceService.removeRegistrationId(user, registrationId)
    }
}
