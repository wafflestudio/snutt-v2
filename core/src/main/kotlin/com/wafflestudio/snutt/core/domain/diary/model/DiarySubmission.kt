package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

data class QuestionAnswer(
    val questionId: Long,
    val answerIndex: Int,
)

@Entity
@Table(name = "diary_submission")
class DiarySubmission(
    var userId: Long,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var lectureId: Long? = null,
    var courseTitle: String,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var comment: String,
) : BaseEntity()
