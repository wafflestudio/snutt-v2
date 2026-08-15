package com.wafflestudio.snutt.api.lecture

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureSearchRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LectureSearchDiffTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("lecture_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var lectureSearchRepository: LectureSearchRepository

    @LocalServerPort
    var port = 0

    private lateinit var referenceLectures: List<ReferenceLecture>

    private val titles =
        listOf(
            Triple("컴퓨터과학입문", "컴퓨터공학부", "4190.204"),
            Triple("컴퓨터프로그래밍", "컴퓨터공학부", "4190.205"),
            Triple("컴퓨터네트워크", "컴퓨터공학부", "4190.406"),
            Triple("컴퓨터구조", "컴퓨터공학부", "4190.305"),
            Triple("CSE and Programming", "컴퓨터공학부", "4190.210"),
            Triple("CSE Data Structures", "컴퓨터공학부", "4190.302"),
            Triple("C++ Programming", "컴퓨터공학부", "4190.211"),
            Triple("프로그래밍실습(4190.204)", "컴퓨터공학부", "4190.301"),
            Triple("프로그래밍실습(4190X204)", "컴퓨터공학부", "4190.306"),
            Triple("전기전자공학개론", "전기공학부", "430.201"),
            Triple("전기회로이론", "전기공학부", "430.302"),
            Triple("수학의세계", "수학과", "034.001"),
            Triple("선형대수학", "수학과", "034.005"),
            Triple("공학수학", "수학과", "034.012"),
            Triple("물리학실험", "물리학과", "334.101"),
            Triple("경제학원론", "경제학과", "211.304"),
            Triple("데이터베이스", "컴퓨터공학부", "4190.404"),
            Triple("운영체제", "컴퓨터공학부", "4190.403"),
            Triple("알고리즘", "컴퓨터공학부", "4190.440"),
            Triple("체육과건강", "체육교육과", "051.001"),
            Triple("국어국문학개론", "국어국문학과", "101.101"),
            Triple("불어불문학입문", "불어불문학과", "109.101"),
            Triple("영미문학의이해", "영어영문학과", "108.102"),
            Triple("심리학개론", "심리학과", "204.101"),
            Triple("사회학입문", "사회학과", "205.101"),
            Triple("생물학실험", "생명과학부", "334.201"),
            Triple("화학의기초", "화학부", "334.301"),
            Triple("통계학입문", "통계학과", "212.201"),
        )

    private val instructors = listOf("김컴퓨터", "이전기", "박수학", "최물리", "정경제", "한데이터", "오운영", "유알고", "강체육", "John Smith", "Jane Doe")
    private val academicYears = listOf("1학년", "2학년", "3학년", "4학년", "석사", "박사", "석박사통합")
    private val classifications = listOf("전선", "전필", "교양", "일선")
    private val categories = listOf("전공필수", "전공선택", "교양필수", "교양선택", "체육", "일반교양")
    private val remarks = listOf(null, "ⓔ", "ⓜⓞ", "권장과목", "ⓔⓜⓞ", "권장과목ⓔ", "원어강의", "실험실습")
    private val places = listOf("302-101", "302-102", "43-1-302", "43-1-303", "301-1A", "301-1B")
    private val timeSlots =
        listOf(
            listOf(ReferenceClassTime(DayOfWeek.MONDAY, "302-101", 570, 660)),
            listOf(ReferenceClassTime(DayOfWeek.TUESDAY, "43-1-302", 780, 870)),
            listOf(ReferenceClassTime(DayOfWeek.WEDNESDAY, "302-102", 570, 660)),
            listOf(ReferenceClassTime(DayOfWeek.THURSDAY, "43-1-303", 780, 870)),
            listOf(ReferenceClassTime(DayOfWeek.MONDAY, "301-1A", 570, 660), ReferenceClassTime(DayOfWeek.WEDNESDAY, "301-1B", 570, 660)),
            listOf(ReferenceClassTime(DayOfWeek.MONDAY, "302-101", 780, 870), ReferenceClassTime(DayOfWeek.FRIDAY, "302-102", 780, 870)),
            emptyList(),
        )

    private fun lectureSeed(
        title: String,
        department: String,
        courseNumber: String,
        section: Int,
        random: Random,
    ): SeedLecture {
        val instructor = instructors[random.nextInt(instructors.size)]
        val category = categories[random.nextInt(categories.size)]
        val classification =
            when (category) {
                "전공필수" -> "전필"
                "전공선택" -> "전선"
                "체육" -> "교양"
                else -> classifications[random.nextInt(classifications.size)]
            }
        val credit = listOf(2, 3, 3, 3, 4)[random.nextInt(5)]
        val remark = remarks[random.nextInt(remarks.size)]
        return SeedLecture(
            year = 2026,
            semester = Semester.AUTUMN,
            courseNumber = courseNumber,
            lectureNumber = "%03d".format(section),
            courseTitle = title,
            instructor = instructor,
            department = department,
            academicYear = academicYears[random.nextInt(academicYears.size)],
            category = category,
            categoryPre2025 = if (category.startsWith("전공")) category else null,
            classification = classification,
            credit = credit,
            remark = remark,
            classPlaceAndTimes = timeSlots[random.nextInt(timeSlots.size)],
        )
    }

    @BeforeAll
    fun seedDatabase() {
        val random = Random(42)
        val seeds = mutableListOf<SeedLecture>()
        for ((title, department, courseNumber) in titles) {
            for (section in 1..13) {
                seeds += lectureSeed(title, department, courseNumber, section, random)
            }
        }
        val explicit =
            listOf(
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "000.001",
                    "001",
                    "체육특강",
                    "강체육",
                    "체육교육과",
                    "1학년",
                    "체육",
                    null,
                    "교양",
                    1,
                    "ⓔ",
                    listOf(ReferenceClassTime(DayOfWeek.MONDAY, "302-101", 570, 660)),
                ),
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "000.002",
                    "001",
                    "대학원세미나",
                    "박수학",
                    "수학과",
                    "석박사통합",
                    "전공선택",
                    "전공선택",
                    "전선",
                    3,
                    null,
                    listOf(ReferenceClassTime(DayOfWeek.TUESDAY, "43-1-302", 780, 870)),
                ),
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "000.003",
                    "001",
                    "군휴학원격강좌",
                    "이전기",
                    "전기공학부",
                    "4학년",
                    "전공선택",
                    "전공선택",
                    "전선",
                    3,
                    "ⓜⓞ",
                    listOf(ReferenceClassTime(DayOfWeek.WEDNESDAY, "302-102", 570, 660)),
                ),
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "000.004",
                    "001",
                    "취업과진로",
                    "한데이터",
                    "컴퓨터공학부",
                    "4학년",
                    "교양필수",
                    null,
                    "교양",
                    2,
                    "권장과목",
                    emptyList(),
                ),
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "000.005",
                    "001",
                    "석사논문연구",
                    "오운영",
                    "컴퓨터공학부",
                    "석사",
                    "전공필수",
                    "전공필수",
                    "전필",
                    3,
                    null,
                    listOf(ReferenceClassTime(DayOfWeek.THURSDAY, "43-1-303", 780, 870)),
                ),
            )
        seeds += explicit
        seeds +=
            List(25) { i ->
                SeedLecture(
                    2026,
                    Semester.AUTUMN,
                    "999.%03d".format(i),
                    "001",
                    "기타강좌$i",
                    "기타교수$i",
                    "기타학과",
                    "1학년",
                    "일반교양",
                    null,
                    "일선",
                    1,
                    null,
                    listOf(ReferenceClassTime(DayOfWeek.FRIDAY, "302-101", 570, 660)),
                )
            }

        val courses = mutableMapOf<Pair<String, String>, Course>()
        val linkedSeeds =
            seeds.map { seed ->
                if (seed.instructor != null && random.nextInt(10) < 7) {
                    val key = seed.courseNumber to seed.instructor
                    val course =
                        courses.getOrPut(key) {
                            Course(
                                courseNumber = seed.courseNumber,
                                instructor = seed.instructor!!,
                                title = seed.courseTitle,
                                department = seed.department,
                                credit = seed.credit,
                                academicYear = seed.academicYear,
                                category = seed.category,
                                classification = seed.classification,
                                avgRating = (10 + random.nextInt(40)) / 10.0,
                                evalCount = random.nextInt(50).toLong(),
                            )
                        }
                    seed.copy(course = course)
                } else {
                    seed
                }
            }

        courseRepository.saveAll(courses.values)
        val lectures = lectureRepository.saveAll(linkedSeeds.map { it.toLecture() })
        val classTimes =
            linkedSeeds.flatMapIndexed { i, seed ->
                seed.classPlaceAndTimes.map { time ->
                    LectureClassTime(
                        lecture = lectures[i],
                        day = time.day,
                        place = time.place,
                        startMinute = time.startMinute,
                        endMinute = time.endMinute,
                    )
                }
            }
        lectureClassTimeRepository.saveAll(classTimes)

        referenceLectures =
            linkedSeeds.mapIndexed { i, seed ->
                ReferenceLecture(
                    id = checkNotNull(lectures[i].id),
                    year = seed.year,
                    semester = seed.semester,
                    academicYear = seed.academicYear,
                    category = seed.category,
                    categoryPre2025 = seed.categoryPre2025,
                    classification = seed.classification,
                    credit = seed.credit,
                    department = seed.department,
                    instructor = seed.instructor,
                    lectureNumber = seed.lectureNumber,
                    remark = seed.remark,
                    courseNumber = seed.courseNumber,
                    courseTitle = seed.courseTitle,
                    classPlaceAndTimes = seed.classPlaceAndTimes,
                    avgRating = seed.course?.avgRating,
                    evalCount = seed.course?.evalCount ?: 0,
                )
            }
    }

    private fun assertSearch(
        name: String,
        criteria: LectureSearchCriteria,
        expectNonEmpty: Boolean = true,
    ) {
        val reference = LectureSearchReference.search(referenceLectures, criteria).map { it.id }
        val sql = lectureSearchRepository.search(criteria).map { checkNotNull(it.id) }
        if (expectNonEmpty) {
            assertEquals(true, reference.isNotEmpty(), "corpus[$name]: 참조 결과가 비어 있음 — 데이터셋 확인 필요")
        }
        assertEquals(reference, sql, "corpus[$name] 불일치")
    }

    private fun criteria(
        query: String? = null,
        classification: List<String>? = null,
        credit: List<Int>? = null,
        courseNumber: List<String>? = null,
        academicYear: List<String>? = null,
        department: List<String>? = null,
        category: List<String>? = null,
        categoryPre2025: List<String>? = null,
        etcTags: List<String>? = null,
        times: List<SearchTime>? = null,
        timesToExclude: List<SearchTime>? = null,
        offset: Long = 0,
        limit: Int = 20,
        sort: LectureSort = LectureSort.DEFAULT,
    ) = LectureSearchCriteria(
        year = 2026,
        semester = Semester.AUTUMN,
        query = query,
        classification = classification,
        credit = credit,
        courseNumber = courseNumber,
        academicYear = academicYear,
        department = department,
        category = category,
        categoryPre2025 = categoryPre2025,
        etcTags = etcTags,
        times = times,
        timesToExclude = timesToExclude,
        offset = offset,
        limit = limit,
        sort = sort,
    )

    @Test
    fun `검색 corpus가 참조 포트와 일치한다`() {
        val monMorning = SearchTime(DayOfWeek.MONDAY, 570, 660)
        val wedMorning = SearchTime(DayOfWeek.WEDNESDAY, 570, 660)
        val tueAfternoon = SearchTime(DayOfWeek.TUESDAY, 780, 870)

        val corpus =
            listOf(
                "학기 전체" to criteria(limit = 1000),
                "빈 문자열 query" to criteria(query = "", limit = 1000),
                "한국어 fuzzy 제목" to criteria(query = "컴퓨터"),
                "한국어 fuzzy 축약" to criteria(query = "컴공"),
                "학과 '과' 접미사 규칙" to criteria(query = "전기과"),
                "학과 '학' 접미사 규칙" to criteria(query = "수학"),
                "한국어 제목 없는 학과 fuzzy" to criteria(query = "물리학"),
                "영어 제목 substring" to criteria(query = "CSE"),
                "영어 + 이스케이프" to criteria(query = "C++"),
                "과목코드 점 이스케이프" to criteria(query = "4190.204"),
                "과목코드 정확히" to criteria(query = "4190.205"),
                "분반번호" to criteria(query = "001", limit = 1000),
                "전공 특수어" to criteria(query = "전공", limit = 1000),
                "석박 특수어" to criteria(query = "석박"),
                "대학원 특수어" to criteria(query = "대학원"),
                "학부 특수어" to criteria(query = "학부", limit = 1000),
                "체육 특수어" to criteria(query = "체육"),
                "영강 특수어" to criteria(query = "영강"),
                "영어강의 특수어" to criteria(query = "영어강의"),
                "군휴학 특수어" to criteria(query = "군휴학"),
                "권장과목 특수어" to criteria(query = "권장과목"),
                "건물 검색" to criteria(query = "302동"),
                "장소 검색" to criteria(query = "302-101"),
                "장소 세부 검색" to criteria(query = "43-1"),
                "다중 키워드 AND" to criteria(query = "컴퓨터 1학년", limit = 1000),
                "분류 필터" to criteria(classification = listOf("전선", "전필"), limit = 1000),
                "학점 필터" to criteria(credit = listOf(3), limit = 1000),
                "과목코드 필터" to criteria(courseNumber = listOf("4190.204", "4190.205")),
                "학년 필터" to criteria(academicYear = listOf("1학년", "2학년")),
                "학과 필터" to criteria(department = listOf("컴퓨터공학부")),
                "카테고리 필터" to criteria(category = listOf("전공필수", "체육")),
                "카테고리 pre2025 필터" to criteria(categoryPre2025 = listOf("전공필수")),
                "etcTags E" to criteria(etcTags = listOf("E")),
                "etcTags MO" to criteria(etcTags = listOf("MO")),
                "etcTags R" to criteria(etcTags = listOf("R")),
                "etcTags 복합" to criteria(etcTags = listOf("E", "R")),
                "미지의 etcTag 무시" to criteria(etcTags = listOf("UNKNOWN"), limit = 1000),
                "시간 포함 단일" to criteria(times = listOf(monMorning)),
                "시간 포함 이중" to criteria(times = listOf(monMorning, wedMorning)),
                "시간 제외" to criteria(timesToExclude = listOf(tueAfternoon)),
                "시간 포함+제외" to criteria(times = listOf(monMorning), timesToExclude = listOf(tueAfternoon)),
                "query+시간+필터 복합" to criteria(query = "컴퓨터", times = listOf(monMorning), department = listOf("컴퓨터공학부")),
                "기본 정렬 페이지 2" to criteria(limit = 20, offset = 20),
                "기본 정렬 작은 페이지" to criteria(limit = 10, offset = 5),
                "평점순 정렬" to criteria(sort = LectureSort.RATING_DESC, limit = 1000),
                "평점순 정렬 페이지" to criteria(sort = LectureSort.RATING_DESC, limit = 20, offset = 30),
                "강의평 많은 순" to criteria(sort = LectureSort.COUNT_DESC, limit = 1000),
                "평점순 + query" to criteria(query = "컴퓨터", sort = LectureSort.RATING_DESC, limit = 1000),
            )

        corpus.forEach { (name, c) -> assertSearch(name, c) }
    }

    @Test
    fun `QUERY 메서드로 검색이 동작한다`() {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v2/lectures/search"))
                .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"year":2026,"semester":3,"query":"컴퓨터","limit":10}"""))
                .header("Content-Type", "application/json")
                .header("x-client-platform", "ios")
                .header("x-client-key", "test-ios-key")
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("courseTitle"))
    }
}

private data class SeedLecture(
    val year: Int,
    val semester: Semester,
    val courseNumber: String,
    val lectureNumber: String,
    val courseTitle: String,
    val instructor: String?,
    val department: String?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val credit: Int,
    val remark: String?,
    val classPlaceAndTimes: List<ReferenceClassTime>,
    val course: Course? = null,
)

private fun SeedLecture.toLecture() =
    Lecture(
        year = year,
        semester = semester,
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        courseTitle = courseTitle,
        instructor = instructor,
        department = department,
        academicYear = academicYear,
        category = category,
        categoryPre2025 = categoryPre2025,
        classification = classification,
        credit = credit,
        quota = 40,
        freshmanQuota = 10,
        remark = remark,
        courseId = course?.id,
    )
