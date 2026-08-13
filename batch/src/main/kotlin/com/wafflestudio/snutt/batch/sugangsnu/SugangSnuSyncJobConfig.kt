package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
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

// 수강스누 sync 잡: 최신 coursebook의 (year, semester) xlsx를 내려받아 lecture 3계층을 upsert
@Configuration
class SugangSnuSyncJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val sugangSnuLectureApi: SugangSnuLectureApi,
    private val sugangSnuXlsxParser: SugangSnuXlsxParser,
    private val sugangSnuLectureEnricher: SugangSnuLectureEnricher,
    private val sugangSnuSyncService: SugangSnuSyncService,
    private val coursebookService: CoursebookService,
) {
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
    ): Step {
        val log = LoggerFactory.getLogger(javaClass)
        return StepBuilder("sugangSnuMigrationStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val coursebook = coursebookService.getLatestCoursebook()
                    val targetYear = year ?: coursebook.year
                    val targetSemester = semester?.let(Semester::getOfValue) ?: coursebook.semester
                    val xlsx = sugangSnuLectureApi.downloadLectureXlsx(targetYear, targetSemester, "ko")
                    val englishXlsx = sugangSnuLectureApi.downloadLectureXlsx(targetYear, targetSemester, "en")
                    val englishRows = sugangSnuXlsxParser.parseEnglish(englishXlsx)
                    val rows =
                        sugangSnuXlsxParser
                            .parse(xlsx)
                            .mapIndexed { index, row ->
                                val en = englishRows.getOrNull(index)
                                row.copy(
                                    courseTitleEn = en?.courseTitleEn,
                                    instructorEn = en?.instructorEn,
                                    departmentEn = en?.departmentEn,
                                    academicYearEn = en?.academicYearEn,
                                    classificationEn = en?.classificationEn,
                                    remarkEn = en?.remarkEn,
                                )
                            }.map { sugangSnuLectureEnricher.enrich(targetYear, targetSemester, it) }
                    val result = sugangSnuSyncService.sync(targetYear, targetSemester, rows)
                    log.info("sugang sync 완료: {}", result)
                    RepeatStatus.FINISHED
                },
                transactionManager,
            ).build()
    }

    companion object {
        const val JOB_NAME = "sugangSnuMigrationJob"
    }
}
