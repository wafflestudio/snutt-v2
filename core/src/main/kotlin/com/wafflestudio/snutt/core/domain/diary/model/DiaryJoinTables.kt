package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 질문이 노출되는 수업 유형(daily class type: 오늘 수업이 어떻게 진행되었는지. 수업/시험/발표/휴강 등) 매핑.
 * JSON targetDailyClassTypeIdList 대신 FK 무결성과 조인 가능한 정규화 테이블로 대체한다.
 */
@Entity
@Table(name = "diary_question_target")
class DiaryQuestionTarget(
    var questionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

/**
 * 제출에 표시된 수업 유형. JSON dailyClassTypeIdList 대신 정규화 테이블로 대체한다.
 */
@Entity
@Table(name = "diary_submission_daily_class_type")
class DiarySubmissionDailyClassType(
    var submissionId: Long,
    var dailyClassTypeId: Long,
) : BaseEntity()

/**
 * 제출별 답변. JSON questionAnswerList 대신 정규화 테이블로 대체한다.
 */
@Entity
@Table(name = "diary_submission_answer")
class DiarySubmissionAnswer(
    var submissionId: Long,
    var questionId: Long,
    var answerIndex: Int,
) : BaseEntity()
