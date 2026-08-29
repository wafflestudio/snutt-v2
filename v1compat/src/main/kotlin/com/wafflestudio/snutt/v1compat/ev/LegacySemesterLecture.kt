package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "legacy_semester_lecture")
class LegacySemesterLecture(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val courseId: Long,
    val year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    val semester: Semester,
    val credit: Int,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    val extraInfo: String,
    val academicYear: String,
    val category: String,
    val classification: String,
)

interface LegacySemesterLectureRepository : JpaRepository<LegacySemesterLecture, Long> {
    fun findByCourseIdOrderByYearDescSemesterDesc(courseId: Long): List<LegacySemesterLecture>
}

@Service
class LegacySemesterLectureService(
    private val repository: LegacySemesterLectureRepository,
    private val lectureRepository: LectureRepository,
) {
    fun get(id: Long): LegacySemesterLecture = repository.findById(id).orElseThrow { SnuttException(ErrorType.EV_DATA_NOT_FOUND) }

    @Transactional
    fun getAll(courseId: Long): List<LegacySemesterLecture> {
        val existing = repository.findByCourseIdOrderByYearDescSemesterDesc(courseId)
        val existingSemesters = existing.map { it.year to it.semester }.toSet()
        val missing =
            lectureRepository
                .findByCourseIdOrderByYearDescSemesterDesc(courseId)
                .groupBy { it.year to it.semester }
                .filterKeys { it !in existingSemesters }
                .values
                .map { lectures -> lectures.minBy { it.id!! } }
                .map {
                    LegacySemesterLecture(
                        courseId = courseId,
                        year = it.year,
                        semester = it.semester,
                        credit = it.credit,
                        extraInfo = it.remark.orEmpty(),
                        academicYear = it.academicYear.orEmpty(),
                        category = it.category.orEmpty(),
                        classification = it.classification.orEmpty(),
                    )
                }
        if (missing.isNotEmpty()) repository.saveAll(missing)
        return repository.findByCourseIdOrderByYearDescSemesterDesc(courseId)
    }
}
