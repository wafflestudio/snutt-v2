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

    /** 여러 시간표를 id 순으로 잠근다(수강편람 동기화). 사용자 요청과 같이 timetable 행을 먼저 잠그고 timetable_lecture를 고친다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Timetable t WHERE t.id IN :ids ORDER BY t.id")
    fun findAllForUpdateByIdIn(ids: Collection<Long>): List<Timetable>

    fun findByUserId(userId: Long): List<Timetable>

    fun findByUserIdAndYearAndSemester(
        userId: Long,
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

    /**
     * 대표 여부만 바꾼다. 엔티티를 고치면 @UpdateTimestamp가 updated_at을 바꾸는데, 대표 지정·해제는 시간표 내용의 수정이 아니다.
     * v1도 isPrimary만 바꿔 저장했고(TimetableServiceImpl.setPrimary/unSetPrimary의 copy), 앱은 updated_at이 가장 최근인 시간표를 처음에 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Timetable t SET t.isPrimary = :isPrimary WHERE t.id = :timetableId")
    fun updateIsPrimary(
        timetableId: Long,
        isPrimary: Boolean,
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
        """
        SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.PrimaryTimetableTarget(t.id, t.userId)
        FROM Timetable t WHERE t.year = :year AND t.semester = :semester AND t.isPrimary = true
        """,
    )
    fun findPrimaryTargetsByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<PrimaryTimetableTarget>

    fun countByUserId(userId: Long): Long
}
