package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.model.CourseSemester
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSemesterRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CourseSemesterService(
    private val courseSemesterRepository: CourseSemesterRepository,
) {
    fun get(id: Long): CourseSemester = courseSemesterRepository.findByIdOrNull(id) ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)

    @Transactional
    fun sync(lectures: Collection<Lecture>) {
        val offerings =
            lectures
                .filter { it.courseId != null }
                .groupBy { Triple(it.courseId!!, it.year, it.semester) }
                .mapValues { (_, rows) -> rows.minBy { it.id!! } }
        if (offerings.isEmpty()) return
        val existing =
            courseSemesterRepository
                .findByCourseIdIn(offerings.keys.map { it.first }.distinct())
                .associateBy { Triple(it.courseId, it.year, it.semester) }
        courseSemesterRepository.saveAll(
            offerings.map { (key, lecture) ->
                val semester = existing[key] ?: CourseSemester(key.first, key.second, key.third, lecture.credit)
                semester.apply {
                    credit = lecture.credit
                    academicYear = lecture.academicYear
                    category = lecture.category
                    classification = lecture.classification
                    extraInfo = lecture.remark
                }
            },
        )
    }
}
