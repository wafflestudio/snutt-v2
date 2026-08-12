package com.wafflestudio.snutt.api.v2.pushpreference

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/push/preferences")
class PushPreferenceController(
    private val pushPreferenceService: PushPreferenceService,
) {
    @GetMapping("")
    fun getPushPreferences(
        @CurrentUser user: User,
    ): PushPreferenceDto = pushPreferenceService.getPushPreferences(user)

    @PostMapping("")
    fun savePushPreferences(
        @CurrentUser user: User,
        @RequestBody dto: PushPreferenceDto,
    ): PushPreferenceDto {
        pushPreferenceService.savePushPreferences(user, dto)
        return pushPreferenceService.getPushPreferences(user)
    }
}
