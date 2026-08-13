package com.wafflestudio.snutt.batch.vacancy

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
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

// 빈자리 알림 잡: 만석이었다가 정원 미만이 된 강의의 구독자에게 FCM + 알림함 저장 (PLAN.md §4)
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
                    // 빈자리 알림은 수강신청 기간 안에서만 발송한다
                    if (!isInRegistrationPeriod(targetYear, targetSemester)) {
                        log.info("수강신청 기간이 아니므로 빈자리 알림을 건너뛴다: {} {}", targetYear, targetSemester)
                        return@tasklet RepeatStatus.FINISHED
                    }
                    // sync가 반영한 registration_count 기준으로 만석 해제를 감지한다
                    val vacated =
                        lectureRepository
                            .findByYearAndSemesterAndWasFullTrue(targetYear, targetSemester)
                            .filter { it.registrationCount < it.quota }
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

    private fun isInRegistrationPeriod(
        year: Int,
        semester: Semester,
    ): Boolean {
        val periods =
            semesterRegistrationPeriodService.getByYearAndSemester(year, semester)?.registrationPeriodList
                ?: return false
        val now = System.currentTimeMillis()
        return periods.any { now in it.startAt..it.endAt }
    }

    companion object {
        const val JOB_NAME = "vacancyNotificationJob"
    }
}
