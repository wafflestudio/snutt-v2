package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabularyRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.configuration.JobRegistry
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * M7 DoD: 수강스누 sync 잡 — xlsx 파싱 → lecture/course upsert → tag_list → 변경 알림
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SugangSnuSyncJobTest : AbstractBatchIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("batch_sync_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var jobLauncher: JobLauncher

    @Autowired
    lateinit var jobRegistry: JobRegistry

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var lectureVocabularyRepository: LectureVocabularyRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var timetableRepository: com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository

    @Autowired
    lateinit var timetableLectureRepository: com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository

    @MockitoBean
    lateinit var sugangSnuLectureApi: com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi

    // 상세 API enrichment는 별도 테스트(SugangSnuLectureEnricherTest)에서 검증한다. 여기선 통과시킨다
    @MockitoBean
    lateinit var sugangSnuLectureEnricher: com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureEnricher

    @BeforeAll
    fun seedCoursebook() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
    }

    @BeforeEach
    fun cleanTables() {
        Mockito
            .`when`(
                sugangSnuLectureEnricher.enrich(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                ),
            ).thenAnswer { it.getArgument<Any>(2) }
        lectureClassTimeRepository.deleteAll()
        lectureRepository.deleteAll()
        courseRepository.deleteAll()
        notificationRepository.deleteAll()
        timetableRepository.deleteAll()
    }

    private fun runJob(): BatchStatus =
        jobLauncher
            .run(
                jobRegistry.getJob("sugangSnuMigrationJob"),
                org.springframework.batch.core.job.parameters
                    .JobParametersBuilder()
                    .addLong(
                        "run.id",
                        System.currentTimeMillis(),
                    ).toJobParameters(),
            ).status

    @Autowired
    lateinit var sugangSnuXlsxParser: com.wafflestudio.snutt.batch.sugangsnu.SugangSnuXlsxParser

    @Test
    fun `xlsx 파싱과 신규 강의 upsert`() {
        val xlsx =
            SugangXlsxFixture.xlsx(
                listOf(
                    SugangXlsxFixture.RowData(
                        courseNumber = "4190.204",
                        lectureNumber = "001",
                        courseTitle = "컴퓨터과학입문",
                        instructor = "김컴퓨터",
                    ),
                    SugangXlsxFixture.RowData(
                        courseNumber = "430.201",
                        lectureNumber = "002",
                        courseTitle = "전기전자공학개론",
                        instructor = "이전기",
                    ),
                ),
            )
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "ko")
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "en")
        assertEquals(2, sugangSnuXlsxParser.parse(xlsx).size)

        assertEquals(BatchStatus.COMPLETED, runJob())

        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        assertEquals(2, lectures.size)
        val first = lectures.first { it.courseNumber == "4190.204" }
        assertEquals("컴퓨터과학입문", first.courseTitle)
        assertEquals("3학년", first.academicYear)
        val firstTimes = lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(first.id!!)).map { it.toClassPlaceAndTime() }
        assertEquals(listOf(DayOfWeek.MONDAY), firstTimes.map { it.day })
        assertEquals(570, firstTimes.first().startMinute)
        assertEquals(645, firstTimes.first().endMinute)
        // course 앵커 연결
        assertTrue(first.courseId != null)
        assertEquals("김컴퓨터", courseRepository.findById(first.courseId!!).get().instructor)

        // 검색 어휘는 강의에서 파생한다
        val vocabulary =
            lectureVocabularyRepository.findVocabulary(
                2026,
                Semester.AUTUMN,
                com.wafflestudio.snutt.core.common.client.Language.KO,
            )
        assertTrue(vocabulary.department.contains("컴퓨터공학부"))
        assertTrue(vocabulary.credit.contains(3))
        assertTrue(vocabulary.instructor.contains("김컴퓨터"))
    }

    @Test
    fun `변경된 강의는 업데이트되고 사용자에게 알림이 간다`() {
        val user =
            userRepository.save(
                User(
                    email = "sync@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "syncuser",
                    localId = "syncuser",
                ),
            )
        val oldLecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "4190.204",
                    lectureNumber = "001",
                    courseTitle = "컴퓨터과학입문",
                    instructor = "김컴퓨터",
                    classification = "전선",
                    credit = 3,
                    quota = 40,
                ),
            )
        val timetable =
            timetableRepository.save(
                com.wafflestudio.snutt.core.domain.timetable.model.Timetable(
                    userId = user.id!!,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    title = "나의 시간표",
                    theme = com.wafflestudio.snutt.core.common.enums.BasicThemeType.FALL,
                ),
            )
        timetableLectureRepository.save(
            com.wafflestudio.snutt.core.domain.timetable.model
                .TimetableLecture(timetableId = timetable.id!!, lectureId = oldLecture.id),
        )

        // 제목/교수가 바뀐 xlsx
        val xlsx =
            SugangXlsxFixture.xlsx(
                listOf(
                    SugangXlsxFixture.RowData(
                        courseNumber = "4190.204",
                        lectureNumber = "001",
                        courseTitle = "컴퓨터과학입문(바뀜)",
                        instructor = "박컴퓨터",
                    ),
                ),
            )
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "ko")
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "en")

        assertEquals(BatchStatus.COMPLETED, runJob())

        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        assertEquals(1, lectures.size)
        assertEquals("컴퓨터과학입문(바뀜)", lectures[0].courseTitle)
        assertEquals("박컴퓨터", lectures[0].instructor)

        // 변경 알림 (LECTURE_UPDATE)이 사용자에게 저장된다
        val notifications = notificationRepository.findAll()
        assertTrue(notifications.isNotEmpty())
        assertEquals(user.id, notifications[0].userId)
    }
}
