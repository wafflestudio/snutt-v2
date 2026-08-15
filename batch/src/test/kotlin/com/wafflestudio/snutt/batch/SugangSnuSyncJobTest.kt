package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.sugangsnu.LectureBuildingSync
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuCoursebookCondition
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureEnricher
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuXlsxParser
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
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
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
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
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

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
    lateinit var timetableRepository: TimetableRepository

    @Autowired
    lateinit var timetableLectureRepository: TimetableLectureRepository

    @MockitoBean
    lateinit var sugangSnuLectureApi: SugangSnuLectureApi

    @MockitoBean
    lateinit var sugangSnuLectureEnricher: SugangSnuLectureEnricher

    @MockitoBean
    lateinit var lectureBuildingSync: LectureBuildingSync

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

    private fun stubCurrentCoursebook() {
        Mockito
            .doReturn(
                SugangSnuCoursebookCondition(2026, "U000200002", "U000300001"),
            ).`when`(sugangSnuLectureApi)
            .getCoursebookCondition()
    }

    private fun runJob(): BatchStatus =
        jobLauncher
            .run(
                jobRegistry.getJob("sugangSnuMigrationJob"),
                JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters(),
            ).status

    @Autowired
    lateinit var sugangSnuXlsxParser: SugangSnuXlsxParser

    @Test
    fun `xlsx 파싱과 신규 강의 upsert`() {
        val xlsx =
            SugangXlsxFixture.xlsx(
                listOf(
                    SugangXlsxFixture.RowData(
                        courseNumber = "400.320",
                        lectureNumber = "002",
                        courseTitle = "공학연구의 실습 1",
                    ),
                    SugangXlsxFixture.RowData(
                        classification = "전필",
                        department = "언론정보학과(연합전공 정보문화학)",
                        academicYear = "4학년",
                        courseNumber = "2114.408A",
                        lectureNumber = "001",
                        courseTitle = "HCI이론 및 실습",
                        credit = 3,
                        classTime = "화(14:00~16:50)",
                        place = "83-601",
                        instructor = "임하진",
                        quota = 25,
                    ),
                ),
            )
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "ko")
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "en")
        stubCurrentCoursebook()
        assertEquals(2, sugangSnuXlsxParser.parse(xlsx).size)

        assertEquals(BatchStatus.COMPLETED, runJob())

        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        assertEquals(2, lectures.size)
        val first = lectures.first { it.courseNumber == "400.320" }
        assertEquals("공학연구의 실습 1", first.courseTitle)
        assertEquals("3학년", first.academicYear)
        val firstTimes = lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(first.id!!)).map { it.toClassPlaceAndTime() }
        assertEquals(listOf(DayOfWeek.FRIDAY), firstTimes.map { it.day })
        assertEquals(1140, firstTimes.first().startMinute)
        assertEquals(1250, firstTimes.first().endMinute)
        assertTrue(first.courseId != null)
        assertEquals("이제희", courseRepository.findById(first.courseId!!).get().instructor)

        val vocabulary =
            lectureVocabularyRepository.findVocabulary(
                2026,
                Semester.AUTUMN,
                Language.KO,
            )
        assertTrue(vocabulary.department.contains("컴퓨터공학부"))
        assertTrue(vocabulary.credit.contains(3))
        assertTrue(vocabulary.instructor.contains("이제희"))
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
                ),
            )
        val timetable =
            timetableRepository.save(
                Timetable(
                    userId = user.id!!,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    title = "나의 시간표",
                    theme = BasicThemeType.FALL,
                ),
            )
        timetableLectureRepository.save(
            TimetableLecture(timetableId = timetable.id!!, lectureId = oldLecture.id),
        )

        val xlsx =
            SugangXlsxFixture.xlsx(
                listOf(
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
                    ),
                ),
            )
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "ko")
        Mockito.doReturn(xlsx).`when`(sugangSnuLectureApi).downloadLectureXlsx(2026, Semester.AUTUMN, "en")
        stubCurrentCoursebook()

        assertEquals(BatchStatus.COMPLETED, runJob())

        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        assertEquals(1, lectures.size)
        assertEquals("고급한국어", lectures[0].courseTitle)
        val times = lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(lectures[0].id!!)).map { it.toClassPlaceAndTime() }
        assertEquals(listOf(DayOfWeek.MONDAY), times.map { it.day })
        assertEquals(570, times.first().startMinute)

        val notifications = notificationRepository.findAll()
        assertTrue(notifications.isNotEmpty())
        assertEquals(user.id, notifications[0].userId)
    }
}
