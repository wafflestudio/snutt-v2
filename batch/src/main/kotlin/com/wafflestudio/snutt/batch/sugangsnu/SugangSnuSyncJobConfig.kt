package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
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

@Configuration
class SugangSnuSyncJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val sugangSnuLectureApi: SugangSnuLectureApi,
    private val sugangSnuXlsxParser: SugangSnuXlsxParser,
    private val sugangSnuLectureEnricher: SugangSnuLectureEnricher,
    private val sugangSnuSyncService: SugangSnuSyncService,
    private val registrationPeriodExtractor: RegistrationPeriodExtractor,
    private val coursebookService: CoursebookService,
    private val coursebookRepository: CoursebookRepository,
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val pushService: PushService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun sugangSnuMigrationJob(): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(sugangSnuMigrationStep(null, null))
            .build()

    @Bean
    @JobScope
    fun sugangSnuMigrationStep(
        @Value("#{jobParameters[year]}") year: Int?,
        @Value("#{jobParameters[semester]}") semester: Int?,
    ): Step =
        StepBuilder("sugangSnuMigrationStep", jobRepository)
            .tasklet(
                { _, _ ->
                    if (year != null || semester != null) {
                        val coursebook = coursebookService.getLatestCoursebook()
                        syncSemester(year ?: coursebook.year, semester?.let(Semester::getOfValue) ?: coursebook.semester)
                    } else {
                        run()
                    }
                    RepeatStatus.FINISHED
                },
                transactionManager,
            ).build()

    /**
     * 수강스누가 서비스하는 학기가 DB 최신 coursebook과 같으면 그 학기를 갱신하고,
     * 앞서 있으면 다음 학기를 새로 연다 (v1 syncSugangSnuStep 이식)
     */
    private fun run() {
        val latest = coursebookService.getLatestCoursebook()
        val condition = sugangSnuLectureApi.getCoursebookCondition()
        if (condition.latestYear == latest.year && condition.latestSemester == latest.semester) {
            extractRegistrationPeriod(latest.year, latest.semester)
            syncSemester(latest.year, latest.semester)
            coursebookRepository.touchUpdatedAt(latest.id!!)
        } else {
            val next = nextCoursebook(latest)
            log.info("신규 수강편람 감지: {} {}", next.year, next.semester)
            vacancyNotificationRepository.deleteAll()
            coursebookRepository.save(next)
            extractRegistrationPeriod(next.year, next.semester)
            syncSemester(next.year, next.semester)
            pushService.sendGlobalPushAndNotification(
                title = "신규 수강편람",
                body = "${next.year}년도 ${next.semester.fullName} 수강편람이 추가되었습니다.",
                type = NotificationType.COURSEBOOK,
            )
        }
    }

    // 일정 페이지 구조 변화가 강의 sync 전체를 막지 않도록 실패는 기록만 한다
    private fun extractRegistrationPeriod(
        year: Int,
        semester: Semester,
    ) {
        runCatching { registrationPeriodExtractor.extract(year, semester) }
            .onFailure { log.error("수강신청 일정 추출 실패: {}", it.message) }
    }

    private fun syncSemester(
        year: Int,
        semester: Semester,
    ) {
        val xlsx = sugangSnuLectureApi.downloadLectureXlsx(year, semester, "ko")
        val englishXlsx = sugangSnuLectureApi.downloadLectureXlsx(year, semester, "en")
        val englishByKey = sugangSnuXlsxParser.parseEnglish(englishXlsx)
        val rows =
            sugangSnuXlsxParser
                .parse(xlsx)
                .map { row ->
                    val en = englishByKey[row.courseNumber to row.lectureNumber]
                    row.copy(
                        courseTitleEn = en?.courseTitleEn,
                        instructorEn = en?.instructorEn,
                        departmentEn = en?.departmentEn,
                        academicYearEn = en?.academicYearEn,
                        classificationEn = en?.classificationEn,
                        remarkEn = en?.remarkEn,
                    )
                }.map { sugangSnuLectureEnricher.enrich(year, semester, it) }
        val result = sugangSnuSyncService.sync(year, semester, rows)
        log.info("sugang sync 완료: {}", result)
    }

    private fun nextCoursebook(coursebook: Coursebook): Coursebook =
        when (coursebook.semester) {
            Semester.SPRING -> Coursebook(year = coursebook.year, semester = Semester.SUMMER)
            Semester.SUMMER -> Coursebook(year = coursebook.year, semester = Semester.AUTUMN)
            Semester.AUTUMN -> Coursebook(year = coursebook.year, semester = Semester.WINTER)
            Semester.WINTER -> Coursebook(year = coursebook.year + 1, semester = Semester.SPRING)
        }

    companion object {
        const val JOB_NAME = "sugangSnuMigrationJob"
    }
}
