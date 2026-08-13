package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 시간표 항목: lecture 참조 + 표시 색상. lecture_id NULL은 완전 custom 강의로,
// 아래 override 컬럼이 내용을 보유한다. lecture 참조 강의는 non-NULL 컬럼만 lecture 위에 덮어쓴다.
@Entity
@Table(name = "timetable_lecture")
class TimetableLecture(
    var timetableId: Long,
    var lectureId: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var color: ColorSet? = null,
    var colorIndex: Int = 0,
    @Column(name = "course_title")
    var courseTitle: String? = null,
    var instructor: String? = null,
    var credit: Int? = null,
    @Column(columnDefinition = "TEXT")
    var remark: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "class_place_and_time")
    var classPlaceAndTime: List<ClassPlaceAndTime>? = null,
    @Column(name = "academic_year")
    var academicYear: String? = null,
    var category: String? = null,
    var classification: String? = null,
    @Column(name = "category_pre2025")
    var categoryPre2025: String? = null,
) : ExternalIdEntity()
