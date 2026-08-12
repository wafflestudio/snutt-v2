package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 사용자 수정분 override. 행 없음 = 원본 그대로, non-NULL 필드만 lecture 위에 덮어쓴다
@Entity
@Table(name = "timetable_lecture_customization")
class TimetableLectureCustomization(
    var timetableLectureId: Long,
    var courseTitle: String? = null,
    var instructor: String? = null,
    var credit: Int? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var remark: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var classPlaceAndTime: List<ClassPlaceAndTime>? = null,
) : BaseEntity()
