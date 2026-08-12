package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "diary_question")
class DiaryQuestion(
    var question: String,
    var shortQuestion: String,
    @JdbcTypeCode(SqlTypes.JSON)
    var answerList: List<String>,
    @JdbcTypeCode(SqlTypes.JSON)
    var shortAnswerList: List<String>,
    @JdbcTypeCode(SqlTypes.JSON)
    var targetDailyClassTypeIdList: List<Long>,
    var active: Boolean = true,
) : ExternalIdEntity()
