package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableRepository : JpaRepository<Timetable, Long> {
    // 같은 시간표로의 동시 강의 추가가 겹침 검증을 우회하지 않도록 행 잠금으로 조회한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Timetable t WHERE t.id = :id AND t.userId = :userId")
    fun findByIdAndUserIdForUpdate(
        id: Long,
        userId: Long,
    ): Timetable?

    fun findByIdAndUserId(
        id: Long,
        userId: Long,
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

    @Modifying
    @Query("UPDATE Timetable t SET t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :timetableId")
    fun touchUpdatedAt(timetableId: Long)

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
