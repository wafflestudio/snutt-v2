package com.wafflestudio.snutt.api.v2.theme

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.validation.Valid
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
    val id: Long,
    val name: String,
    val colors: List<ColorSet>?,
    val isCustom: Boolean,
    val isBuiltin: Boolean,
    val status: ThemeStatus,
    val isDefault: Boolean,
    val publishName: String?,
    val authorAnonymous: Boolean?,
    val downloadCount: Long,
    val authorNickname: String?,
)

data class ThemeAddRequest(
    @field:NotBlank val name: String,
    val colors: List<ColorSet>,
)

data class ThemeModifyRequest(
    val name: String? = null,
    val colors: List<ColorSet>? = null,
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
        colors = colors,
        isCustom = isCustom,
        isBuiltin = isBuiltin,
        status = status,
        isDefault = isDefault,
        publishName = publishName,
        authorAnonymous = authorAnonymous,
        downloadCount = downloadCount,
        authorNickname = authorNickname,
    )

private fun CursorPage<TimetableThemeDisplay>.toResponsePage(): CursorPage<ThemeResponse> = map { it.toResponse() }

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
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<ThemeResponse> = timetableThemeService.getBestThemes(cursor).toResponsePage()

    @GetMapping("/friends")
    fun getFriendsThemes(
        @CurrentUser user: User,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<ThemeResponse> = timetableThemeService.getFriendsThemes(user.id!!, cursor).toResponsePage()

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.setDefault(user.id!!, themeId).toResponse()

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.unsetDefault(user.id!!, themeId).toResponse()

    @GetMapping("/search")
    fun searchThemes(
        @RequestParam keyword: String,
    ): List<ThemeResponse> = timetableThemeService.searchThemes(keyword).map { it.toResponse() }

    @GetMapping("/{themeId}")
    fun getTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.getTheme(user.id!!, themeId).toResponse()

    @PostMapping("")
    fun addTheme(
        @CurrentUser user: User,
        @Valid @RequestBody body: ThemeAddRequest,
    ): ThemeResponse = timetableThemeService.addTheme(user.id!!, body.name, body.colors).toResponse()

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
        @RequestBody body: ThemeModifyRequest,
    ): ThemeResponse = timetableThemeService.modifyTheme(user.id!!, themeId, body.name, body.colors).toResponse()

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ) {
        timetableThemeService.deleteTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
        @Valid @RequestBody body: ThemePublishRequest,
    ) {
        timetableThemeService.publishTheme(user.id!!, themeId, body.publishName, body.authorAnonymous)
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
        @Valid @RequestBody body: ThemeDownloadRequest,
    ): ThemeResponse = timetableThemeService.downloadTheme(user.id!!, themeId, body.name).toResponse()

    @DeleteMapping("/{themeId}/published")
    fun deletePublishedTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ) {
        timetableThemeService.deletePublishedTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.copyTheme(user.id!!, themeId).toResponse()
}
