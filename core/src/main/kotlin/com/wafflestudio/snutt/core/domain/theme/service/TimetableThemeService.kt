package com.wafflestudio.snutt.core.domain.theme.service

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimetableThemeService(
    private val timetableThemeRepository: TimetableThemeRepository,
    private val publishedThemeRepository: PublishedThemeRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val userRepository: UserRepository,
    private val friendRepository: FriendRepository,
) {
    companion object {
        private const val MAX_COLOR_COUNT = 9
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

    fun getBestThemes(page: Int): List<TimetableThemeDisplay> =
        publishedThemeRepository
            .findAllByOrderByDownloadCountDesc(PageRequest.of(page, 20))
            .toMarketDisplays()

    fun getFriendsThemes(
        userId: Long,
        page: Int,
    ): List<TimetableThemeDisplay> {
        val friendUserIds = friendRepository.findActiveByUserId(userId).map { it.getPartnerUserId(userId) }
        if (friendUserIds.isEmpty()) return emptyList()
        return publishedThemeRepository.findFriendsPublished(friendUserIds, PageRequest.of(page, 10)).toMarketDisplays()
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

            val colorMap = theme.colors.mapIndexed { i, color -> color to newColors.getOrNull(i) }.toMap()
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
        published.downloadCount += 1
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
        return setDefaultInternal(theme)
    }

    private fun setDefaultInternal(theme: TimetableTheme): TimetableThemeDisplay {
        timetableThemeRepository.touchUpdatedAt(requireNotNull(theme.id))
        return theme.toDisplay(published = publishedThemeRepository.findByThemeId(theme.id!!), isDefault = true)
    }

    fun unsetDefault(
        userId: Long,
        themeId: Long,
    ): TimetableThemeDisplay {
        val current = getDefaultTheme(userId)
        if (current.isBuiltin || current.id != themeId) throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        return builtinTheme(SNUTT_BUILTIN_THEME_ID).toDisplay(published = null, isDefault = true)
    }

    fun getDefaultTheme(userId: Long): TimetableThemeDisplay {
        val theme = timetableThemeRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
        return theme?.toDisplay(
            published = theme.id?.let { publishedThemeRepository.findByThemeId(it) },
            isDefault = true,
        ) ?: builtinTheme(SNUTT_BUILTIN_THEME_ID).toDisplay(published = null, isDefault = true)
    }

    fun getDefaultThemeId(userId: Long): Long =
        timetableThemeRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)?.id ?: SNUTT_BUILTIN_THEME_ID

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

    fun themeColors(themeId: Long): List<ColorSet>? = timetableThemeRepository.findByIdOrNull(themeId)?.colors

    fun themeColorCount(themeId: Long): Int? = themeColors(themeId)?.size

    fun builtinThemeId(basicThemeType: BasicThemeType): Long = builtinTheme(basicThemeType.value + 1L).id!!

    fun findThemeById(themeId: Long): TimetableTheme =
        timetableThemeRepository.findByIdOrNull(themeId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun builtinTheme(id: Long): TimetableTheme =
        timetableThemeRepository.findByIdOrNull(id) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun getOwnedTheme(
        userId: Long,
        themeId: Long,
    ): TimetableTheme = timetableThemeRepository.findByIdAndUserId(themeId, userId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun timetableLecturesOf(timetableId: Long) = timetableLectureRepository.findByTimetableId(timetableId)

    private fun List<PublishedTheme>.toMarketDisplays(): List<TimetableThemeDisplay> {
        if (isEmpty()) return emptyList()
        val themes = timetableThemeRepository.findAllById(mapNotNull { it.themeId }).associateBy { it.id!! }
        val nicknameMap = userRepository.findAllById(themes.values.mapNotNull { it.userId }).associate { it.id!! to it.nicknameWithoutTag }
        return mapNotNull { published ->
            val theme = themes[published.themeId] ?: return@mapNotNull null
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

    fun getTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay = getTheme(userId, themeExternalId.toLong())

    @Transactional
    fun modifyTheme(
        userId: Long,
        themeExternalId: String,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay = modifyTheme(userId, themeExternalId.toLong(), name, colors)

    @Transactional
    fun publishTheme(
        userId: Long,
        themeExternalId: String,
        publishName: String,
        authorAnonymous: Boolean,
    ) {
        publishTheme(userId, themeExternalId.toLong(), publishName, authorAnonymous)
    }

    @Transactional
    fun downloadTheme(
        downloadedUserId: Long,
        themeExternalId: String,
        name: String,
    ): TimetableThemeDisplay = downloadTheme(downloadedUserId, themeExternalId.toLong(), name)

    @Transactional
    fun deleteTheme(
        userId: Long,
        themeExternalId: String,
    ) {
        deleteTheme(userId, themeExternalId.toLong())
    }

    @Transactional
    fun deletePublishedTheme(
        userId: Long,
        themeExternalId: String,
    ) {
        deletePublishedTheme(userId, themeExternalId.toLong())
    }

    @Transactional
    fun copyTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay = copyTheme(userId, themeExternalId.toLong())

    @Transactional
    fun setDefault(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay = setDefault(userId, themeExternalId.toLong())

    fun unsetDefault(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay = unsetDefault(userId, themeExternalId.toLong())

    fun findTheme(themeExternalId: String): TimetableTheme = findThemeById(themeExternalId.toLong())

    fun findThemeId(themeExternalId: String): Long = themeExternalId.toLong()
}
