package com.wafflestudio.snutt.api.v2.theme

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ThemeResponse(
    val id: String?,
    val name: String,
    val colorList: List<ColorSet>?,
    val isCustom: Boolean,
    val status: ThemeStatus,
    val isDefault: Boolean,
    val publishName: String?,
    val authorAnonymous: Boolean?,
    val downloadCount: Long,
    val authorNickname: String?,
)

data class ThemeAddRequest(
    @field:NotBlank val name: String,
    val colorList: List<ColorSet>,
)

data class ThemeModifyRequest(
    val name: String? = null,
    val colorList: List<ColorSet>? = null,
)

data class ThemePublishRequest(
    @field:NotBlank val publishName: String,
    val authorAnonymous: Boolean,
)

data class ThemeDownloadRequest(
    @field:NotBlank val name: String,
)

private fun TimetableThemeDisplay.toResponse() =
    ThemeResponse(
        id = id,
        name = name,
        colorList = colorList,
        isCustom = isCustom,
        status = status,
        isDefault = isDefault,
        publishName = publishName,
        authorAnonymous = authorAnonymous,
        downloadCount = downloadCount,
        authorNickname = authorNickname,
    )

@RestController
@RequestMapping("/v2/themes")
class ThemeController(
    private val timetableThemeService: TimetableThemeService,
) {
    @GetMapping("")
    fun getThemes(
        @CurrentUser user: User,
    ): List<ThemeResponse> = timetableThemeService.getThemes(user.id!!).map { it.toResponse() }

    @GetMapping("/best")
    fun getBestThemes(
        @RequestParam page: Int,
    ): List<ThemeResponse> = timetableThemeService.getBestThemes(page).map { it.toResponse() }

    @GetMapping("/friends")
    fun getFriendsThemes(
        @CurrentUser user: User,
        @RequestParam page: Int,
    ): List<ThemeResponse> = timetableThemeService.getFriendsThemes(user.id!!, page).map { it.toResponse() }

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): ThemeResponse = timetableThemeService.setDefault(user.id!!, themeId).toResponse()

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): ThemeResponse = timetableThemeService.unsetDefault(user.id!!, themeId).toResponse()

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicThemeDefault(
        @CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): ThemeResponse {
        BasicThemeType.fromValue(basicThemeTypeValue)
        return timetableThemeService.setBasicThemeDefault(user.id!!).toResponse()
    }

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicThemeDefault(
        @CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): ThemeResponse =
        timetableThemeService
            .unsetBasicThemeDefault(user.id!!, BasicThemeType.fromValue(basicThemeTypeValue))
            .toResponse()

    @GetMapping("/search")
    fun searchThemes(
        @RequestParam keyword: String,
    ): List<ThemeResponse> = timetableThemeService.searchThemes(keyword).map { it.toResponse() }

    @GetMapping("/{themeId}")
    fun getTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): ThemeResponse = timetableThemeService.getTheme(user.id!!, themeId, null).toResponse()

    @PostMapping("")
    fun addTheme(
        @CurrentUser user: User,
        @RequestBody body: ThemeAddRequest,
    ): ThemeResponse = timetableThemeService.addTheme(user.id!!, body.name, body.colorList).toResponse()

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: ThemeModifyRequest,
    ): ThemeResponse = timetableThemeService.modifyTheme(user.id!!, themeId, body.name, body.colorList).toResponse()

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        timetableThemeService.deleteTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: ThemePublishRequest,
    ) {
        timetableThemeService.publishTheme(user.id!!, themeId, body.publishName, body.authorAnonymous)
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: ThemeDownloadRequest,
    ): ThemeResponse = timetableThemeService.downloadTheme(user.id!!, themeId, body.name).toResponse()

    @DeleteMapping("/{themeId}/published")
    fun deletePublishedTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        timetableThemeService.deletePublishedTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): ThemeResponse = timetableThemeService.copyTheme(user.id!!, themeId).toResponse()
}
