package com.wafflestudio.snutt.batch.sugang

import com.wafflestudio.snutt.batch.AbstractBatchIntegrationTest
import com.wafflestudio.snutt.batch.SugangFixtures
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.json
import com.wafflestudio.snutt.batch.SugangXlsxFixture
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuSyncJob
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired

/**
 * 수강편람 동기화 시나리오 테스트의 공용 도구. 컨텍스트에 영향을 주는 어노테이션은 달지 않는다(빈 mock·프로퍼티는 모듈 베이스에만).
 *
 * 수강편람 사이트는 WireMock으로 stub하고 잡([SugangSnuSyncJob])을 그대로 돌린다. 강좌 상세 API는 기본으로 빈 응답을 줘서
 * 엑셀 값이 그대로 반영되고, SNU 지도는 기본으로 검색 결과가 없다. 사용자의 시간표 조작은 실제 서비스를 거친다.
 */
abstract class SugangSyncScenarioTest : AbstractBatchIntegrationTest() {
    @Autowired lateinit var sugangSnuSyncJob: SugangSnuSyncJob

    @Autowired lateinit var coursebookRepository: CoursebookRepository

    @Autowired lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired lateinit var courseRepository: CourseRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var timetableRepository: TimetableRepository

    @Autowired lateinit var timetableLectureRepository: TimetableLectureRepository

    @Autowired lateinit var timetableLectureService: TimetableLectureService

    @Autowired lateinit var reminderService: TimetableLectureReminderService

    @Autowired lateinit var reminderRepository: TimetableLectureReminderRepository

    @Autowired lateinit var reminderScheduleRepository: TimetableLectureReminderScheduleRepository

    @Autowired lateinit var bookmarkService: BookmarkService

    @Autowired lateinit var userDeviceRepository: UserDeviceRepository

    /** 수강편람 사이트가 [year] [semester]를 현재 학기로 보여 주고, 엑셀에 [rows]가 있다. 영어 엑셀은 [englishRows]. */
    fun stubSite(
        rows: List<SugangXlsxFixture.RowData>,
        englishRows: List<SugangXlsxFixture.RowData> = rows,
        year: Int = 2026,
        semester: Semester = Semester.AUTUMN,
        mainPage: String = SugangFixtures.text("main-page.html"),
    ) {
        sugang.coursebookCondition(year, semester)
        sugang.lectureXlsx("ko", SugangXlsxFixture.xlsx(rows).byteArray)
        sugang.lectureXlsx("en", SugangXlsxFixture.englishXlsx(englishRows).byteArray)
        sugang.lectureInfoForAll()
        sugang.mainPage(mainPage)
        sugang.snuMapForAll(json("""{"search_list":[]}"""))
    }

    /** [stubSite] 후 잡을 학기 지정 없이(운영 스케줄과 같이) 한 번 돌린다. */
    fun sync(
        rows: List<SugangXlsxFixture.RowData>,
        englishRows: List<SugangXlsxFixture.RowData> = rows,
        year: Int = 2026,
        semester: Semester = Semester.AUTUMN,
    ) {
        stubSite(rows, englishRows, year, semester)
        sugangSnuSyncJob.run(null)
    }

    fun lecture(
        courseNumber: String,
        lectureNumber: String = "001",
        year: Int = 2026,
        semester: Semester = Semester.AUTUMN,
    ): Lecture =
        lectureRepository
            .findByYearAndSemester(year, semester)
            .single { it.courseNumber == courseNumber && it.lectureNumber == lectureNumber }

    fun lectureOrNull(
        courseNumber: String,
        lectureNumber: String = "001",
    ): Lecture? =
        lectureRepository
            .findByYearAndSemester(2026, Semester.AUTUMN)
            .singleOrNull { it.courseNumber == courseNumber && it.lectureNumber == lectureNumber }

    fun classTimes(lecture: Lecture): List<ClassPlaceAndTime> =
        lectureClassTimeRepository.findAllByLectureIdInOrderById(listOf(lecture.id!!)).map { it.toClassPlaceAndTime() }

    /** 사용자와 기기 하나. 푸시 대상이 되도록 FCM 토큰을 `fcm-<localId>`로 둔다. */
    fun userWithDevice(
        localId: String,
        language: Language = Language.KO,
    ): User =
        fixtures.user(localId).also { user ->
            userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-$localId", language = language))
        }

    /** 실제 서비스(시간 겹침 검사 포함)로 강의를 담는다. 담은 timetable_lecture id를 돌려준다. */
    fun addLecture(
        user: User,
        timetable: Timetable,
        lecture: Lecture,
    ): Long =
        timetableLectureService
            .addLecture(user.id!!, timetable.id!!, TimetableLectureAddRequest(lecture.id!!))
            .lectures
            .single { it.lectureId == lecture.id }
            .id

    fun setReminder(
        user: User,
        timetable: Timetable,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    ) {
        reminderService.modifyReminder(user.id!!, timetable.id!!, timetableLectureId, option)
    }

    /** 리마인더 발송 시각. 리마인더가 없으면 null. */
    fun reminderSchedules(timetableLectureId: Long): List<Schedule>? =
        reminderRepository
            .findByTimetableLectureId(timetableLectureId)
            ?.let { reminder -> reminderScheduleRepository.findByReminderId(reminder.id!!).map { it.toSchedule() }.sorted() }

    fun timetableLectureIds(timetable: Timetable): List<Long?> =
        timetableLectureRepository.findByTimetableIdIn(listOf(timetable.id!!)).sortedBy { it.id }.map { it.lectureId }

    fun notificationsOf(user: User): List<Notification> =
        notificationRepository.findAll().filter { it.userId == user.id }.sortedBy { it.id }

    /** 지금까지 FCM으로 보낸 개별 푸시. 단계마다 [clearPushes]로 비운다. */
    fun sentPushes(): List<TargetedPushMessage> {
        val captor = argumentCaptor<List<TargetedPushMessage>>()
        verify(pushClient, atLeast(0)).sendMessages(captor.capture())
        return captor.allValues.flatten()
    }

    fun clearPushes() = clearInvocations(pushClient)

    fun deeplink(
        path: String,
        query: String? = null,
    ): String = "snutt-dev://$path" + (query?.let { "?$it" } ?: "")
}
