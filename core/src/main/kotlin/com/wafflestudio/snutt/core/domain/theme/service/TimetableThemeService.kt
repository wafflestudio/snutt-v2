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
        private val copyNumberRegex = """\s\(\d+\)$""".toRegex()
    }

    // v1과 동일: 기본 테마(내장 6종, isDefault 포함) + 보관함 테마 목록
    fun getThemes(userId: Long): List<TimetableThemeDisplay> {
        val defaultThemeName = getDefaultTheme(userId).name
        val basicThemes = BasicThemeType.entries.map { it.toBasicDisplay(isDefault = it.displayName == defaultThemeName) }
        return basicThemes + getLibraryThemeDisplays(userId)
    }

    private fun getLibraryThemeDisplays(userId: Long): List<TimetableThemeDisplay> {
        val defaultThemeId = getDefaultTheme(userId).id
        val themes = timetableThemeRepository.findByUserIdOrderByUpdatedAtDesc(userId)
        val publishedByThemeId = publishedThemeRepository.findByThemeIdIn(themes.mapNotNull { it.id }).associateBy { it.themeId }
        return themes.map { it.toDisplay(published = publishedByThemeId[it.id], isDefault = it.externalId == defaultThemeId) }
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
            .save(TimetableTheme(userId = userId, name = name, colorList = colors))
            .toDisplay(published = null)
    }

    @Transactional
    fun modifyTheme(
        userId: Long,
        themeExternalId: String,
        name: String?,
        colors: List<ColorSet>?,
    ): TimetableThemeDisplay {
        val theme = getOwnedTheme(userId, themeExternalId)
        name?.let { theme.name = it }
        colors?.let { newColors ->
            validateColorCount(newColors)

            // 색상 변경은 해당 테마를 쓰는 시간표의 강의 색상에도 반영한다 (v1 동일)
            val colorMap = theme.colorList.mapIndexed { i, color -> color to newColors.getOrNull(i) }.toMap()
            timetableRepository.findByUserIdAndThemeId(userId, theme.id!!).forEach { timetable ->
                val lectures = timetableLecturesOf(timetable.id!!)
                lectures.filter { it.color in theme.colorList }.forEach { lecture ->
                    colorMap[lecture.color]?.let { lecture.color = it }
                }
                timetableLectureRepository.saveAll(lectures)
            }
            theme.colorList = newColors
        }
        return theme.toDisplay(published = publishedThemeRepository.findByThemeId(theme.id!!))
    }

    @Transactional
    fun publishTheme(
        userId: Long,
        themeExternalId: String,
        publishName: String,
        authorAnonymous: Boolean,
    ) {
        val theme = getOwnedTheme(userId, themeExternalId)
        // 공개 정보는 테마 행과 분리되어 있으므로 재공개는 기존 행을 갱신한다
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
        themeExternalId: String,
        name: String,
    ): TimetableThemeDisplay {
        val theme = timetableThemeRepository.findByExternalId(themeExternalId) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        val published = publishedThemeRepository.findByThemeId(theme.id!!) ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        if (timetableThemeRepository.existsByOriginThemeIdAndUserId(theme.id!!, downloadedUserId)) {
            throw SnuttException(ErrorType.ALREADY_DOWNLOADED_THEME)
        }
        val downloaded =
            timetableThemeRepository.save(
                TimetableTheme(
                    userId = downloadedUserId,
                    name = name,
                    colorList = theme.colorList,
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
        themeExternalId: String,
    ) {
        val theme = getOwnedTheme(userId, themeExternalId)
        if (publishedThemeRepository.existsByThemeId(theme.id!!)) throw SnuttException(ErrorType.PUBLISHED_THEME_DELETE_ERROR)

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
        val theme = getOwnedTheme(userId, themeExternalId)
        val published = publishedThemeRepository.findByThemeId(theme.id!!) ?: throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        publishedThemeRepository.delete(published)
    }

    @Transactional
    fun copyTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay {
        val theme = getOwnedTheme(userId, themeExternalId)
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
                    colorList = theme.colorList,
                ),
            ).toDisplay(published = null)
    }

    // 기본 테마는 "가장 최근에 수정한 보관함 테마"이므로, 지정은 updatedAt 갱신으로 표현한다 (v1 동일)
    @Transactional
    fun setDefault(
        userId: Long,
        themeExternalId: String,
    ): TimetableThemeDisplay {
        val theme =
            timetableThemeRepository.findByExternalIdAndUserId(themeExternalId, userId)
                ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
        timetableThemeRepository.touchUpdatedAt(requireNotNull(theme.id))
        return theme.toDisplay(published = publishedThemeRepository.findByThemeId(theme.id!!), isDefault = true)
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
        return BasicThemeType.SNUTT.toBasicDisplay(isDefault = true)
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
        return BasicThemeType.SNUTT.toBasicDisplay(isDefault = true)
    }

    // v1 동일: 기본 테마 = 가장 최근 수정한 보관함 테마, 없으면 SNUTT
    fun getDefaultTheme(userId: Long): TimetableThemeDisplay {
        val theme = timetableThemeRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
        return theme?.toDisplay(published = theme.id?.let { publishedThemeRepository.findByThemeId(it) }, isDefault = true)
            ?: BasicThemeType.SNUTT.toBasicDisplay(isDefault = true)
    }

    fun getTheme(
        userId: Long,
        themeExternalId: String?,
        basicThemeType: BasicThemeType?,
    ): TimetableThemeDisplay {
        require((themeExternalId == null) xor (basicThemeType == null))
        return themeExternalId?.let {
            val theme =
                timetableThemeRepository.findByExternalIdAndUserId(it, userId)
                    ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)
            theme.toDisplay(published = theme.id?.let { id -> publishedThemeRepository.findByThemeId(id) })
        } ?: checkNotNull(basicThemeType).toBasicDisplay(isDefault = false)
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
            val colorToCount = theme.colorList.associateWith { color -> usedColors.count { it == color } }
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

    private fun getOwnedTheme(
        userId: Long,
        themeExternalId: String,
    ): TimetableTheme =
        timetableThemeRepository.findByExternalIdAndUserId(themeExternalId, userId)
            ?: throw SnuttException(ErrorType.THEME_NOT_FOUND)

    private fun timetableLecturesOf(timetableId: Long) = timetableLectureRepository.findByTimetableId(timetableId)

    // 마켓 응답: 공개 행 + 원본 테마 + 저자 닉네임을 합쳐 표시 모델로 만든다
    private fun List<PublishedTheme>.toMarketDisplays(): List<TimetableThemeDisplay> {
        if (isEmpty()) return emptyList()
        val themes = timetableThemeRepository.findAllById(mapNotNull { it.themeId }).associateBy { it.id!! }
        val nicknameMap = userRepository.findAllById(themes.values.map { it.userId }).associate { it.id!! to it.nicknameWithoutTag }
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
        id = externalId,
        name = name,
        colorList = colorList,
        isCustom = true,
        status =
            when {
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

    private fun BasicThemeType.toBasicDisplay(isDefault: Boolean) =
        TimetableThemeDisplay(
            id = null,
            name = displayName,
            colorList = null,
            isCustom = false,
            status = ThemeStatus.BASIC,
            isDefault = isDefault,
            publishName = null,
            authorAnonymous = null,
            downloadCount = 0,
            authorNickname = null,
        )

    private fun validateColorCount(colors: List<ColorSet>) {
        if (colors.size !in 1..MAX_COLOR_COUNT) throw SnuttException(ErrorType.INVALID_THEME_COLOR_COUNT)
    }
}
