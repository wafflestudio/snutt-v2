package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "evaluation")
class Evaluation(
    var courseId: Long,
    var userId: Long? = null,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var content: String,
    var gradeSatisfaction: Double? = null,
    var teachingSkill: Double? = null,
    var gains: Double? = null,
    var lifeBalance: Double? = null,
    var rating: Double,
    var likeCount: Long = 0,
    var isHidden: Boolean = false,
    var isReported: Boolean = false,
    var fromSnuev: Boolean = false,
) : BaseEntity()
