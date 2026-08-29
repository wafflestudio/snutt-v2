package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestionTarget
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmissionAnswer
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmissionDailyClassType
import org.springframework.data.jpa.repository.JpaRepository

interface DiaryQuestionTargetRepository : JpaRepository<DiaryQuestionTarget, Long> {
    fun findByDailyClassTypeIdIn(dailyClassTypeIds: Collection<Long>): List<DiaryQuestionTarget>
}

interface DiarySubmissionDailyClassTypeRepository : JpaRepository<DiarySubmissionDailyClassType, Long> {
    fun deleteBySubmissionId(submissionId: Long)
}

interface DiarySubmissionAnswerRepository : JpaRepository<DiarySubmissionAnswer, Long> {
    fun findBySubmissionIdIn(submissionIds: Collection<Long>): List<DiarySubmissionAnswer>

    fun deleteBySubmissionId(submissionId: Long)
}
