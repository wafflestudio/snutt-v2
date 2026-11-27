package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.test.perf.QueryPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 강의 검색(v2 `/v2/lectures/search`, v1 `/v1/search_query`가 같은 리포지토리 메서드를 쓴다)의 실행계획.
 * 검색어는 정규식이라 인덱스로 거를 수 없다. 대신 학기 인덱스로 한 학기(정규학기 약 6,000개)만 읽고 id 순서로 LIMIT에서 멈춰야 한다.
 */
class LectureSearchQueryPlanTest : AbstractPerfTest() {
    @Autowired lateinit var lectureRepository: LectureRepository

    private fun search(
        offset: Int? = null,
        criteria: (LectureSearchCriteria) -> LectureSearchCriteria = { it },
    ): QueryPlan {
        val base = LectureSearchCriteria(year = perf.current.year, semester = perf.current.semesterEnum)
        return explainOne { lectureRepository.search(criteria(base), null, 20, offset) }
    }

    private fun QueryPlan.assertSemesterScan(): QueryPlan =
        assertKey("lecture", "idx_lecture_year_semester")
            .assertNoFullScan()
            .assertNoFilesort()
            .assertAccess("course", "eq_ref")

    @Test
    fun `조건 없는 검색은 idx_lecture_year_semester로 한 학기만 id 순서로 읽는다`() {
        search().assertSemesterScan().assertRowsAtMost("lecture", 15_000)
    }

    @Test
    fun `검색어 검색도 학기 인덱스로 읽고 정규식은 그 안에서 거른다`() {
        search { it.copy(query = "운영체제") }.assertSemesterScan()
        search { it.copy(query = "컴공 전필") }.assertSemesterScan()
    }

    @Test
    fun `분류·학점·학과 필터를 걸어도 학기 인덱스 순서를 유지해 filesort가 없다`() {
        search { it.copy(classification = listOf("전선", "전필"), credit = listOf(3), department = listOf("컴퓨터공학부")) }.assertSemesterScan()
    }

    @Test
    fun `강의 태그 목록은 캐시가 비어도 학기 인덱스 범위만 읽는다`() {
        // 결과는 Redis에 하루 캐시된다(LectureVocabularyService). 캐시 미스 때 DISTINCT 7개와 MAX 1개가 한 학기 안에서 끝나야 한다.
        val plans = explainAll { lectureRepository.findVocabulary(perf.current.year, perf.current.semesterEnum, Language.KO) }
        assertEquals(8, plans.size)
        plans.forEach { it.assertNoFullScan().assertRowsAtMost("lecture", 15_000) }
    }

    @Test
    fun `과목의 강의 목록은 idx_lecture_course 역방향 스캔으로 filesort가 없다`() {
        explainOne { lectureRepository.findByCourseIdOrderByYearDescSemesterDesc(perf.popularCourseId) }
            .assertKey("lecture", "idx_lecture_course")
            .assertNoFilesort()
    }

    @Test
    fun `v1의 오프셋 페이지도 같은 계획이다`() {
        search(offset = 200) { it.copy(query = "기초") }.assertSemesterScan()
    }
}
