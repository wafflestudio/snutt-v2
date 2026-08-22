package com.wafflestudio.snutt.batch.vacancy

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZoneId
import java.time.ZonedDateTime

@Configuration
class VacancyNotificationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val lectureRepository: LectureRepository,
    private val lectureRegistrationStatusRepository: LectureRegistrationStatusRepository,
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val pushService: PushService,
    private val coursebookService: CoursebookService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val crawler: SugangSnuRegistrationStatusCrawler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun vacancyNotificationJob(): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(vacancyNotificationStep(null, null))
            .build()

    @Bean
    @JobScope
    fun vacancyNotificationStep(
        @Value("#{jobParameters[year]}") year: Int?,
        @Value("#{jobParameters[semester]}") semester: Int?,
    ): Step =
        StepBuilder("vacancyNotificationStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val coursebook = coursebookService.getLatestCoursebook()
                    val targetYear = year ?: coursebook.year
                    val targetSemester = semester?.let(Semester::getOfValue) ?: coursebook.semester
                    runOnce(targetYear, targetSemester)
                    RepeatStatus.FINISHED
                },
                ResourcelessTransactionManager(),
            ).build()

    private fun runOnce(
        year: Int,
        semester: Semester,
    ) {
        val window = currentRegistrationWindow(year, semester)
        if (window == null) {
            log.info("빈자리 조회 시간대가 아니므로 건너뛴다: {} {}", year, semester)
            return
        }
        val pageCount =
            runCatching { crawler.getPageCount(year, semester) }
                .getOrElse {
                    log.error("수강스누 조회 실패(부하 기간 가능성): {}", it.message)
                    return
                }
        val lectureMap =
            lectureRepository
                .findByYearAndSemester(year, semester)
                .associateBy { it.courseNumber + "##" + it.lectureNumber }
        val storedStatuses =
            lectureRegistrationStatusRepository.findByYearAndSemester(year, semester).associateBy { it.lectureId }

        (1..pageCount).chunked(maxOf(1, pageCount / 20)).forEach { pages ->
            val statuses =
                runCatching { crawler.getRegistrationStatus(year, semester, pages) }
                    .getOrElse {
                        log.error("수강스누 페이지 크롤링 실패, 해당 청크는 건너뛴다: {}", it.message)
                        return@forEach
                    }
            if (statuses.all { it.registrationCount == 0 }) {
                log.info("수강신청이 시작되지 않아 중단한다")
                return
            }
            TransactionTemplate(transactionManager).executeWithoutResult {
                processChunk(lectureMap, storedStatuses, statuses, window)
            }
            Thread.sleep(DELAY_PER_CHUNK_MS)
        }
    }

    private fun processChunk(
        lectureMap: Map<String, Lecture>,
        storedStatuses: Map<Long, LectureRegistrationStatus>,
        crawled: List<RegistrationStatus>,
        window: RegistrationWindow,
    ) {
        val rows =
            crawled.mapNotNull { status ->
                val lecture = lectureMap[status.courseNumber + "##" + status.lectureNumber] ?: return@mapNotNull null
                val stored = storedStatuses[lecture.id] ?: return@mapNotNull null
                Triple(lecture, stored, status)
            }
        val notiTargets =
            rows
                .filter { (lecture, stored, _) -> stored.registrationCount == lecture.effectiveQuota(window.phase) }
                .filter { (_, _, status) -> status.wasFull }
                .filter { (_, stored, status) -> stored.registrationCount > status.registrationCount }
                .map { (lecture, _, _) -> lecture }

        val updated =
            rows
                .filter { (_, stored, status) ->
                    stored.registrationCount != status.registrationCount || stored.wasFull != status.wasFull
                }.map { (_, stored, status) ->
                    stored.apply {
                        registrationCount = status.registrationCount
                        wasFull = status.wasFull
                    }
                }
        lectureRegistrationStatusRepository.saveAll(updated)

        val targetTimeString = window.nextOpenTimeString()
        notiTargets.forEach { lecture ->
            val userIds = vacancyNotificationRepository.findByLectureId(lecture.id!!).map { it.userId }
            log.info("빈자리 감지: {} ({}-{})", lecture.courseTitle, lecture.courseNumber, lecture.lectureNumber)
            pushService.sendPushAndNotification(
                userIds = userIds,
                title = "빈자리 알림",
                body =
                    "\"${lecture.courseTitle} (${lecture.lectureNumber})\" 강의에 빈자리가 생겼습니다.\n" +
                        "${targetTimeString}에 수강신청 사이트를 확인해보세요!",
                type = NotificationType.LECTURE_VACANCY,
                preferenceType = PushPreferenceType.VACANCY_NOTIFICATION,
                urlScheme = "snutt://vacancy",
            )
        }
    }

    private data class RegistrationWindow(
        val phase: RegistrationPhase,
        val vacantSeatRegistrationTimes: List<RegistrationTimeSlot>,
    ) {
        fun nextOpenTimeString(): String {
            val now = ZonedDateTime.now(KST)
            val currentMinute = now.hour * 60 + now.minute
            // 아직 시작하지 않은 가장 가까운 슬롯의 시작 시각을 안내한다(이미 지난 슬롯 표기 방지)
            vacantSeatRegistrationTimes
                .filter { it.startMinute > currentMinute }
                .minOfOrNull { it.startMinute }
                ?.let { return "%02d:%02d".format(it / 60, it % 60) }
            return "다음 수강신청 일자"
        }
    }

    private fun currentRegistrationWindow(
        year: Int,
        semester: Semester,
    ): RegistrationWindow? {
        val periods =
            semesterRegistrationPeriodService.getByYearAndSemester(year, semester)?.registrationPeriodList
                ?: return null
        val now = ZonedDateTime.now(KST)
        val currentMinute = now.hour * 60 + now.minute
        return periods
            .firstOrNull { it.date == now.toLocalDate() && it.isOpenAt(currentMinute) }
            ?.let { RegistrationWindow(it.phase, it.vacantSeatRegistrationTimes) }
    }

    private fun RegistrationDate.isOpenAt(minute: Int): Boolean =
        vacantSeatRegistrationTimes.any { minute >= it.startMinute && minute < it.endMinute }

    // 1학기 재학생 선착순 기간에는 신입생 몫이 정원에서 빠져 있다
    private fun Lecture.effectiveQuota(phase: RegistrationPhase): Int =
        if (semester == Semester.SPRING && phase == RegistrationPhase.CURRENT_STUDENT) {
            quota - (freshmanQuota ?: 0)
        } else {
            quota
        }

    companion object {
        const val JOB_NAME = "vacancyNotificationJob"
        private const val DELAY_PER_CHUNK_MS = 300L
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
