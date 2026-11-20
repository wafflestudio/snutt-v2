package com.wafflestudio.snutt.api.lecture

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class EnglishLectureSearchTest : AbstractApiIntegrationTest() {
    companion object {
        private const val CS_001 = "4190.101/001"
        private const val CS_002 = "4190.101/002"
        private const val SYSTEMS = "4190.211/001"
        private const val DS_001 = "4190.206A/001"
        private const val DS_002 = "4190.206A/002"
        private const val PSYCH = "046.016/001"
        private const val CPP_NO_EN = "4190.999/001"
    }

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @BeforeEach
    fun seed() {
        lectureRepository.saveAll(
            listOf(
                lecture("4190.101", "001", "컴퓨터과학개론", "Introduction to Computer Science", "김민수", "Kim, Minsu", "컴퓨터공학부", "전필"),
                lecture("4190.101", "002", "컴퓨터과학개론", "Introduction to Computer Science", "이지훈", "Lee Ji Hoon", "컴퓨터공학부", "전필"),
                lecture("4190.211", "001", "컴퓨터시스템개론", "Introduction to Computer Systems", "김하준", "Kim, Hajun", "컴퓨터공학부", "교양"),
                lecture("4190.206A", "001", "자료구조", "Data Structures", "최동우", "CHOI/DONGWOO", "컴퓨터공학부", "전선"),
                lecture("4190.206A", "002", "자료구조", "Data Structures", "한재웅", "Han, Jae-woong", "컴퓨터공학부", "전선"),
                lecture("046.016", "001", "심리학개론", "Introduction to Psychology", "유하진", "Yu, Hajin", "심리학과", "교양"),
                lecture("212.101", "001", "경제원론 1", "Principles of Economics 1", "김예린", "Kim, Yerin", "경제학부", "교양"),
                lecture("034.020", "001", "물리학 1", "Physics 1", "최윤서", "Choi, Yunseo", "물리천문학부", "교양"),
                lecture("4190.999", "001", "C++ 프로그래밍", null, "John Smith", null, "컴퓨터공학부", "교양"),
            ),
        )
    }

    private fun lecture(
        courseNumber: String,
        lectureNumber: String,
        title: String,
        titleEn: String?,
        instructor: String,
        instructorEn: String?,
        department: String,
        classification: String,
    ) = Lecture(
        year = 2026,
        semester = Semester.AUTUMN,
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        courseTitle = title,
        instructor = instructor,
        department = department,
        classification = classification,
        credit = 3,
        courseTitleEn = titleEn,
        instructorEn = instructorEn,
    )

    private fun assertSearch(
        query: String,
        vararg expected: String,
    ) {
        val found =
            lectureRepository
                .search(LectureSearchCriteria(year = 2026, semester = Semester.AUTUMN, language = Language.EN, query = query), null, 100)
                .map { "${it.lecture.courseNumber}/${it.lecture.lectureNumber}" }
                .toSet()
        assertEquals(expected.toSet(), found, "query=$query")
    }

    @Test
    fun `강의명 단어 머리글자에 순서대로 맞춘다`() {
        assertSearch("intro", CS_001, CS_002, SYSTEMS, PSYCH)
        assertSearch("comp sci", CS_001, CS_002)
        assertSearch("computer science", CS_001, CS_002)
        assertSearch("cs", CS_001, CS_002, SYSTEMS)
        assertSearch("ics", CS_001, CS_002, SYSTEMS)
        assertSearch("itcs", CS_001, CS_002, SYSTEMS)
        assertSearch("intro cs", CS_001, CS_002, SYSTEMS)
        assertSearch("puter")
        assertSearch("cmpt")
        assertSearch("sci comp")
        assertSearch("introintro")
    }

    @Test
    fun `검색어 맨 앞이나 맨 뒤 단어는 교수명으로도 맞춘다`() {
        assertSearch("kim intro", CS_001, SYSTEMS)
        assertSearch("intro kim minsu", CS_001)
        assertSearch("han jae woong", DS_002)
        assertSearch("intro y")
        assertSearch("intro kim cs")
    }

    @Test
    fun `한국어 키워드는 KO와 같은 스마트 검색을 쓴다`() {
        assertSearch("전공", CS_001, CS_002, DS_001, DS_002)
        assertSearch("컴공 intro", CS_001, CS_002, SYSTEMS)
    }

    @Test
    fun `번호 키워드, 영문이 없는 강의, 긴 검색어`() {
        assertSearch("4190.206A 002", DS_002)
        assertSearch("smith", CPP_NO_EN)
        assertSearch("introduction computer science introduction computer science", CS_001, CS_002)
    }
}
