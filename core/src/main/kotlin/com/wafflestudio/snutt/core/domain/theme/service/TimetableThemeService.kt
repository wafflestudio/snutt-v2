package com.wafflestudio.snutt.core.domain.theme.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.util.CopyTitle
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.theme.dto.ThemePublicationDisplay
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.model.UserPreference
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeCursor
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.UserPreferenceRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimetableThemeService(
    private val timetableThemeRepository: TimetableThemeRepository,
    private val publishedThemeRepository: PublishedThemeRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val userRepository: UserRepository,
    private val friendRepository: FriendRepository,
    private val cursorCodec: CursorCodec,
) {
    fun getThemes(userId: Long): List<TimetableThemeDisplay> {
        val defaultId = getDefaultThemeId(userId)
        val themes =
            (
                timetableThemeRepository.findByUserIdOrderById(userId) +
                    timetableThemeRepository.findByBuiltinCodeIsNotNullOrderById()
            ).sortedBy { it.id }
        return displays(themes, defaultId).values.toList()
    }

    fun getTheme(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay = displays(listOf(findThemeAvailableToUser(userId, themeId)), getDefaultThemeId(userId)).getValue(themeId)

    fun getThemesByIds(themeIds: Collection<Long>): Map<Long, TimetableThemeDisplay> =
        displays(timetableThemeRepository.findAllById(themeIds.distinct()))

    @Transactional
    fun addTheme(
        userId: Long,
        name: String,
        colors: List<ColorSet>,
    ): TimetableThemeDisplay {
        validateName(name)
        validatePalette(colors)
        val theme = timetableThemeRepository.save(TimetableTheme(userId, name = name, colors = colors.toList()))
        return displays(listOf(theme)).getValue(theme.id!!)
    }

    @Transactional
    fun modifyTheme(
        userId: Long,
        themeId: Long,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay {
        val theme = editableTheme(userId, themeId)
        name?.let {
            validateName(it)
            theme.name = it
        }
        colors?.let { palette ->
            validatePalette(palette)
            val timetableIds = timetableRepository.findByUserIdAndThemeId(userId, themeId).map { it.id!! }
            timetableLectureRepository.findByTimetableIdIn(timetableIds).forEach { lecture ->
                if (lecture.paletteIndex >= palette.size) lecture.paletteIndex %= palette.size
            }
            theme.colors = palette.toList()
        }
        return displays(listOf(theme), getDefaultThemeId(userId)).getValue(themeId)
    }

    @Transactional
    fun copyTheme(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        val source = getTheme(userId, themeId)
        return addTheme(userId, CopyTitle.next(source.name, getThemes(userId).map { it.name }), source.colors)
    }

    @Transactional
    fun deleteTheme(
        userId: Long,
        themeId: Long,
    ) {
        val theme =
            timetableThemeRepository.findByIdAndUserId(themeId, userId)
                ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        val fallback = builtinTheme("snutt")
        val fallbackId = fallback.id!!
        userPreferenceRepository.findByUserId(userId)?.let { preference ->
            if (preference.defaultThemeId == themeId) {
                preference.defaultThemeId = fallbackId
                userPreferenceRepository.saveAndFlush(preference)
            }
        }
        val timetables = timetableRepository.findByUserIdAndThemeId(userId, themeId)
        timetables.forEach { it.themeId = fallbackId }
        val paletteSize = checkNotNull(fallback.colors).size
        timetableLectureRepository.findByTimetableIdIn(timetables.map { it.id!! }).forEach { lecture ->
            if (lecture.paletteIndex >= paletteSize) lecture.paletteIndex %= paletteSize
        }
        timetableRepository.flush()
        timetableThemeRepository.delete(theme)
    }

    @Transactional
    fun setDefault(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        findThemeAvailableToUser(userId, themeId)
        userPreferenceRepository.save(UserPreference(userId, themeId))
        return getTheme(userId, themeId)
    }

    @Transactional
    fun unsetDefault(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        if (getDefaultThemeId(userId) != themeId) throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        val fallbackId = builtinThemeId("snutt")
        userPreferenceRepository.save(UserPreference(userId, fallbackId))
        return getTheme(userId, fallbackId)
    }

    fun getDefaultTheme(userId: Long): TimetableThemeDisplay = getTheme(userId, getDefaultThemeId(userId))

    fun getDefaultThemeId(userId: Long): Long = userPreferenceRepository.findByUserId(userId)?.defaultThemeId ?: builtinThemeId("snutt")

    fun builtinThemeId(code: String): Long = builtinTheme(code).id!!

    private fun builtinTheme(code: String): TimetableTheme =
        timetableThemeRepository.findByBuiltinCode(code) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun findThemeAvailableToUser(
        userId: Long,
        themeId: Long,
    ): TimetableTheme =
        (timetableThemeRepository.findByIdOrNull(themeId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)).also {
            if (it.kind != ThemeKind.BUILTIN && it.userId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        }

    @Transactional
    fun publishTheme(
        userId: Long,
        themeId: Long,
        name: String,
        authorAnonymous: Boolean,
    ): ThemePublicationDisplay {
        val source = editableTheme(userId, themeId)
        validateName(name)
        val publication =
            publishedThemeRepository.save(
                PublishedTheme(userId, themeId, name, checkNotNull(source.colors).toList(), authorAnonymous),
            )
        return publicationDisplays(listOf(publication)).single()
    }

    @Transactional
    fun downloadTheme(
        userId: Long,
        publicationId: Long,
    ): TimetableThemeDisplay {
        val publication =
            publishedThemeRepository
                .findByIdOrNull(publicationId)
                ?.takeIf { it.listed } ?: throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        val theme =
            conflictAs(ErrorType.ALREADY_DOWNLOADED_THEME) {
                timetableThemeRepository.saveAndFlush(TimetableTheme(userId, publicationId = publicationId))
            }
        publishedThemeRepository.incrementDownloadCount(publication.id!!)
        return displays(listOf(theme)).getValue(theme.id!!)
    }

    @Transactional
    fun unpublishTheme(
        userId: Long,
        publicationId: Long,
    ) {
        val publication =
            publishedThemeRepository.findByIdOrNull(publicationId)
                ?: throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        if (publication.authorId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        publication.listed = false
    }

    fun getPublication(
        userId: Long,
        publicationId: Long,
    ): ThemePublicationDisplay {
        val publication =
            publishedThemeRepository.findByIdOrNull(publicationId)
                ?: throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        if (!publication.listed &&
            publication.authorId != userId &&
            !timetableThemeRepository.existsByUserIdAndPublicationId(userId, publicationId)
        ) {
            throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        }
        return publicationDisplays(listOf(publication)).single()
    }

    fun getPublications(
        cursor: String?,
        query: String? = null,
    ): CursorPage<ThemePublicationDisplay> {
        val after = decodeCursor(cursor)
        return publishedThemeRepository.findListed(query, after, PAGE_SIZE + 1).toPublicationPage()
    }

    fun getFriendsPublications(
        userId: Long,
        cursor: String?,
    ): CursorPage<ThemePublicationDisplay> {
        val userIds = friendRepository.findActiveByUserId(userId).map { it.getPartnerUserId(userId) }
        if (userIds.isEmpty()) return CursorPage.of(emptyList(), null, PAGE_SIZE)
        val after = decodeCursor(cursor)
        val authored = publishedThemeRepository.findListedByAuthorIds(userIds, after, PAGE_SIZE + 1)
        val downloaded = publishedThemeRepository.findListedDownloadedByUsers(userIds, after, PAGE_SIZE + 1)
        return (authored + downloaded)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<PublishedTheme> { it.downloadCount }.thenByDescending { it.id })
            .take(PAGE_SIZE + 1)
            .toPublicationPage()
    }

    fun getMyPublications(userId: Long): List<ThemePublicationDisplay> =
        publicationDisplays(publishedThemeRepository.findByAuthorIdOrderByIdDesc(userId))

    private fun editableTheme(
        userId: Long,
        themeId: Long,
    ): TimetableTheme =
        findThemeAvailableToUser(userId, themeId).also {
            if (it.kind != ThemeKind.CUSTOM) throw SnuttException(ErrorType.INVALID_THEME_TYPE)
        }

    private fun displays(
        themes: List<TimetableTheme>,
        defaultId: Long? = null,
    ): Map<Long, TimetableThemeDisplay> {
        val publications = publishedThemeRepository.findAllById(themes.mapNotNull { it.publicationId }.distinct()).associateBy { it.id!! }
        return themes.associate { theme ->
            val publication = theme.publicationId?.let(publications::getValue)
            theme.id!! to
                TimetableThemeDisplay(
                    id = theme.id!!,
                    userId = theme.userId,
                    name = if (publication != null) publication.name else checkNotNull(theme.name),
                    colors = if (publication != null) publication.colors else checkNotNull(theme.colors),
                    kind = theme.kind,
                    builtinCode = theme.builtinCode,
                    publicationId = theme.publicationId,
                    isDefault = theme.id == defaultId,
                )
        }
    }

    private fun publicationDisplays(publications: List<PublishedTheme>): List<ThemePublicationDisplay> {
        val authors = userRepository.findAllById(publications.mapNotNull { it.authorId }.distinct()).associateBy { it.id!! }
        return publications.map {
            ThemePublicationDisplay(
                id = it.id!!,
                sourceThemeId = it.sourceThemeId,
                authorId = it.authorId,
                name = it.name,
                colors = it.colors,
                authorAnonymous = it.authorAnonymous,
                authorNickname = if (it.authorAnonymous) null else authors[it.authorId]?.nickname,
                listed = it.listed,
                downloadCount = it.downloadCount,
            )
        }
    }

    private fun List<PublishedTheme>.toPublicationPage(): CursorPage<ThemePublicationDisplay> =
        cursorCodec.pageOf(
            items = this,
            pageSize = PAGE_SIZE,
            cursorOf = { PublishedThemeCursor(it.downloadCount, it.id!!) },
            transform = ::publicationDisplays,
        )

    private fun decodeCursor(cursor: String?): PublishedThemeCursor? =
        cursorCodec.decode<PublishedThemeCursor>(cursor)?.also {
            if (it.downloadCount < 0 || it.publicationId <= 0) throw SnuttException(ErrorType.INVALID_CURSOR)
        }

    private fun validateName(name: String) {
        if (name.isBlank() || name.length > 128) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
    }

    private fun validatePalette(colors: List<ColorSet>) {
        if (colors.size !in 1..9) throw SnuttException(ErrorType.INVALID_THEME_COLOR_COUNT)
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
