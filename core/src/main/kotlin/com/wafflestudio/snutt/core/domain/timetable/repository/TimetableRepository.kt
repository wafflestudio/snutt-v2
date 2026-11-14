package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

data class PrimaryTimetableTarget(
    val timetableId: Long,
    val userId: Long,
)

interface TimetableRepository : JpaRepository<Timetable, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByIdAndUserId(
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Timetable t SET t.updatedAt = :updatedAt WHERE t.id IN :timetableIds")
    fun touchUpdatedAt(
        timetableIds: Collection<Long>,
        updatedAt: Instant,
    )

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByUserIdAndThemeId(
        userId: Long,
        themeId: Long,
    ): List<Timetable>

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.PrimaryTimetableTarget(t.id, t.userId) " +
            "FROM Timetable t WHERE t.year = :year AND t.semester = :semester AND t.isPrimary = true",
    )
    fun findPrimaryTargetsByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<PrimaryTimetableTarget>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            "UPDATE timetable t " +
                "JOIN (" +
                "    SELECT id FROM (" +
                "        SELECT id, " +
                "               ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY updated_at DESC, id DESC) AS rn " +
                "        FROM timetable t1 " +
                "        WHERE t1.year = :year AND t1.semester = :semester " +
                "          AND NOT EXISTS (" +
                "              SELECT 1 FROM timetable t2 " +
                "              WHERE t2.user_id = t1.user_id AND t2.year = :year AND t2.semester = :semester AND t2.is_primary = true" +
                "          )" +
                "    ) ranked WHERE rn = 1" +
                ") target ON target.id = t.id " +
                "SET t.is_primary = true",
        nativeQuery = true,
    )
    fun autoAssignPrimaryByYearAndSemester(
        year: Int,
        semester: Int,
    ): Int

    fun countByUserId(userId: Long): Long
}
