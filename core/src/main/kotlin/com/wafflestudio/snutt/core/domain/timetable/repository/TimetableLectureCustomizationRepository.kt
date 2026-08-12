package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureCustomization
import org.springframework.data.jpa.repository.JpaRepository

interface TimetableLectureCustomizationRepository : JpaRepository<TimetableLectureCustomization, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureCustomization?

    fun findByTimetableLectureIdIn(timetableLectureIds: Collection<Long>): List<TimetableLectureCustomization>

    fun deleteByTimetableLectureId(timetableLectureId: Long)
}
