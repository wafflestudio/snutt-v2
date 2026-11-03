package com.wafflestudio.snutt.batch.vacancy

import com.wafflestudio.snutt.batch.BatchJob
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.model.localizedCourseTitle
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

@Component
class VacancyNotificationJob(
    private val transactionManager: PlatformTransactionManager,
    private val lectureRepository: LectureRepository,
    private val lectureRegistrationStatusRepository: LectureRegistrationStatusRepository,
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val pushService: PushService,
    private val coursebookService: CoursebookService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val crawler: SugangSnuRegistrationStatusCrawler,
    private val clock: Clock,
) : BatchJob {
    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "vacancyNotification"

    override fun run(target: YearAndSemester?) {
        val (year, semester) = target ?: coursebookService.getLatestCoursebook().let { YearAndSemester(it.year, it.semester) }
        while (true) {
            val window = currentRegistrationWindow(year, semester)
            if (window == null) {
                log.info("빈자리 조회 대상 등록일이 아니거나 18시 이후이므로 종료한다: {} {}", year, semester)
                return
            }
            if (!pollOnce(year, semester, window)) return
        }
    }

    private fun pollOnce(
        year: Int,
        semester: Semester,
        window: RegistrationWindow,
    ): Boolean {
        val pageCount =
            runCatching { crawler.getPageCount(year, semester) }
                .getOrElse {
                    log.error("수강스누 조회 실패(부하 기간 가능성), 잠시 뒤 다시 조회한다: {}", it.message)
                    Thread.sleep(OVERLOAD_RETRY_DELAY_MS)
                    return true
                }
        if (pageCount == 0) {
            log.error("수강스누 페이지 수가 0이다, 잠시 뒤 다시 조회한다")
            Thread.sleep(OVERLOAD_RETRY_DELAY_MS)
            return true
        }
        val lectureMap =
            lectureRepository
                .findByYearAndSemester(year, semester)
                .associateBy { it.courseNumber + "##" + it.lectureNumber }
        val storedStatuses =
            lectureRegistrationStatusRepository.findByYearAndSemester(year, semester).associateBy { it.lectureId }

        var processedChunks = 0
        (1..pageCount).chunked(maxOf(1, pageCount / 20)).forEach { pages ->
            val statuses =
                runCatching { crawler.getRegistrationStatus(year, semester, pages) }
                    .getOrElse {
                        log.error("수강스누 페이지 크롤링 실패, 해당 청크는 건너뛴다: {}", it.message)
                        return@forEach
                    }
            processedChunks++
            if (statuses.all { it.registrationCount == 0 }) {
                log.info("수강신청이 시작되지 않아 종료한다")
                return false
            }
            val pendingPushes =
                TransactionTemplate(transactionManager)
                    .execute { processChunk(lectureMap, storedStatuses, statuses, window) }
                    .orEmpty()
            pendingPushes.forEach { push ->
                pushService.sendPushAndNotification(
                    userIds = push.userIds,
                    message = push.message,
                    type = NotificationType.LECTURE_VACANCY,
                    preferenceType = PushPreferenceType.VACANCY_NOTIFICATION,
                )
            }
            Thread.sleep(DELAY_PER_CHUNK_MS)
        }
        if (processedChunks == 0) {
            log.error("수강스누 페이지를 하나도 읽지 못했다, 잠시 뒤 다시 조회한다")
            Thread.sleep(OVERLOAD_RETRY_DELAY_MS)
        }
        return true
    }

    private data class PendingVacancyPush(
        val userIds: List<Long>,
        val message: LocalizedPushMessage,
    )

    private fun processChunk(
        lectureMap: Map<String, Lecture>,
        storedStatuses: Map<Long, LectureRegistrationStatus>,
        crawled: List<RegistrationStatus>,
        window: RegistrationWindow,
    ): List<PendingVacancyPush> {
        val updates = mutableListOf<LectureRegistrationStatus>()
        val notiTargets = mutableListOf<Lecture>()
        crawled.forEach { status ->
            val lecture = lectureMap[status.courseNumber + "##" + status.lectureNumber] ?: return@forEach
            val stored = storedStatuses[lecture.id]
            if (stored == null) {
                updates +=
                    LectureRegistrationStatus(
                        lectureId = lecture.id!!,
                        registrationCount = status.registrationCount,
                        wasFull = status.wasFull,
                    )
                return@forEach
            }
            if (becameVacant(lecture, stored, status, window.phase)) notiTargets += lecture
            if (stored.registrationCount != status.registrationCount || stored.wasFull != status.wasFull) {
                stored.registrationCount = status.registrationCount
                stored.wasFull = status.wasFull
                updates += stored
            }
        }
        lectureRegistrationStatusRepository.saveAll(updates)

        val targetTime = window.nextOpenTime(ZonedDateTime.now(clock).withZoneSameInstant(KST))
        return notiTargets.map { lecture ->
            val userIds = vacancyNotificationRepository.findByLectureId(lecture.id!!).map { it.userId }
            log.info("빈자리 감지: {} ({}-{})", lecture.courseTitle, lecture.courseNumber, lecture.lectureNumber)
            PendingVacancyPush(
                userIds = userIds,
                message =
                    NotificationTemplate.LECTURE_VACANCY
                        .render(lecture.localizedCourseTitle, lecture.lectureNumber, targetTime)
                        .copy(urlScheme = "snutt://vacancy", isUrgentOnAndroid = true),
            )
        }
    }

    private data class RegistrationWindow(
        val phase: RegistrationPhase,
        val vacantSeatRegistrationTimes: List<RegistrationTimeSlot>,
    ) {
        fun nextOpenTime(now: ZonedDateTime): LocalizedMessage {
            val currentMinute = now.hour * 60 + now.minute
            val startMinute =
                vacantSeatRegistrationTimes
                    .filter { it.endMinute > currentMinute }
                    .minOfOrNull { it.startMinute }
                    ?: return LocalizedMessage(ko = "다음 수강신청 일자", en = "the next registration date")
            return LocalizedMessage.same("%02d:%02d".format(startMinute / 60, startMinute % 60))
        }
    }

    private fun currentRegistrationWindow(
        year: Int,
        semester: Semester,
    ): RegistrationWindow? {
        val periods =
            semesterRegistrationPeriodService.getByYearAndSemester(year, semester)?.registrationPeriodList
                ?: return null
        val now = ZonedDateTime.now(clock).withZoneSameInstant(KST)
        if (now.hour >= 18) return null
        return periods
            .firstOrNull { it.date == now.toLocalDate() }
            ?.let { RegistrationWindow(it.phase, it.vacantSeatRegistrationTimes) }
    }

    private fun becameVacant(
        lecture: Lecture,
        stored: LectureRegistrationStatus,
        crawled: RegistrationStatus,
        phase: RegistrationPhase,
    ): Boolean =
        stored.registrationCount == lecture.effectiveQuota(phase) &&
            crawled.wasFull &&
            stored.registrationCount > crawled.registrationCount

    private fun Lecture.effectiveQuota(phase: RegistrationPhase): Int =
        if (semester == Semester.SPRING && phase == RegistrationPhase.CURRENT_STUDENT) {
            quota - (freshmanQuota ?: 0)
        } else {
            quota
        }

    companion object {
        private const val DELAY_PER_CHUNK_MS = 300L
        private const val OVERLOAD_RETRY_DELAY_MS = 30_000L
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
