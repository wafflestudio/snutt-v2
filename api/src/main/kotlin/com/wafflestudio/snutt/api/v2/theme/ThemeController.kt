package com.wafflestudio.snutt.api.v2.theme

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
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

@RestController
@RequestMapping("/v2/themes")
class ThemeController(
    private val timetableThemeService: TimetableThemeService,
) {
    @GetMapping("")
    fun getThemes(
        @CurrentUserId userId: Long,
    ): List<ThemeResponse> = timetableThemeService.getThemes(userId).map { it.toResponse() }

    @GetMapping("/best")
    fun getBestThemes(
        @RequestParam page: Int,
    ): List<ThemeResponse> = timetableThemeService.getBestThemes(page).map { it.toResponse() }

    @GetMapping("/friends")
    fun getFriendsThemes(
        @CurrentUserId userId: Long,
        @RequestParam page: Int,
    ): List<ThemeResponse> = timetableThemeService.getFriendsThemes(userId, page).map { it.toResponse() }

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.setDefault(userId, themeId).toResponse()

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.unsetDefault(userId, themeId).toResponse()

    @GetMapping("/search")
    fun searchThemes(
        @RequestParam keyword: String,
    ): List<ThemeResponse> = timetableThemeService.searchThemes(keyword).map { it.toResponse() }

    @GetMapping("/{themeId}")
    fun getTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.getTheme(userId, themeId).toResponse()

    @PostMapping("")
    fun addTheme(
        @CurrentUserId userId: Long,
        @Valid @RequestBody body: ThemeAddRequest,
    ): ThemeResponse = timetableThemeService.addTheme(userId, body.name, body.colors).toResponse()

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
        @RequestBody body: ThemeModifyRequest,
    ): ThemeResponse = timetableThemeService.modifyTheme(userId, themeId, body.name, body.colors).toResponse()

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ) {
        timetableThemeService.deleteTheme(userId, themeId)
    }

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
        @Valid @RequestBody body: ThemePublishRequest,
    ) {
        timetableThemeService.publishTheme(userId, themeId, body.publishName, body.authorAnonymous)
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
        @Valid @RequestBody body: ThemeDownloadRequest,
    ): ThemeResponse = timetableThemeService.downloadTheme(userId, themeId, body.name).toResponse()

    @DeleteMapping("/{themeId}/published")
    fun deletePublishedTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ) {
        timetableThemeService.deletePublishedTheme(userId, themeId)
    }

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.copyTheme(userId, themeId).toResponse()
}
