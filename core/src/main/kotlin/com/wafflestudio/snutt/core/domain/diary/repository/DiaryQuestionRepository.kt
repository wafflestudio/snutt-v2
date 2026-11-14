package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DiaryQuestionRepository : JpaRepository<DiaryQuestion, Long> {
    fun findAllByActiveTrue(): List<DiaryQuestion>

    @Query(
        "SELECT DISTINCT q FROM DiaryQuestion q " +
            "JOIN DiaryQuestionTarget t ON t.questionId = q.id " +
            "JOIN DiaryDailyClassType d ON d.id = t.dailyClassTypeId " +
            "WHERE q.active = true AND d.active = true AND d.name IN :dailyClassTypes",
    )
    fun findActiveByDailyClassTypes(dailyClassTypes: Collection<String>): List<DiaryQuestion>
}
