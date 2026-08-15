package com.wafflestudio.snutt.batch.vacancy

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
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

// 빈자리 알림 잡: 수강스누 검색 페이지를 실시간 크롤링해 만석 강의의 재안인원 감소를 감지하고
// 구독자에게 FCM + 알림함 저장 (v1 VacancyNotifierService 이식)
@Configuration
class VacancyNotificationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val lectureRepository: LectureRepository,
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
                // 크롤링이 오래 걸리므로 트랜잭션은 chunk 처리 시에만 연다
                ResourcelessTransactionManager(),
            ).build()

    private fun runOnce(
        year: Int,
        semester: Semester,
    ) {
        // 빈자리 조회가 열려 있는 시간대에만 크롤링한다
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

        // 수강 사이트 부하를 분산하기 위해 전체 페이지를 20등분해서 요청한다 (v1 동일)
        (1..pageCount).chunked(maxOf(1, pageCount / 20)).forEach { pages ->
            val statuses =
                runCatching { crawler.getRegistrationStatus(year, semester, pages) }
                    .getOrElse {
                        log.error("수강스누 페이지 크롤링 실패: {}", it.message)
                        return
                    }
            if (statuses.all { it.registrationCount == 0 }) {
                log.info("수강신청이 시작되지 않아 중단한다")
                return
            }
            TransactionTemplate(transactionManager).executeWithoutResult {
                processChunk(year, semester, lectureMap, statuses, window)
            }
            Thread.sleep(DELAY_PER_CHUNK_MS)
        }
    }

    private fun processChunk(
        year: Int,
        semester: Semester,
        lectureMap: Map<String, Lecture>,
        statuses: List<RegistrationStatus>,
        window: RegistrationWindow,
    ) {
        val pairs =
            statuses.mapNotNull { status ->
                lectureMap[status.courseNumber + "##" + status.lectureNumber]?.let { it to status }
            }
        val notiTargets =
            pairs
                .filter { (lecture, _) -> lecture.isFull(window.phase) }
                .filter { (_, status) -> status.wasFull }
                .filter { (lecture, status) -> lecture.registrationCount > status.registrationCount }
                .map { (lecture, _) -> lecture }

        val updated =
            pairs
                .filter { (lecture, status) ->
                    lecture.registrationCount != status.registrationCount || lecture.wasFull != status.wasFull
                }.map { (lecture, status) ->
                    lecture.apply {
                        registrationCount = status.registrationCount
                        wasFull = status.wasFull
                    }
                }
        lectureRepository.saveAll(updated)

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
            return vacantSeatRegistrationTimes
                .filter { currentMinute < it.endMinute }
                .minOfOrNull { it.startMinute }
                ?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "다음 수강신청 일자"
        }
    }

    // 지금이 빈자리 조회가 열린 시간대이면 그 단계와 당일 조회 시간대들을 준다. 아니면 null
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

    // 1학기 재학생 선착순 기간에는 신입생 몫이 정원에서 빠져 있어 그만큼이 만석 기준이다 (v1 isFull)
    private fun Lecture.isFull(phase: RegistrationPhase): Boolean =
        if (semester == Semester.SPRING && phase == RegistrationPhase.CURRENT_STUDENT) {
            quota - (freshmanQuota ?: 0) == registrationCount
        } else {
            quota == registrationCount
        }

    companion object {
        const val JOB_NAME = "vacancyNotificationJob"
        private const val DELAY_PER_CHUNK_MS = 300L
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
