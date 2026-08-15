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
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.ZoneId
import java.time.ZonedDateTime

// 빈자리 알림 잡: 만석이었다가 정원 미만이 된 강의의 구독자에게 FCM + 알림함 저장
@Configuration
class VacancyNotificationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val lectureRepository: LectureRepository,
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val pushService: PushService,
    private val coursebookService: CoursebookService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
) {
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
    ): Step {
        val log = LoggerFactory.getLogger(javaClass)
        return StepBuilder("vacancyNotificationStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val coursebook = coursebookService.getLatestCoursebook()
                    val targetYear = year ?: coursebook.year
                    val targetSemester = semester?.let(Semester::getOfValue) ?: coursebook.semester
                    // 빈자리 조회가 열려 있는 시간대에만 발송한다
                    val phase = currentRegistrationPhase(targetYear, targetSemester)
                    if (phase == null) {
                        log.info("빈자리 조회 시간대가 아니므로 건너뛴다: {} {}", targetYear, targetSemester)
                        return@tasklet RepeatStatus.FINISHED
                    }
                    // sync가 반영한 registration_count 기준으로 만석 해제를 감지한다
                    val vacated =
                        lectureRepository
                            .findByYearAndSemesterAndWasFullTrue(targetYear, targetSemester)
                            .filter { it.registrationCount < it.effectiveQuota(phase) }
                    vacated.forEach { lecture ->
                        val userIds = vacancyNotificationRepository.findByLectureId(lecture.id!!).map { it.userId }
                        pushService.sendPushAndNotification(
                            userIds = userIds,
                            title = "빈자리 알림",
                            body = "'${lecture.courseTitle}' 강의에 빈자리가 생겼습니다.",
                            type = NotificationType.LECTURE_VACANCY,
                            preferenceType = PushPreferenceType.VACANCY_NOTIFICATION,
                            urlScheme = "snutt://timetable",
                        )
                        lecture.wasFull = false
                    }
                    log.info("빈자리 알림 발송: {}건", vacated.size)
                    RepeatStatus.FINISHED
                },
                transactionManager,
            ).build()
    }

    /**
     * 지금이 빈자리 조회가 열린 시간대이면 그 시점의 수강신청 단계를 준다. 아니면 null.
     * 수강신청 일정은 날짜별로 여러 개의 조회 시간대를 가진다.
     */
    private fun currentRegistrationPhase(
        year: Int,
        semester: Semester,
    ): RegistrationPhase? {
        val periods =
            semesterRegistrationPeriodService.getByYearAndSemester(year, semester)?.registrationPeriodList
                ?: return null
        val now = ZonedDateTime.now(KST)
        val currentMinute = now.hour * 60 + now.minute
        return periods.firstOrNull { it.date == now.toLocalDate() && it.isOpenAt(currentMinute) }?.phase
    }

    private fun RegistrationDate.isOpenAt(minute: Int): Boolean =
        vacantSeatRegistrationTimes.any { minute >= it.startMinute && minute < it.endMinute }

    // 1학기 재학생 선착순 기간에는 신입생 몫이 정원에서 빠져 있어 그만큼이 만석 기준이다
    private fun Lecture.effectiveQuota(phase: RegistrationPhase): Int =
        if (semester == Semester.SPRING && phase == RegistrationPhase.CURRENT_STUDENT) {
            quota - (freshmanQuota ?: 0)
        } else {
            quota
        }

    companion object {
        const val JOB_NAME = "vacancyNotificationJob"
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
