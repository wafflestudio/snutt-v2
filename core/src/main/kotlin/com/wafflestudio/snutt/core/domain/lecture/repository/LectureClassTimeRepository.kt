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
        classTimes.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "(?, ?, ?, ?, ?)" }
            jdbcTemplate.update(
                "INSERT INTO lecture_class_time (lecture_id, day, place, start_minute, end_minute) VALUES $placeholders",
            ) { ps ->
                var idx = 1
                for (c in chunk) {
                    ps.setLong(idx++, c.lectureId)
                    ps.setInt(idx++, c.day.value)
                    ps.setString(idx++, c.place)
                    ps.setInt(idx++, c.startMinute)
                    ps.setInt(idx++, c.endMinute)
                }
            }
        }
    }

    private companion object {
        const val INSERT_CHUNK_SIZE = 500
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
