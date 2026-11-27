package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.v1compat.ev.LegacyCourseRepository
import com.wafflestudio.snutt.v1compat.ev.LegacyCourseSearchCriteria
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** 운영 분포에서 인덱스를 타지 않는 쿼리 재현(수정 전 실패). */
class UnindexedQueryReproTest : AbstractPerfTest() {
    @Autowired lateinit var legacyCourseRepository: LegacyCourseRepository

    @Autowired lateinit var courseRepository: CourseRepository

    @Test
    fun `v1 강의평 과목 검색의 전체 건수는 검색어가 있으면 course를 풀 스캔한다`() {
        // /v1/ev-service/v1/lectures?query=... 는 요청마다 search(LIMIT)와 count를 함께 부른다. count는 LIMIT이 없어 course 전체를 읽는다.
        explainOne { legacyCourseRepository.count(LegacyCourseSearchCriteria(query = "운영체제")) }
            .assertNoFullScan()
    }

    @Test
    fun `과목 태그 목록의 최종 갱신 시각은 lecture를 풀 스캔한다`() {
        // CourseRepository.getVocabulary의 max(updated_at) where course_id is not null. Redis에 하루 캐시되지만 캐시 미스마다 lecture 전체를 읽는다.
        explainAll { courseRepository.getVocabulary(Language.KO) }
            .last()
            .assertNoFullScan()
    }
}
