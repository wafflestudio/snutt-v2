package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationLikeRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.v1compat.ev.LegacyCourseRepository
import com.wafflestudio.snutt.v1compat.ev.LegacyCourseSearchCriteria
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Limit

/** 강의평·과목·수강 강의 조회의 실행계획. #60·#61·#62에서 정한 인덱스와 쿼리 모양을 고정한다. */
class EvaluationQueryPlanTest : AbstractPerfTest() {
    @Autowired lateinit var evaluationRepository: EvaluationRepository

    @Autowired lateinit var evaluationLikeRepository: EvaluationLikeRepository

    @Autowired lateinit var courseRepository: CourseRepository

    @Autowired lateinit var legacyCourseRepository: LegacyCourseRepository

    private fun page(
        sort: EvaluationSort,
        includeMine: Boolean,
        year: Int? = null,
    ) = explainAll {
        evaluationRepository.findPageOfCourse(
            courseId = perf.popularCourseId,
            userId = perf.typicalUserId,
            year = year,
            semester = year?.let { perf.current.semesterEnum },
            cursor = null,
            pageSize = 20,
            sort = sort,
            includeMine = includeMine,
        )
    }

    @Test
    fun `최신순 강의평 목록은 idx_evaluation_course_visible 역방향 스캔으로 filesort 없이 읽는다`() {
        // 본인 강의평을 먼저 붙이면 쿼리가 둘(내 것, 남의 것)이다. 내 것은 사용자 인덱스로 몇 행만 읽고 정렬해도 되고(작성자는 과목당 몇 개뿐),
        // 남의 것은 과목의 강의평 전체가 대상이라 인덱스 순서로 LIMIT에서 멈춰야 한다.
        val (mine, others) = page(EvaluationSort.LATEST, includeMine = true)
        mine.assertNoFullScan().assertRowsAtMost("evaluation", 50)
        others.assertKey("evaluation", "idx_evaluation_course_visible").assertNoFilesort().assertNoFullScan()
        page(EvaluationSort.LATEST, includeMine = false)
            .single()
            .assertKey("evaluation", "idx_evaluation_course_visible")
            .assertNoFilesort()
    }

    @Test
    fun `학기를 고른 최신순 목록은 학기까지 등호인 인덱스로 filesort가 없다`() {
        // (course_id, year, semester, id DESC)는 year·semester가 등호면 id DESC 순서가 그대로 나온다.
        page(EvaluationSort.LATEST, includeMine = false, year = perf.current.year)
            .single()
            .assertKey("evaluation", "idx_evaluation_course_semester", "idx_evaluation_course_visible")
            .assertNoFilesort()
    }

    @Test
    fun `추천순 강의평 목록은 idx_evaluation_course_recommended로 filesort 없이 읽는다`() {
        page(EvaluationSort.RECOMMENDED, includeMine = false)
            .single()
            .assertKey("evaluation", "idx_evaluation_course_recommended")
            .assertNoFilesort()
            .assertNoFullScan()
    }

    @Test
    fun `과목별 최근 강의평은 과목 범위만 읽는다`() {
        // 창 함수 결과(<derived2>)를 다시 정렬하는 것은 과목 수 × limit 행이라 허용한다. 원본은 과목 인덱스 범위만 읽는다.
        explainOne { evaluationRepository.findRecentOfCourses(listOf(perf.popularCourseId, perf.popularCourseId + 1), 2) }
            .assertNoFullScan()
            .assertKey("evaluation", "idx_evaluation_course_visible", "PRIMARY")
            .assertRowsAtMost("evaluation", 1_000)
    }

    @Test
    fun `과목 강의평 수와 집계는 과목 인덱스 범위에서 끝난다`() {
        explainOne { evaluationRepository.countVisible(perf.popularCourseId, null, null) }
            .assertNoFullScan()
            .assertRowsAtMost("evaluation", 1_000)
        explainOne { evaluationRepository.findCourseAggregate(perf.popularCourseId) }
            .assertNoFullScan()
            .assertRowsAtMost("evaluation", 1_000)
    }

    @Test
    fun `최근 강의평 태그는 PK 역순으로 20개만 읽고 filesort가 없다`() {
        explainOne { evaluationRepository.findByTag(EvaluationTag.RECENT, null, 20) }
            .assertKey("evaluation", "PRIMARY")
            .assertNoFilesort()
    }

    @Test
    fun `교양 태그는 idx_lecture_classification_course로 교양 강의 존재 여부를 본다`() {
        explainOne { evaluationRepository.findByTag(EvaluationTag.LIBERAL_EDUCATION, null, 20) }
            .assertKey("evaluation", "PRIMARY")
            .assertKey("lecture", "idx_lecture_classification_course")
            .assertNoFullScan()
            .assertNoFilesort()
    }

