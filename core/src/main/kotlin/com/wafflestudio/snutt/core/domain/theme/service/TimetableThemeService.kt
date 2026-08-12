package com.wafflestudio.snutt.core.domain.theme.service

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimetableThemeService(
    private val timetableThemeRepository: TimetableThemeRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val userRepository: UserRepository,
    private val friendRepository: FriendRepository,
) {
    companion object {
        private const val MAX_COLOR_COUNT = 9
        private val copyNumberRegex = """\s\(\d+\)$""".toRegex()
    }

    // v1과 동일: 기본 테마(내장 6종, isDefault 포함) + 커스텀 테마 목록
    fun getThemes(userId: Long): List<TimetableThemeDisplay> {
        val defaultThemeName = getDefaultTheme(userId).name
        val basicThemes =
            BasicThemeType.entries.map {
                TimetableThemeDisplay(
                    id = null,
                    name = it.displayName,
                    colorList = null,
                    isCustom = false,
                    status = ThemeStatus.BASIC,
                    isDefault = it.displayName == defaultThemeName,
                    publishName = null,
                    authorAnonymous = null,
                    downloadCount = 0,
                    authorNickname = null,
                )
            }
        return basicThemes + getCustomThemeDisplays(userId)
    }

    private fun getCustomThemeDisplays(userId: Long): List<TimetableThemeDisplay> {
        val defaultThemeId = getDefaultTheme(userId).id
        return timetableThemeRepository
            .findByUserIdAndIsCustomTrueOrderByUpdatedAtDesc(userId)
            .map { it.toDisplay(isDefault = it.externalId == defaultThemeId) }
    }

    fun getBestThemes(page: Int): List<TimetableThemeDisplay> {
        val themes = timetableThemeRepository.findByStatusOrderByDownloadCountDesc(ThemeStatus.PUBLISHED, PageRequest.of(page, 20))
        return themes.toDisplays()
    }

    fun getFriendsThemes(
        userId: Long,
        page: Int,
    ): List<TimetableThemeDisplay> {
        val friendUserIds = friendRepository.findActiveByUserId(userId).map { it.getPartnerUserId(userId) }
        if (friendUserIds.isEmpty()) return emptyList()
        return timetableThemeRepository.findFriendsThemes(friendUserIds, PageRequest.of(page, 10)).toDisplays()
    }

    // 기본 테마는 "가장 최근에 수정한 커스텀 테마"이므로, 지정은 updatedAt 갱신으로 표현한다 (v1 동일)
    @Transactional
    fun setDefault(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay {
        val theme =
            timetableThemeRepository.findByExternalIdAndUserId(themeExternalId, userId)
                ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        timetableThemeRepository.touchUpdatedAt(requireNotNull(theme.id))
        return theme.toDisplay(isDefault = true)
    }

    // 내장 테마는 사용자가 직접 기본으로 지정할 수 없다 (v1 3.5.0 대응)
    fun setBasicThemeDefault(userId: Long): TimetableThemeDisplay = getDefaultTheme(userId)

    @Transactional
    fun unsetDefault(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay {
        val current = getDefaultTheme(userId)
        if (!current.isCustom || current.id != themeExternalId) throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        return basicDefaultDisplay()
    }

    @Transactional
    fun unsetBasicThemeDefault(
        userId: Long,
        basicThemeType: BasicThemeType,
    ): TimetableThemeDisplay {
        val current = getDefaultTheme(userId)
        if (current.isCustom || current.name != basicThemeType.displayName) {
            throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        }
        return basicDefaultDisplay()
    }

    private fun basicDefaultDisplay() =
        TimetableThemeDisplay(
            id = null,
            name = BasicThemeType.SNUTT.displayName,
            colorList = null,
            isCustom = false,
            status = ThemeStatus.BASIC,
            isDefault = true,
            publishName = null,
            authorAnonymous = null,
            downloadCount = 0,
            authorNickname = null,
        )

    fun searchThemes(keyword: String): List<TimetableThemeDisplay> =
        timetableThemeRepository.findByStatusAndPublishNameContainingIgnoreCase(ThemeStatus.PUBLISHED, keyword).toDisplays()

    @Transactional
    fun addTheme(
        userId: Long,
        name: String,
        colors: List<ColorSet>,
    ): TimetableThemeDisplay {
        if (colors.size !in 1..MAX_COLOR_COUNT) throw SnuttException(ErrorType.INVALID_THEME_COLOR_COUNT)
        val theme =
            timetableThemeRepository.save(
                TimetableTheme(
                    userId = userId,
                    name = name,
                    colorList = colors,
                    isCustom = true,
                    status = ThemeStatus.PRIVATE,
                ),
            )
        return theme.toDisplay()
    }

    @Transactional
    fun modifyTheme(
        userId: Long,
        themeExternalId: String,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay {
        val theme = getCustomTheme(userId, themeExternalId)
        name?.let { theme.name = it }
        colors?.let { newColors ->
            if (newColors.size !in 1..MAX_COLOR_COUNT) throw SnuttException(ErrorType.INVALID_THEME_COLOR_COUNT)

            // 색상 변경은 해당 테마를 쓰는 시간표의 강의 색상에도 반영한다 (v1 동일)
            val colorMap =
                theme.colorList
                    .orEmpty()
                    .mapIndexed { i, color -> color to newColors.getOrNull(i) }
                    .toMap()
            timetableRepository.findByUserIdAndThemeId(userId, theme.id!!).forEach { timetable ->
                val lectures = timetableLecturesOf(timetable.id!!)
                lectures.filter { it.color in theme.colorList.orEmpty() }.forEach { lecture ->
                    colorMap[lecture.color]?.let { lecture.color = it }
                }
                timetableLectureRepository.saveAll(lectures)
            }
            theme.colorList = newColors
        }
        return theme.toDisplay()
    }

    @Transactional
    fun publishTheme(
        userId: Long,
        themeExternalId: String,
        publishName: String,
        authorAnonymous: Boolean,
    ) {
        val theme = getCustomTheme(userId, themeExternalId)
        theme.status = ThemeStatus.PUBLISHED
        theme.publishName = publishName
        theme.authorAnonymous = authorAnonymous
        theme.downloadCount = 0
    }

    @Transactional
    fun downloadTheme(
        downloadedUserId: Long,
        themeExternalId: String,
        name: String,
    ): TimetableThemeDisplay {
        val theme = timetableThemeRepository.findByExternalId(themeExternalId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        if (theme.status != ThemeStatus.PUBLISHED) throw SnuttException(ErrorType.THEME_NOT_FOUND)
        val originThemeId = theme.id!!
        if (timetableThemeRepository.existsByOriginThemeIdAndUserId(originThemeId, downloadedUserId)) {
            throw SnuttException(ErrorType.ALREADY_DOWNLOADED_THEME)
        }
        val downloaded =
            timetableThemeRepository.save(
                TimetableTheme(
                    userId = downloadedUserId,
                    name = name,
                    colorList = theme.colorList,
                    isCustom = true,
                    status = ThemeStatus.DOWNLOADED,
                    originThemeId = originThemeId,
                    originAuthorId = theme.userId,
                ),
            )
        theme.downloadCount += 1
        return downloaded.toDisplay()
    }

    @Transactional
    fun deleteTheme(
        userId: Long,
        themeExternalId: String,
    ) {
        val theme = getCustomTheme(userId, themeExternalId)
        if (theme.status == ThemeStatus.PUBLISHED) throw SnuttException(ErrorType.PUBLISHED_THEME_DELETE_ERROR)

        // 해당 테마를 쓰는 시간표는 내장 SNUTT 테마로 되돌린다 (v1 동일)
        timetableRepository.findByUserIdAndThemeId(userId, theme.id!!).forEach { timetable ->
            timetable.theme = BasicThemeType.SNUTT
            timetable.themeId = null
        }
        timetableThemeRepository.delete(theme)
    }

    @Transactional
    fun deletePublishedTheme(
        userId: Long,
        themeExternalId: String,
    ) {
        val theme = getCustomTheme(userId, themeExternalId)
        if (theme.status != ThemeStatus.PUBLISHED) throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        theme.status = ThemeStatus.PRIVATE
        theme.publishName = null
        theme.authorAnonymous = null
    }

    @Transactional
    fun copyTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay {
        val theme = getCustomTheme(userId, themeExternalId)
        val baseName = theme.name.replace(copyNumberRegex, "")
        val lastCopiedNumber =
            timetableThemeRepository
                .findByUserIdAndIsCustomTrueOrderByUpdatedAtDesc(userId)
                .mapNotNull {
                    it.name
                        .replace(baseName, "")
                        .filter(Char::isDigit)
                        .toIntOrNull()
                }.maxOrNull() ?: 0
        val copied =
            timetableThemeRepository.save(
                TimetableTheme(
                    userId = userId,
                    name = "$baseName (${lastCopiedNumber + 1})",
                    colorList = theme.colorList,
                    isCustom = true,
                    status = ThemeStatus.PRIVATE,
                ),
            )
        return copied.toDisplay()
    }

    // v1 동일: default 커스텀 테마 = 가장 최근 수정한 커스텀 테마, 없으면 SNUTT
    fun getDefaultTheme(userId: Long): TimetableThemeDisplay {
        val custom = timetableThemeRepository.findFirstByUserIdAndIsCustomTrueOrderByUpdatedAtDesc(userId)
        return custom?.toDisplay(isDefault = true)
            ?: TimetableThemeDisplay(
                id = null,
                name = BasicThemeType.SNUTT.displayName,
                colorList = null,
                isCustom = false,
                status = ThemeStatus.BASIC,
                isDefault = true,
                publishName = null,
                authorAnonymous = null,
                downloadCount = 0,
                authorNickname = null,
            )
    }

    fun getTheme(
        userId: Long,
        themeExternalId: String?,
        basicThemeType: BasicThemeType?,
    ): TimetableThemeDisplay {
        require((themeExternalId == null) xor (basicThemeType == null))
        return themeExternalId?.let {
            (timetableThemeRepository.findByExternalIdAndUserId(it, userId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)).toDisplay()
        } ?: TimetableThemeDisplay(
            id = null,
            name = checkNotNull(basicThemeType).displayName,
            colorList = null,
            isCustom = false,
            status = ThemeStatus.BASIC,
            isDefault = false,
            publishName = null,
            authorAnonymous = null,
            downloadCount = 0,
            authorNickname = null,
        )
    }

    // 시간표에 강의를 추가할 때 부여할 (colorIndex, color) (v1 getNewColorIndexAndColor 이식)
    fun getNewColorIndexAndColor(
        themeId: Long?,
        usedColors: List<ColorSet?>,
        usedColorIndexes: List<Int>,
    ): Pair<Int, ColorSet?> =
        if (themeId == null) {
            val indexToCount = (1..BasicThemeType.COLOR_COUNT).associateWith { index -> usedColorIndexes.count { it == index } }
            val minCount = indexToCount.minOf { it.value }
            indexToCount.entries
                .filter { (_, count) -> count == minCount }
                .map { it.key }
                .random() to null
        } else {
            val theme = timetableThemeRepository.findById(themeId).orElse(null) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
            val colorToCount = theme.colorList.orEmpty().associateWith { color -> usedColors.count { it == color } }
            val minCount = colorToCount.minOf { it.value }
            0 to
                colorToCount.entries
                    .filter { (_, count) -> count == minCount }
                    .map { it.key }
                    .random()
        }

    // v2 테마 공개 id(externalId) ↔ 테이블 FK용 숫자 id 변환
    fun findThemeId(themeExternalId: String): Long? = timetableThemeRepository.findByExternalId(themeExternalId)?.id

    fun findThemeIdOwnedBy(
        userId: Long,
        themeExternalId: String,
    ): Long? =
        timetableThemeRepository.findByExternalIdAndUserId(themeExternalId, userId)?.id
            ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    fun themeColors(themeId: Long): List<ColorSet>? = timetableThemeRepository.findById(themeId).orElse(null)?.colorList

    fun themeColorCount(themeId: Long): Int? = themeColors(themeId)?.size

    fun findThemeExternalId(themeId: Long): String? = timetableThemeRepository.findById(themeId).orElse(null)?.externalId

    private fun getCustomTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableTheme {
        val theme =
            timetableThemeRepository.findByExternalIdAndUserId(themeExternalId, userId)
                ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        if (!theme.isCustom) throw SnuttException(ErrorType.INVALID_THEME_TYPE)
        return theme
    }

    private fun timetableLecturesOf(timetableId: Long) = timetableLectureRepository.findByTimetableId(timetableId)

    private fun List<TimetableTheme>.toDisplays(): List<TimetableThemeDisplay> {
        val nicknameMap =
            userRepository.findAllById(mapNotNull { it.userId }).associate { it.id!! to it.nicknameWithoutTag }
        return map { it.toDisplay(authorNickname = nicknameMap[it.userId]) }
    }

    private fun TimetableTheme.toDisplay(
        isDefault: Boolean = false,
        authorNickname: String? = null,
    ) = TimetableThemeDisplay(
        id = externalId,
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
}
