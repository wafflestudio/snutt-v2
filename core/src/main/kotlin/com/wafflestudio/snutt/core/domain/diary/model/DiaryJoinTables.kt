package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class DiaryQuestionTarget(
    var questionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

@Entity
class DiarySubmissionDailyClassType(
    var submissionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

@Entity
class DiarySubmissionAnswer(
    var submissionId: Long,
    var questionId: Long,
    var answerIndex: Int,
) : BaseEntity()
