package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import org.springframework.data.jpa.repository.JpaRepository

interface DiaryDailyClassTypeRepository : JpaRepository<DiaryDailyClassType, Long> {
    fun findAllByActiveTrueOrderByNameAsc(): List<DiaryDailyClassType>

    fun findAllByNameIn(names: Collection<String>): List<DiaryDailyClassType>

    fun findAllByNameInAndActiveTrue(names: Collection<String>): List<DiaryDailyClassType>

    fun findByName(name: String): DiaryDailyClassType?
}
