package com.wafflestudio.snutt.core.domain.theme.service

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.pagination.toCursorPage
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.model.UserPreference
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.UserPreferenceRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PublishedThemeCursor(
    val downloadCount: Long,
    val publishedThemeId: Long,
)

@Service
class TimetableThemeService(
    private val timetableThemeRepository: TimetableThemeRepository,
    private val publishedThemeRepository: PublishedThemeRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val userRepository: UserRepository,
    private val friendRepository: FriendRepository,
) {
    companion object {
        private const val MAX_COLOR_COUNT = 9
        private const val MARKET_PAGE_SIZE = 20
        private const val FRIENDS_MARKET_PAGE_SIZE = 10
        private const val SNUTT_BUILTIN_THEME_ID = 1L
        private val copyNumberRegex = """\s\(\d+\)$""".toRegex()
    }

    fun getThemes(userId: Long): List<TimetableThemeDisplay> {
        val defaultThemeId = getDefaultThemeId(userId)
        return timetableThemeRepository.findByUserIdIsNull().sortedBy { it.builtinType }.map {
            it.toDisplay(
                published = null,
                isDefault =
                    it.id == defaultThemeId,
            )
        } +
            getLibraryThemeDisplays(userId, defaultThemeId)
    }

    private fun getLibraryThemeDisplays(
        userId: Long,
        defaultThemeId: Long,
    ): List<TimetableThemeDisplay> {
        val themes = timetableThemeRepository.findByUserIdOrderByUpdatedAtDesc(userId)
        val publishedByThemeId =
            publishedThemeRepository.findByThemeIdIn(themes.mapNotNull { it.id }).associateBy { it.themeId }
        return themes.map { it.toDisplay(published = publishedByThemeId[it.id], isDefault = it.id == defaultThemeId) }
    }

    fun getBestThemes(cursor: String?): CursorPage<TimetableThemeDisplay> {
        val decoded = decodePublishedThemeCursor(cursor)
        val pageable = PageRequest.of(0, MARKET_PAGE_SIZE + 1)
        val themes =
            if (decoded == null) {
                publishedThemeRepository.findAllByOrderByDownloadCountDescIdDesc(pageable)
            } else {
                publishedThemeRepository.findBestPublishedAfter(decoded.downloadCount, decoded.publishedThemeId, pageable)
            }
        return themes.toCursorPage(MARKET_PAGE_SIZE)
    }

    fun getFriendsThemes(
        userId: Long,
        cursor: String?,
    ): CursorPage<TimetableThemeDisplay> {
        val friendUserIds = friendRepository.findActiveByUserId(userId).map { it.getPartnerUserId(userId) }
        if (friendUserIds.isEmpty()) return CursorPage.of(emptyList(), null, FRIENDS_MARKET_PAGE_SIZE)
        val decoded = decodePublishedThemeCursor(cursor)
        val themes =
            publishedThemeRepository.findFriendsPublished(
                friendUserIds,
                decoded?.downloadCount,
                decoded?.publishedThemeId,
                PageRequest.of(0, FRIENDS_MARKET_PAGE_SIZE + 1),
            )
        return themes.toCursorPage(FRIENDS_MARKET_PAGE_SIZE)
    }

    fun searchThemes(keyword: String): List<TimetableThemeDisplay> =
        publishedThemeRepository.findByPublishNameContainingIgnoreCase(keyword).toMarketDisplays()

    @Transactional
    fun addTheme(
        userId: Long,
        name: String,
        colors: List<ColorSet>,
    ): TimetableThemeDisplay {
        validateColorCount(colors)
        return timetableThemeRepository
            .save(TimetableTheme(userId = userId, name = name, colors = colors))
            .toDisplay(published = null)
    }

    @Transactional
    fun modifyTheme(
        userId: Long,
        themeId: Long,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay {
        val theme = getOwnedTheme(userId, themeId)
        return modifyTheme(userId, theme, name, colors)
    }

    private fun modifyTheme(
        userId: Long,
        theme: TimetableTheme,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay {
        name?.let { theme.name = it }
        colors?.let { newColors ->
            validateColorCount(newColors)

            val colorMap = theme.colors.mapIndexed { i, color -> color to newColors[i % newColors.size] }.toMap()
            timetableRepository.findByUserIdAndThemeId(userId, theme.id!!).forEach { timetable ->
                val lectures = timetableLecturesOf(timetable.id!!)
                lectures.filter { it.color in theme.colors }.forEach { lecture ->
                    colorMap[lecture.color]?.let { lecture.color = it }
                }
                timetableLectureRepository.saveAll(lectures)
            }
            theme.colors = newColors
        }
        return theme.toDisplay(published = publishedThemeRepository.findByThemeId(theme.id!!))
    }

    @Transactional
    fun publishTheme(
        userId: Long,
        themeId: Long,
        publishName: String,
        authorAnonymous: Boolean,
    ) {
        val theme = getOwnedTheme(userId, themeId)
        publishTheme(theme, publishName, authorAnonymous)
    }

    private fun publishTheme(
        theme: TimetableTheme,
        publishName: String,
        authorAnonymous: Boolean,
    ) {
        val published =
            publishedThemeRepository.findByThemeId(theme.id!!)
                ?: PublishedTheme(themeId = theme.id!!, publishName = publishName, authorAnonymous = authorAnonymous)
        published.publishName = publishName
        published.authorAnonymous = authorAnonymous
        published.downloadCount = 0
        publishedThemeRepository.save(published)
    }

    @Transactional
    fun downloadTheme(
        downloadedUserId: Long,
        themeId: Long,
        name: String,
    ): TimetableThemeDisplay = downloadTheme(downloadedUserId, findThemeById(themeId), name)

    private fun downloadTheme(
        downloadedUserId: Long,
        theme: TimetableTheme,
        name: String,
    ): TimetableThemeDisplay {
        if (theme.isBuiltin) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        val published = publishedThemeRepository.findByThemeId(theme.id!!) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        if (timetableThemeRepository.existsByOriginThemeIdAndUserId(theme.id!!, downloadedUserId)) {
            throw SnuttException(ErrorType.ALREADY_DOWNLOADED_THEME)
        }
        val downloaded =
            timetableThemeRepository.save(
                TimetableTheme(
                    userId = downloadedUserId,
                    name = name,
                    colors = theme.colors,
                    originThemeId = theme.id,
                    originAuthorId = theme.userId,
                ),
            )
        // 동시 다운로드에도 카운트가 유실되지 않도록 원자 증가로 처리한다(응답값은 증가 전 값일 수 있다)
        publishedThemeRepository.incrementDownloadCount(published.id!!)
        return downloaded.toDisplay(published = null)
    }

    @Transactional
    fun deleteTheme(
        userId: Long,
        themeId: Long,
    ) {
        deleteTheme(userId, getOwnedTheme(userId, themeId))
    }

    private fun deleteTheme(
        userId: Long,
        theme: TimetableTheme,
    ) {
        if (publishedThemeRepository.existsByThemeId(theme.id!!)) throw SnuttException(ErrorType.PUBLISHED_THEME_DELETE_ERROR)

        // 삭제되는 테마가 기본 테마면 내장 테마로 되돌린다. 그렇지 않으면 user_preference의
        // default_theme_id FK가 삭제를 막는다.
        userPreferenceRepository.findByUserId(userId)?.let { pref ->
            if (pref.defaultThemeId == theme.id) {
                pref.defaultThemeId = SNUTT_BUILTIN_THEME_ID
                userPreferenceRepository.saveAndFlush(pref)
            }
        }
        timetableRepository.findByUserIdAndThemeId(userId, theme.id!!).forEach { timetable ->
            timetable.themeId = SNUTT_BUILTIN_THEME_ID
        }
        timetableThemeRepository.delete(theme)
    }

    @Transactional
    fun deletePublishedTheme(
        userId: Long,
        themeId: Long,
    ) {
        deletePublishedTheme(getOwnedTheme(userId, themeId))
    }

    private fun deletePublishedTheme(theme: TimetableTheme) {
        val published = publishedThemeRepository.findByThemeId(theme.id!!) ?: throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        publishedThemeRepository.delete(published)
    }

    @Transactional
    fun copyTheme(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay = copyTheme(userId, findThemeById(themeId))

    private fun copyTheme(
        userId: Long,
        theme: TimetableTheme,
    ): TimetableThemeDisplay {
        if (!theme.isBuiltin && theme.userId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        val baseName = theme.name.replace(copyNumberRegex, "")
        val lastCopiedNumber =
            timetableThemeRepository
                .findByUserIdOrderByUpdatedAtDesc(userId)
                .mapNotNull {
                    it.name
                        .replace(baseName, "")
                        .filter(Char::isDigit)
                        .toIntOrNull()
                }.maxOrNull() ?: 0
        return timetableThemeRepository
            .save(
                TimetableTheme(
                    userId = userId,
                    name = "$baseName (${lastCopiedNumber + 1})",
                    colors = theme.colors,
                ),
            ).toDisplay(published = null)
    }

    @Transactional
    fun setDefault(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        val theme = findThemeById(themeId)
        if (theme.isBuiltin) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        if (theme.userId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        userPreferenceRepository.save(UserPreference(userId = userId, defaultThemeId = theme.id!!))
        return theme.toDisplay(published = publishedThemeRepository.findByThemeId(theme.id!!), isDefault = true)
    }

    fun unsetDefault(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        val current = getDefaultTheme(userId)
        if (current.isBuiltin || current.id != themeId) throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        userPreferenceRepository.save(UserPreference(userId = userId, defaultThemeId = SNUTT_BUILTIN_THEME_ID))
        return builtinTheme(SNUTT_BUILTIN_THEME_ID).toDisplay(published = null, isDefault = true)
    }

    fun getDefaultTheme(userId: Long): TimetableThemeDisplay {
        val theme = findThemeById(getDefaultThemeId(userId))
        return theme.toDisplay(
            published = theme.id?.let { publishedThemeRepository.findByThemeId(it) },
            isDefault = true,
        )
    }

    fun getDefaultThemeId(userId: Long): Long = userPreferenceRepository.findByUserId(userId)?.defaultThemeId ?: SNUTT_BUILTIN_THEME_ID

    fun getTheme(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay = getTheme(userId, findThemeById(themeId))

    private fun getTheme(
        userId: Long,
        theme: TimetableTheme,
    ): TimetableThemeDisplay {
        if (!theme.isBuiltin && theme.userId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        return theme.toDisplay(published = theme.id?.let { publishedThemeRepository.findByThemeId(it) })
    }

    fun getNewColorIndexAndColor(
        themeId: Long,
        usedColors: List<ColorSet?>,
        usedColorIndexes: List<Int>,
    ): Pair<Int, ColorSet?> {
        val theme = timetableThemeRepository.findByIdOrNull(themeId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        return if (theme.isBuiltin) {
            val indexToCount = (1..BasicThemeType.COLOR_COUNT).associateWith { index -> usedColorIndexes.count { it == index } }
            val minCount = indexToCount.minOf { it.value }
            indexToCount.entries
                .filter { (_, count) -> count == minCount }
                .map { it.key }
                .random() to null
        } else {
            val colorToCount = theme.colors.associateWith { color -> usedColors.count { it == color } }
            val minCount = colorToCount.minOf { it.value }
            0 to
                colorToCount.entries
                    .filter { (_, count) -> count == minCount }
                    .map { it.key }
                    .random()
        }
    }

    fun builtinThemeId(basicThemeType: BasicThemeType): Long = builtinTheme(basicThemeType.value + 1L).id!!

    fun findThemeById(themeId: Long): TimetableTheme =
        timetableThemeRepository.findByIdOrNull(themeId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    fun findThemeAvailableToUser(
        userId: Long,
        themeId: Long,
    ): TimetableTheme =
        findThemeById(themeId).also { theme ->
            if (!theme.isBuiltin && theme.userId != userId) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        }

    private fun builtinTheme(id: Long): TimetableTheme =
        timetableThemeRepository.findByIdOrNull(id) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun getOwnedTheme(
        userId: Long,
        themeId: Long,
    ): TimetableTheme = timetableThemeRepository.findByIdAndUserId(themeId, userId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun timetableLecturesOf(timetableId: Long) = timetableLectureRepository.findByTimetableId(timetableId)

    private fun decodePublishedThemeCursor(cursor: String?): PublishedThemeCursor? =
        CursorCodec.decode<PublishedThemeCursor>(cursor)?.also {
            if (it.downloadCount < 0 || it.publishedThemeId <= 0) {
                throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        }

    private fun List<PublishedTheme>.toCursorPage(pageSize: Int): CursorPage<TimetableThemeDisplay> {
        val page =
            toCursorPage(
                pageSize,
                cursorOf = { PublishedThemeCursor(it.downloadCount, it.id!!) },
                transform = { it },
            )
        return CursorPage(
            content = page.content.toMarketDisplays(),
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    private fun List<PublishedTheme>.toMarketDisplays(): List<TimetableThemeDisplay> {
        if (isEmpty()) return emptyList()
        val themes = timetableThemeRepository.findAllById(mapNotNull { it.themeId }).associateBy { it.id!! }
        val nicknameMap = userRepository.findAllById(themes.values.mapNotNull { it.userId }).associate { it.id!! to it.nicknameWithoutTag }
        return map { published ->
            val theme = checkNotNull(themes[published.themeId])
            theme.toDisplay(published = published, authorNickname = nicknameMap[theme.userId])
        }
    }

    private fun TimetableTheme.toDisplay(
        published: PublishedTheme?,
        isDefault: Boolean = false,
        authorNickname: String? = null,
    ) = TimetableThemeDisplay(
        id = id!!,
        name = name,
        colors = colors,
        isCustom = !isBuiltin,
        isBuiltin = isBuiltin,
        builtinType = builtinType,
        status =
            when {
                isBuiltin -> ThemeStatus.BASIC
                originThemeId != null -> ThemeStatus.DOWNLOADED
                published != null -> ThemeStatus.PUBLISHED
                else -> ThemeStatus.PRIVATE
            },
        isDefault = isDefault,
        publishName = published?.publishName,
        authorAnonymous = published?.authorAnonymous,
        downloadCount = published?.downloadCount ?: 0,
        authorNickname = authorNickname,
    )

    private fun validateColorCount(colors: List<ColorSet>) {
        if (colors.size !in 1..MAX_COLOR_COUNT) throw SnuttException(ErrorType.INVALID_THEME_COLOR_COUNT)
    }
}
