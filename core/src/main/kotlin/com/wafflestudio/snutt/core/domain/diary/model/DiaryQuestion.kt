package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
class DiaryQuestion(
    var question: String,
    var shortQuestion: String,
    @JdbcTypeCode(SqlTypes.JSON)
    var answerList: List<String>,
    @JdbcTypeCode(SqlTypes.JSON)
    var shortAnswerList: List<String>,
    var active: Boolean = true,
) : BaseEntity()
