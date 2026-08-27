package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 강좌 원본(Lecture)을 덮어쓸 때만 채워지는 오버라이드 값들.
 * 미지정 필드는 null. 커스텀 강좌(lectureId=null)는 courseTitle을 필수로 가진다.
 */
data class LectureOverrides(
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    val remark: String? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTime>? = null,
    val academicYear: String? = null,
    val category: String? = null,
    val classification: String? = null,
    val categoryPre2025: String? = null,
)

@Entity
@Table(name = "timetable_lecture")
class TimetableLecture(
    var timetableId: Long,
    var lectureId: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var overrides: LectureOverrides? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var color: ColorSet? = null,
    var colorIndex: Int = 0,
) : BaseEntity() {
    fun copyFor(targetTimetableId: Long) =
        TimetableLecture(
            timetableId = targetTimetableId,
            lectureId = lectureId,
            overrides = overrides,
            color = color,
            colorIndex = colorIndex,
        )

    fun clearOverrides() {
        overrides = null
    }

    fun updateOverrides(transform: (LectureOverrides) -> LectureOverrides) {
        // 모든 필드가 null이면 빈 JSON({})을 쓰지 않고 null로 되돌린다
        overrides = transform(overrides ?: LectureOverrides()).takeUnless { it == LectureOverrides() }
    }
}
