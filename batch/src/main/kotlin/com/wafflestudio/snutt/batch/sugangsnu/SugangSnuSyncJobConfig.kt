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
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SugangSnuSyncJobConfig(
    private val jobRepository: JobRepository,
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
                    val semesterValue =
                        semester?.let {
                            Semester.getOfValue(it) ?: throw IllegalStateException("잘못된 semester 파라미터: $it")
                        }
                    when {
                        year == null && semesterValue == null -> run()
                        year != null && semesterValue != null -> syncSemester(year, semesterValue)
                        else -> throw IllegalStateException("year와 semester 파라미터는 함께 지정해야 한다")
                    }
                    RepeatStatus.FINISHED
                },
                ResourcelessTransactionManager(),
            ).build()

    private fun run() {
        val condition = sugangSnuLectureApi.getCoursebookCondition()
        val latest = coursebookService.findLatestCoursebook()
        if (latest == null) {
            // 최초 실행: DB에 수강편람이 없으면 수강사이트 기준으로 첫 편람을 만들고 동기화한다
            log.info("첫 수강편람 생성: {} {}", condition.latestYear, condition.latestSemester)
            coursebookRepository.save(Coursebook(year = condition.latestYear, semester = condition.latestSemester))
            extractRegistrationPeriod(condition.latestYear, condition.latestSemester)
            syncSemester(condition.latestYear, condition.latestSemester)
            // 사용자가 없으므로 신규 편람 푸시는 생략한다
            return
        }
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
        val baseRows =
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
                }

        val rows = baseRows.map { enrichWithRetry(year, semester, it) }
        val result = sugangSnuSyncService.sync(year, semester, rows)
        log.info("sugang sync 완료: {}", result)
    }

    private fun enrichWithRetry(
        year: Int,
        semester: Semester,
        row: SugangLectureRow,
    ): SugangLectureRow {
        repeat(ENRICH_RETRY_COUNT) { attempt ->
            runCatching { return sugangSnuLectureEnricher.enrich(year, semester, row) }
                .onFailure { Thread.sleep(ENRICH_RETRY_BACKOFF_MS * (attempt + 1)) }
        }
        return sugangSnuLectureEnricher.enrich(year, semester, row)
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
        private const val ENRICH_RETRY_COUNT = 2
        private const val ENRICH_RETRY_BACKOFF_MS = 500L
    }
}
