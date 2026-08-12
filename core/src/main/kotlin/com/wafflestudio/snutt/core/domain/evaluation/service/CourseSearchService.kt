package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSearchRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.tag.model.TagValueType
import com.wafflestudio.snutt.core.domain.tag.repository.TagGroupRepository
import com.wafflestudio.snutt.core.domain.tag.repository.TagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CourseSemester(
    val year: Int,
    val semester: Semester,
    val lectureExternalId: String,
    val myEvaluationExists: Boolean,
)

data class CourseWithSemesters(
    val course: Course,
    val semesters: List<CourseSemester>,
)

// 강의평 탭의 강의 검색/상세 (구 ev LectureService 이식)
@Service
class CourseSearchService(
    private val courseSearchRepository: CourseSearchRepository,
    private val courseRepository: CourseRepository,
    private val lectureRepository: LectureRepository,
    private val evaluationRepository: EvaluationRepository,
    private val tagRepository: TagRepository,
    private val tagGroupRepository: TagGroupRepository,
) {
    @Transactional(readOnly = true)
    fun search(
        query: String,
        tagIds: List<Long>,
        page: Int,
    ): List<Course> = courseSearchRepository.search(criteriaOf(query, tagIds, page))

    // 태그는 그룹 이름에 따라 강의 속성으로 환원된다
    private fun criteriaOf(
        query: String,
        tagIds: List<Long>,
        page: Int,
    ): CourseSearchCriteria {
        if (tagIds.isEmpty()) return CourseSearchCriteria(query = query, page = page)
        val tags = tagRepository.findAllById(tagIds)
        val groupsById = tagGroupRepository.findAllById(tags.map { it.tagGroupId }.distinct()).associateBy { it.id }
        val byGroupName =
            tags.groupBy(
                { groupsById[it.tagGroupId]?.name.orEmpty() },
                { tag ->
                    when (groupsById[tag.tagGroupId]?.valueType) {
                        TagValueType.INT -> tag.intValue
                        TagValueType.STRING -> tag.stringValue
                        else -> null
                    }
                },
            )
        val yearSemesters =
            byGroupName["학기"].orEmpty().filterIsInstance<String>().mapNotNull {
                val parts = it.split(",")
                parts.getOrNull(0)?.toIntOrNull()?.let { year ->
                    parts.getOrNull(1)?.toIntOrNull()?.let { semester -> year to semester }
                }
            }
        return CourseSearchCriteria(
            query = query,
            classification = byGroupName["구분"].orEmpty().filterIsInstance<String>(),
            credit = byGroupName["학점"].orEmpty().filterIsInstance<Int>(),
            academicYear = byGroupName["학년"].orEmpty().filterIsInstance<String>(),
            department = byGroupName["학과"].orEmpty().filterIsInstance<String>(),
            category = byGroupName["교양분류"].orEmpty().filterIsInstance<String>(),
            yearSemesters = yearSemesters,
            page = page,
        )
    }

    // 강의평 상세: 개설 학기 목록과 내가 이미 평가했는지
    @Transactional(readOnly = true)
    fun getCourseWithSemesters(
        courseId: Long,
        userId: Long,
    ): CourseWithSemesters {
        val course = courseRepository.findById(courseId).orElseThrow { SnuttException(ErrorType.LECTURE_NOT_FOUND) }
        val lectures = lectureRepository.findByCourseIdOrderByYearDescSemesterDesc(courseId)
        val evaluated =
            lectures
                .filter { lecture ->
                    evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
                        courseId,
                        lecture.year,
                        lecture.semester,
                        userId,
                    )
                }.map { lecture -> lecture.year to lecture.semester }
                .toSet()
        return CourseWithSemesters(
            course = course,
            semesters =
                lectures.map {
                    CourseSemester(
                        year = it.year,
                        semester = it.semester,
                        lectureExternalId = it.externalId,
                        myEvaluationExists = (it.year to it.semester) in evaluated,
                    )
                },
        )
    }
}
