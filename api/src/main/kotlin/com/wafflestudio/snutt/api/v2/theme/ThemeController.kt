package com.wafflestudio.snutt.api.v2.theme

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.theme.dto.ThemePublicationDisplay
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind
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
    val colors: List<ColorSet>,
    val kind: ThemeKind,
    val builtinCode: String?,
    val publicationId: Long?,
    val isDefault: Boolean,
)

data class ThemePublicationResponse(
    val id: Long,
    val name: String,
    val colors: List<ColorSet>,
    val authorId: Long?,
    val authorNickname: String?,
    val authorAnonymous: Boolean,
    val listed: Boolean,
    val downloadCount: Long,
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
    @field:NotBlank val name: String,
    val authorAnonymous: Boolean,
)

private fun TimetableThemeDisplay.toResponse() = ThemeResponse(id, name, colors, kind, builtinCode, publicationId, isDefault)

private fun ThemePublicationDisplay.toResponse() =
    ThemePublicationResponse(
        id,
        name,
        colors,
        authorId.takeUnless { authorAnonymous },
        authorNickname,
        authorAnonymous,
        listed,
        downloadCount,
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
    ) = timetableThemeService.deleteTheme(userId, themeId)

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
    ): ThemeResponse = timetableThemeService.copyTheme(userId, themeId).toResponse()

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

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @CurrentUserId userId: Long,
        @PathVariable themeId: Long,
        @Valid @RequestBody body: ThemePublishRequest,
    ): ThemePublicationResponse = timetableThemeService.publishTheme(userId, themeId, body.name, body.authorAnonymous).toResponse()
}

@RestController
@RequestMapping("/v2/theme-publications")
class ThemePublicationController(
    private val timetableThemeService: TimetableThemeService,
) {
    @GetMapping("")
    fun getPublications(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) query: String?,
    ): CursorPage<ThemePublicationResponse> = timetableThemeService.getPublications(cursor, query).map { it.toResponse() }

    @GetMapping("/friends")
    fun getFriendsPublications(
        @CurrentUserId userId: Long,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<ThemePublicationResponse> = timetableThemeService.getFriendsPublications(userId, cursor).map { it.toResponse() }

    @GetMapping("/me")
    fun getMyPublications(
        @CurrentUserId userId: Long,
    ): List<ThemePublicationResponse> = timetableThemeService.getMyPublications(userId).map { it.toResponse() }

    @GetMapping("/{publicationId}")
    fun getPublication(
        @CurrentUserId userId: Long,
        @PathVariable publicationId: Long,
    ): ThemePublicationResponse = timetableThemeService.getPublication(userId, publicationId).toResponse()

    @PostMapping("/{publicationId}/download")
    fun downloadTheme(
        @CurrentUserId userId: Long,
        @PathVariable publicationId: Long,
    ): ThemeResponse = timetableThemeService.downloadTheme(userId, publicationId).toResponse()

    @DeleteMapping("/{publicationId}")
    fun unpublishTheme(
        @CurrentUserId userId: Long,
        @PathVariable publicationId: Long,
    ) = timetableThemeService.unpublishTheme(userId, publicationId)
}
