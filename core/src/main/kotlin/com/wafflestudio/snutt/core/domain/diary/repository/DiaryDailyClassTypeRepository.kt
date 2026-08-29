package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import org.springframework.data.jpa.repository.JpaRepository

interface DiaryDailyClassTypeRepository : JpaRepository<DiaryDailyClassType, Long> {
    fun findAllByActiveTrue(): List<DiaryDailyClassType>

    fun findAllByActiveTrueOrderByNameAsc(): List<DiaryDailyClassType>

    fun findAllByNameIn(names: Collection<String>): List<DiaryDailyClassType>

    override fun findAll(): List<DiaryDailyClassType>
}
