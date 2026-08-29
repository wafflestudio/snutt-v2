package com.wafflestudio.snutt.api.v2.pushpreference

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceService
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
        @CurrentUserId userId: Long,
    ): PushPreferenceDto = pushPreferenceService.getPushPreferences(userId)

    @PostMapping("")
    fun savePushPreferences(
        @CurrentUserId userId: Long,
        @RequestBody dto: PushPreferenceDto,
    ): PushPreferenceDto {
        pushPreferenceService.savePushPreferences(userId, dto)
        return pushPreferenceService.getPushPreferences(userId)
    }
}
