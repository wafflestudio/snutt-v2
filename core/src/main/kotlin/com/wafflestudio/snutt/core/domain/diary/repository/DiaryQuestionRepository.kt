package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import org.springframework.data.jpa.repository.JpaRepository

interface DiaryQuestionRepository : JpaRepository<DiaryQuestion, Long> {
    fun findAllByActiveTrue(): List<DiaryQuestion>
}
