package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "diary_question_target")
class DiaryQuestionTarget(
    var questionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

@Entity
@Table(name = "diary_submission_daily_class_type")
class DiarySubmissionDailyClassType(
    var submissionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

@Entity
@Table(name = "diary_submission_answer")
class DiarySubmissionAnswer(
    var submissionId: Long,
    var questionId: Long,
    var answerIndex: Int,
) : BaseEntity()
