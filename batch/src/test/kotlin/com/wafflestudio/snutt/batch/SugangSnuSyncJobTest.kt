package com.wafflestudio.snutt.batch

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.CONDITION
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.EXCEL
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.LECTURE_INFO
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.html
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.json
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.slow
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.status
import com.wafflestudio.snutt.batch.sugang.SugangXlsxFixtureFiles
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuSyncJob
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import tools.jackson.core.exc.StreamReadException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

class SugangSnuSyncJobTest : AbstractBatchIntegrationTest() {
    @Autowired
    lateinit var sugangSnuSyncJob: SugangSnuSyncJob

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var lectureBuildingRepository: LectureBuildingRepository

    @Autowired
    lateinit var semesterRegistrationPeriodService: SemesterRegistrationPeriodService

    @BeforeEach
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        sugang.lectureInfoForAll()
        sugang.mainPage()
        sugang.snuMapForAll(json("""{"search_list":[]}"""), priority = 20)
        // 테스트에서 시각(updatedAt)을 갱신했는지 구분할 수 있도록 운영과 동떨어진 시각으로 둔다.
        clock.setInstant(TOUCHED_AT)
    }

    private fun runJob() = sugangSnuSyncJob.run(null)

    private fun stubXlsx(rows: List<SugangXlsxFixture.RowData>) {
        sugang.lectureXlsx("ko", SugangXlsxFixture.xlsx(rows).byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(rows).byteArray)
    }

    private fun autumnUpdatedAt(): Instant? =
        coursebookRepository.findAll().single { it.year == 2026 && it.semester == Semester.AUTUMN }.updatedAt

    @Test
    fun `실제 수강편람 엑셀로 새 학기를 동기화한다`() {
        sugang.coursebookCondition(2026, Semester.WINTER)
        sugang.lectureXlsx("ko", SugangXlsxFixtureFiles.koXlsx().byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixtureFiles.enXlsx().byteArray)
        sugang.snuMapSearch("38", json(String(javaClass.getResourceAsStream("/fixtures/snumap/search-38.json")!!.readBytes())))
        sugang.snuMapForAll(json("""{"search_list":[]}"""))

        runJob()

        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.WINTER)
        assertEquals(SugangXlsxFixtureFiles.dataRowCount(SugangXlsxFixtureFiles.koXlsx().byteArray), lectures.size)
        assertTrue(coursebookRepository.findAll().any { it.year == 2026 && it.semester == Semester.WINTER })

        // 개설학과가 비면 개설대학, 학년은 이수과정(석박사통합), 부제는 괄호로 붙는다.
        val intensive = lectures.single { it.courseNumber == "M2177.005500" && it.lectureNumber == "001" }
        assertEquals("글로벌 공학기술 교류 특강 1 (초고집적 반도체 소자 제조를 위한 CVD 및 원자층 공정(ALD/ALE/ASD)의 기초)", intensive.courseTitle)
        assertEquals("공과대학", intensive.department)
        assertEquals("석박사통합", intensive.academicYear)
        assertEquals(40, intensive.quota)
        assertNull(intensive.freshmanQuota)
        assertEquals("Park, Min Hyuk", intensive.instructorEn)
        assertEquals("Combined Masters/Doctorate", intensive.academicYearEn)
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY).map { ClassPlaceAndTime(it, "38-422(무선랜제공)", 660, 710) },
            lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(intensive.id!!)).map { it.toClassPlaceAndTime() },
        )
        // 수업교시가 빈 강의는 시간 없이 들어간다.
        val noTime = lectures.single { it.courseNumber == "M3502.022700" }
        assertEquals(emptyList<Any>(), lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(noTime.id!!)))
        assertTrue(lectures.all { it.courseId != null })

        // 한국어·영어 엑셀은 학기 조건과 언어를 폼으로 보내고, 상세 API는 강좌마다 한 번 부른다.
        listOf("ko", "en").forEach { language ->
            wireMock.verify(
                postRequestedFor(urlPathEqualTo(sugang.path(EXCEL)))
                    .withFormParam("srchLanguage", equalTo(language))
                    .withFormParam("srchOpenSchyy", equalTo("2026"))
                    .withFormParam("srchOpenShtm", equalTo("U000200002U000300002"))
                    .withFormParam("srchPageSize", equalTo("9999"))
                    .withHeader("Referer", equalTo("https://sugang.snu.ac.kr/sugang/cc/cc100InterfaceSrch.action")),
            )
        }
        assertEquals(lectures.size, sugang.requestsTo(LECTURE_INFO))
        wireMock.verify(getRequestedFor(urlPathEqualTo(sugang.path(CONDITION))).withHeader("User-Agent", equalTo(USER_AGENT)))

        // 실제 메인 페이지는 수강취소 일정만 있어 등록일은 비어 있다.
        assertEquals(
            emptyList<Any>(),
            semesterRegistrationPeriodService.getByYearAndSemester(2026, Semester.WINTER)!!.registrationPeriodList,
        )

        // SNU 지도에서 38동 이름과 좌표를 받아 온다. 이름이 가장 짧은 시설을 고른다.
        val building = lectureBuildingRepository.findByBuildingNumberIn(listOf("38")).single()
        assertEquals("해동학술정보실", building.buildingNameKor)
        assertEquals("Haedong Library", building.buildingNameEng)
        assertEquals(37.4553647, building.locationInDecimal!!.latitude)
    }

    // --- 실패하면 배치는 예외로 끝나고, DB에는 아무것도 반영하지 않는다 ---

    @Test
    fun `수강편람 조건 API가 5xx면 아무것도 하지 않는다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookConditionResponse(status(500))

        assertThrows<HttpServerErrorException> { runJob() }

        assertUnchanged(old)
        assertEquals(0, sugang.requestsTo(EXCEL))
    }

    @Test
    fun `수강편람 조건에 모르는 학기 코드가 오면 실패한다`() {
        sugang.coursebookConditionResponse(json("""{"currSchyy":"2026","currShtmFg":"U000200009","currDetaShtmFg":"U000300001"}"""))

        val e = assertThrows<IllegalArgumentException> { runJob() }

        assertTrue(e.message!!.contains("unknown semester flags"), e.message)
        assertEquals(0, sugang.requestsTo(EXCEL))
    }

    @Test
    fun `한국어 엑셀 다운로드가 500이면 재시도 없이 실패하고 기존 강의는 그대로다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsxResponse("ko", status(500))
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(listOf(changedRow())).byteArray)

        assertThrows<HttpServerErrorException> { runJob() }

        // 영어 엑셀은 한국어 다음에 받으므로 요청은 한국어 1번뿐이다.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(sugang.path(EXCEL))))
        assertUnchanged(old)
    }

    @Test
    fun `엑셀 다운로드가 503이면 HTTP 클라이언트가 한 번 재시도해 동기화를 마친다`() {
        // RestClient 밑의 Apache HttpClient 5 기본 재시도: 429·503에 1초 뒤 1회. 500은 재시도하지 않는다.
        lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        val excel = post(urlPathEqualTo(sugang.path(EXCEL))).withFormParam("srchLanguage", equalTo("ko")).inScenario("excel")
        wireMock.stubFor(excel.whenScenarioStateIs(STARTED).willSetStateTo("retried").willReturn(status(503)))
        wireMock.stubFor(
            post(urlPathEqualTo(sugang.path(EXCEL)))
                .withFormParam("srchLanguage", equalTo("ko"))
                .inScenario("excel")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withBody(SugangXlsxFixture.xlsx(listOf(changedRow())).byteArray)),
        )
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(listOf(changedRow())).byteArray)

        runJob()

        wireMock.verify(3, postRequestedFor(urlPathEqualTo(sugang.path(EXCEL))))
        val lecture = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).single()
        assertEquals(
            listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "3-106", 570, 645)),
            lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(lecture.id!!)).map { it.toClassPlaceAndTime() },
        )
    }

    @Test
    fun `엑셀 다운로드가 타임아웃되면 실패하고 기존 강의는 그대로다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsxResponse("ko", slow(html("늦은 응답")))

        assertThrows<ResourceAccessException> { runJob() }

        assertUnchanged(old)
    }

    @Test
    fun `엑셀 자리에 HTML 점검 페이지가 오면 실패하고 기존 강의는 그대로다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsxResponse("ko", html("<html><body>서비스 점검 중입니다</body></html>"))
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(listOf(changedRow())).byteArray)

        val e = assertThrows<IOException> { runJob() }
        assertEquals("Can't open workbook - unsupported file type: HTML", e.message)

        assertUnchanged(old)
    }

    @Test
    fun `바디가 잘린 엑셀이면 실패하고 기존 강의는 그대로다`() {
        val old = lectureRepository.save(existingLecture())
        val ko = SugangXlsxFixtureFiles.koXlsx().byteArray
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsx("ko", ko.copyOf(ko.size / 2))
        sugang.lectureXlsx("en", SugangXlsxFixtureFiles.enXlsx().byteArray)

        // POI가 OLE2 블록을 찾지 못해 내부 예외로 실패한다.
        assertThrows<IndexOutOfBoundsException> { runJob() }

        assertUnchanged(old)
    }

    @Test
    fun `강의 행이 없는 엑셀이면 실패하고 기존 강의를 지우지 않는다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsx("ko", SugangXlsxFixture.xlsx(emptyList()).byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(listOf(changedRow())).byteArray)

        val e = assertThrows<IllegalArgumentException> { runJob() }

        assertEquals("xlsx에 강의 행이 없다", e.message)
        assertUnchanged(old)
    }

    @Test
    fun `영어 엑셀에 강의 행이 없으면 중단하지 않고 한국어로 동기화하되 영어 필드는 지우지 않는다`() {
        // 학기 초에는 한국어 엑셀만 먼저 공개된다. 신규 학기 생성과 영어 엑셀이 생긴 뒤의 채움은 SugangSyncDiffTest에서 본다.
        val old = lectureRepository.save(existingLecture().apply { courseTitleEn = "Advanced Korean" })
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsx("ko", SugangXlsxFixture.xlsx(listOf(changedRow())).byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(emptyList()).byteArray)

        runJob()

        assertEquals("Advanced Korean", lectureRepository.findById(old.id!!).get().courseTitleEn)
        assertEquals(
            listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "3-106", 570, 645)),
            lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(old.id!!)).map { it.toClassPlaceAndTime() },
        )
        assertEquals(TOUCHED_AT, autumnUpdatedAt())
    }

    @Test
    fun `필수 컬럼이 빠진 엑셀이면 실패하고 기존 강의는 그대로다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        sugang.lectureXlsx("ko", renameHeader(SugangXlsxFixtureFiles.koXlsx().byteArray, "정원", "수강정원"))
        sugang.lectureXlsx("en", SugangXlsxFixtureFiles.enXlsx().byteArray)

        val e = assertThrows<IllegalArgumentException> { runJob() }

        assertEquals("xlsx 필수 컬럼이 없다: [정원]", e.message)
        assertUnchanged(old)
    }

    @Test
    fun `강좌 상세 API가 한 강좌라도 5xx면 실패하고 아무것도 반영하지 않는다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        stubXlsx(listOf(changedRow(), SugangXlsxFixture.RowData(courseNumber = "400.320", lectureNumber = "002", courseTitle = "새 강의")))
        sugang.lectureInfo("400.320", "002", status(502))

        assertThrows<HttpServerErrorException> { runJob() }

        assertUnchanged(old)
        assertEquals(0, notificationRepository.count())
    }

    @Test
    fun `강좌 상세 응답이 잘린 JSON이면 실패하고 아무것도 반영하지 않는다`() {
        val old = lectureRepository.save(existingLecture())
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        stubXlsx(listOf(changedRow()))
        val body = SugangFixtures.text("lecture-info.json")
        sugang.lectureInfo("F27.301", "001", json(body.substring(0, body.length / 2)))

        assertThrows<StreamReadException> { runJob() }

        assertUnchanged(old)
    }

    @Test
    fun `수강신청 일정 표가 없는 메인 페이지면 새 학기를 만들지 않는다`() {
        sugang.coursebookCondition(2026, Semester.WINTER)
        sugang.mainPage("<html><body><div class=\"content\">점검 중</div></body></html>")
        sugang.lectureXlsx("ko", SugangXlsxFixtureFiles.koXlsx().byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixtureFiles.enXlsx().byteArray)

        val e = assertThrows<IllegalStateException> { runJob() }

        assertEquals("수강신청 일정 표를 찾지 못했다", e.message)
        assertTrue(coursebookRepository.findAll().none { it.semester == Semester.WINTER })
        assertEquals(0, lectureRepository.findByYearAndSemester(2026, Semester.WINTER).size)
        assertEquals(0, sugang.requestsTo(EXCEL))
    }

    @Test
    fun `SNU 지도가 5xx여도 강의 동기화는 반영되고 건물만 비어 있다`() {
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        stubXlsx(listOf(changedRow()))
        sugang.snuMapForAll(status(500))

        runJob()

        assertEquals(1, lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).size)
        assertEquals(0, lectureBuildingRepository.count())
        assertTrue(wireMock.allServeEvents.any { it.request.url.startsWith(sugang.snuMapPath("/api/search.action")) })
    }

    @Test
    fun `SNU 지도 응답이 타임아웃돼도 강의 동기화는 반영된다`() {
        sugang.coursebookCondition(2026, Semester.AUTUMN)
        stubXlsx(listOf(changedRow()))
        // 늦게라도 도착하면 3동이 저장되는 응답이다. 타임아웃이 걸려야 건물이 비어 있다.
        val hit = String(javaClass.getResourceAsStream("/fixtures/snumap/search-38.json")!!.readBytes()).replace("\"38\"", "\"3\"")
        sugang.snuMapSearch("3", slow(json(hit)))

        runJob()

        assertEquals(1, lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).size)
        assertEquals(0, lectureBuildingRepository.count())
        assertEquals(1, wireMock.allServeEvents.count { it.request.url.startsWith(sugang.snuMapPath("/api/search.action")) })
    }

    private fun existingLecture() =
        Lecture(
            year = 2026,
            semester = Semester.AUTUMN,
            courseNumber = "F27.301",
            lectureNumber = "001",
            courseTitle = "고급한국어",
            instructor = "황현동",
            department = "국어국문학과",
            academicYear = "1학년",
            category = "외국어",
            classification = "교양",
            credit = 3,
            quota = 20,
        )

    /** [existingLecture]와 시간·강의실만 다른 행. */
    private fun changedRow() =
        SugangXlsxFixture.RowData(
            classification = "교양",
            department = "국어국문학과",
            academicYear = "1학년",
            courseNumber = "F27.301",
            lectureNumber = "001",
            courseTitle = "고급한국어",
            credit = 3,
            classTime = "월(09:30~10:45)",
            place = "3-106",
            instructor = "황현동",
            quota = 20,
        )

    private fun assertUnchanged(old: Lecture) {
        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        assertEquals(listOf(old.id), lectures.map { it.id })
        assertEquals(emptyList<Any>(), lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(old.id!!)))
        assertEquals(0, notificationRepository.count())
        assertTrue(autumnUpdatedAt() != TOUCHED_AT, "실패한 동기화가 학기 갱신 시각을 바꿨다")
    }

    private fun renameHeader(
        xls: ByteArray,
        from: String,
        to: String,
    ): ByteArray =
        WorkbookFactory.create(xls.inputStream()).use { workbook ->
            val header = workbook.getSheetAt(0).getRow(SugangXlsxFixtureFiles.HEADER_ROW)
            header.first { it.stringCellValue == from }.setCellValue(to)
            ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
        }

    companion object {
        private val TOUCHED_AT: Instant = Instant.parse("2030-01-01T00:00:00Z")
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
