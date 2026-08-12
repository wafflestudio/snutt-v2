package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 시간표 항목: lecture 참조 + 표시 순서/색상만 보유 (PLAN.md §2).
// 사용자 수정분은 timetable_lecture_customization에, lecture_id NULL은 완전 custom 강의
@Entity
@Table(name = "timetable_lecture")
class TimetableLecture(
    var timetableId: Long,
    var lectureId: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var color: ColorSet? = null,
    var colorIndex: Int = 0,
) : ExternalIdEntity()
