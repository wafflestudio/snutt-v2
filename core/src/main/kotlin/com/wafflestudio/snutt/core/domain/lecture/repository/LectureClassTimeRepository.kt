package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.JdbcTemplate

interface LectureClassTimeBatchRepository {
    fun insertAll(classTimes: List<LectureClassTime>)
}

class LectureClassTimeBatchRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate,
) : LectureClassTimeBatchRepository {
    override fun insertAll(classTimes: List<LectureClassTime>) {
        if (classTimes.isEmpty()) return
        jdbcTemplate.batchUpdate(
            "INSERT INTO lecture_class_time (lecture_id, day, place, start_minute, end_minute) VALUES (?, ?, ?, ?, ?)",
            classTimes,
            classTimes.size,
        ) { ps, classTime ->
            ps.setLong(1, classTime.lectureId)
            ps.setInt(2, classTime.day.value)
            ps.setString(3, classTime.place)
            ps.setInt(4, classTime.startMinute)
            ps.setInt(5, classTime.endMinute)
        }
    }
}

interface LectureClassTimeRepository :
    JpaRepository<LectureClassTime, Long>,
    LectureClassTimeBatchRepository {
    @Modifying
    @Query("DELETE FROM LectureClassTime c WHERE c.lectureId IN :lectureIds")
    fun deleteByLectureIdIn(lectureIds: Collection<Long>)

    fun findAllByLectureIdInOrderById(lectureIds: Collection<Long>): List<LectureClassTime>
}
