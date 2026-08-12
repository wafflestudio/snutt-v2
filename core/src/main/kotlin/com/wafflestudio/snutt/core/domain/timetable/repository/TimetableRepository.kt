package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import org.springframework.data.jpa.repository.JpaRepository

interface TimetableRepository : JpaRepository<Timetable, Long> {
    fun findByExternalId(externalId: String): Timetable?

    fun findByUserIdAndExternalId(
        userId: Long,
        externalId: String,
    ): Timetable?

    fun findByUserId(userId: Long): List<Timetable>

    fun findByUserIdAndYearAndSemester(
        userId: Long,
        year: Int,
        semester: Semester,
    ): List<Timetable>

    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<Timetable>

    fun findByYearAndSemesterAndIsPrimaryTrue(
        year: Int,
        semester: Semester,
    ): List<Timetable>

    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): Timetable?

    fun findByUserIdAndYearAndSemesterAndTitle(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    ): Timetable?

    fun findByUserIdAndYearAndSemesterAndIsPrimaryTrue(
        userId: Long,
        year: Int,
        semester: Semester,
    ): Timetable?

    fun findByUserIdAndIsPrimaryTrue(userId: Long): List<Timetable>

    fun findByUserIdAndThemeId(
        userId: Long,
        themeId: Long,
    ): List<Timetable>

    fun countByUserId(userId: Long): Long
}