    @Test
    fun `집계 태그는 강의평 PK 역순에 과목을 PK로 붙인다`() {
        explainOne { evaluationRepository.findByTag(EvaluationTag.RECOMMENDED, null, 20) }
            .assertAccess("course", "eq_ref")
            .assertNoFilesort()
    }

    @Test
    fun `내 강의평은 idx_evaluation_user로 filesort 없이 읽는다`() {
        explainOne { evaluationRepository.findMine(perf.typicalUserId, null, Limit.of(20)) }
            .assertKey("evaluation", "idx_evaluation_user")
            .assertNoFilesort()
            .assertRowsAtMost("evaluation", 100)
        explainOne { evaluationRepository.countByUserIdAndIsHiddenFalse(perf.typicalUserId) }
            .assertKey("evaluation", "idx_evaluation_user")
    }

    @Test
    fun `snuev 이관 계정의 강의평도 idx_evaluation_user로 20개만 읽는다`() {
        // 강의평 94%(약 15,600개)를 가진 계정. 인덱스 순서가 ORDER BY id DESC와 같아 LIMIT에서 멈춘다.
        explainOne { evaluationRepository.findMine(perf.snuevUserId, null, Limit.of(20)) }
            .assertKey("evaluation", "idx_evaluation_user")
            .assertNoFilesort()
    }

    @Test
    fun `내가 쓴 과목·학기 확인과 공감 여부는 인덱스 범위만 읽는다`() {
        explainOne { evaluationRepository.findEvaluatedCourseSemesters(perf.typicalUserId, listOf(perf.popularCourseId, 2L, 3L)) }
            .assertNoFullScan()
            .assertRowsAtMost("evaluation", 100)
        explainOne { evaluationLikeRepository.findByUserIdAndEvaluationIdIn(perf.typicalUserId, (1L..20L).toList()) }
            .assertKey("evaluation_like", "uk_evaluation_like", "fk_evaluation_like_user")
            .assertNoFullScan()
            .assertRowsAtMost("evaluation_like", 50)
    }

    @Test
    fun `수강 강의는 사용자 학기 인덱스에서 시작해 시간표 강의·강의·과목을 키로 붙인다`() {
        val semesters = listOf(perf.previous, perf.beforePrevious).map { YearAndSemester(it.year, it.semesterEnum) }
        explainOne { courseRepository.findTakenLectures(perf.typicalUserId, semesters) }
            .assertNoFullScan()
            .assertKey("timetable", "idx_timetable_user_semester", "uk_timetable_title")
            .assertKey("timetable_lecture", "idx_timetable_lecture_timetable")
            .assertAccess("lecture", "eq_ref")
            .assertAccess("course", "eq_ref")
            .assertRowsAtMost("timetable", 20)
    }

    @Test
    fun `검색어 과목 검색은 idx_course_eval_count 순서로 읽다가 LIMIT에서 멈춘다`() {
        // LIKE '%검색어%'는 인덱스로 거를 수 없다. 정렬 순서와 같은 인덱스를 따라가며 20개를 찾으면 멈춘다(드문 검색어는 끝까지 읽는다).
        explainOne { courseRepository.search(CourseSearchCriteria(query = "운영체제"), null, 20) }
            .assertKey("course", "idx_course_eval_count")
            .assertNoFilesort()
            .assertAccess("lecture", "eq_ref")
        explainOne { legacyCourseRepository.search(LegacyCourseSearchCriteria(query = "운영체제"), 0, 20) }
            .assertKey("course", "idx_course_eval_count")
            .assertNoFilesort()
    }

    @Test
    fun `학기를 고른 과목 검색은 그 학기 강의를 학기 인덱스로 찾아 과목에 붙인다`() {
        val semester = YearAndSemester(perf.current.year, perf.current.semesterEnum)
        explainOne {
            courseRepository.search(
                CourseSearchCriteria(yearSemesters = listOf(semester), classification = listOf("교양")),
                null,
                20,
            )
        }.assertNoFullScan()
            .assertAccess("course", "eq_ref")
            .assertRowsAtMost("lecture", 15_000)
    }

    @Test
    fun `검색어 없는 과목 검색은 idx_course_eval_count 순서로 filesort 없이 읽는다`() {
        explainOne { courseRepository.search(CourseSearchCriteria(), null, 20) }
            .assertKey("course", "idx_course_eval_count")
            .assertNoFilesort()
            .assertAccess("lecture", "eq_ref")
    }
}
