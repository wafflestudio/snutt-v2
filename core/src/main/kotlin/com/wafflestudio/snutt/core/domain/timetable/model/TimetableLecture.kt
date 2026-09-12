package com.wafflestudio.snutt.core.domain.timetable.model

import com.fasterxml.jackson.annotation.JsonValue
import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class LectureOverrideField(
    @get:JsonValue val fieldName: String,
) {
    COURSE_TITLE("courseTitle"),
    INSTRUCTOR("instructor"),
    CREDIT("credit"),
    REMARK("remark"),
    CLASS_PLACE_AND_TIMES("classPlaceAndTimes"),
    ACADEMIC_YEAR("academicYear"),
    CATEGORY("category"),
    CLASSIFICATION("classification"),
    CATEGORY_PRE2025("categoryPre2025"),
}

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
) {
    fun without(fields: Set<LectureOverrideField>): LectureOverrides =
        copy(
            courseTitle = courseTitle.takeUnless { LectureOverrideField.COURSE_TITLE in fields },
            instructor = instructor.takeUnless { LectureOverrideField.INSTRUCTOR in fields },
            credit = credit.takeUnless { LectureOverrideField.CREDIT in fields },
            remark = remark.takeUnless { LectureOverrideField.REMARK in fields },
            classPlaceAndTimes = classPlaceAndTimes.takeUnless { LectureOverrideField.CLASS_PLACE_AND_TIMES in fields },
            academicYear = academicYear.takeUnless { LectureOverrideField.ACADEMIC_YEAR in fields },
            category = category.takeUnless { LectureOverrideField.CATEGORY in fields },
            classification = classification.takeUnless { LectureOverrideField.CLASSIFICATION in fields },
            categoryPre2025 = categoryPre2025.takeUnless { LectureOverrideField.CATEGORY_PRE2025 in fields },
        )
}

@Entity
@Table(name = "timetable_lecture")
class TimetableLecture(
    var timetableId: Long,
    var lectureId: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var overrides: LectureOverrides? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var customColor: ColorSet? = null,
    var paletteIndex: Int = 0,
) : BaseEntity() {
    fun copyFor(targetTimetableId: Long) =
        TimetableLecture(
            timetableId = targetTimetableId,
            lectureId = lectureId,
            overrides = overrides,
            customColor = customColor,
            paletteIndex = paletteIndex,
        )

    fun clearOverrides() {
        overrides = null
    }

    fun updateOverrides(transform: (LectureOverrides) -> LectureOverrides) {
        overrides = transform(overrides ?: LectureOverrides()).takeUnless { it == LectureOverrides() }
    }
}
