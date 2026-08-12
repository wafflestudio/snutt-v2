package com.wafflestudio.snutt.batch.timetables

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
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

// 대표 시간표 자동 지정: (year, semester)에 대표 시간표가 없는 사용자에게 가장 최근 시간표를 지정 (v1 이식)
@Configuration
class AutoPrimaryJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val timetableRepository: TimetableRepository,
) {
    @Bean
    fun primaryTimetableAutoSetJob(): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(primaryTimetableAutoSetStep(null, null))
            .build()

    @Bean
    @JobScope
    fun primaryTimetableAutoSetStep(
        @Value("#{jobParameters[year]}") year: Int?,
        @Value("#{jobParameters[semester]}") semester: Int?,
    ): Step {
        val log = LoggerFactory.getLogger(javaClass)
        return StepBuilder("primaryTimetableAutoSetStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val targetYear = year ?: 2026
                    val targetSemester = semester?.let(Semester::getOfValue) ?: Semester.AUTUMN
                    val timetables = timetableRepository.findByYearAndSemester(targetYear, targetSemester)
                    val userIdsWithPrimary =
                        timetableRepository
                            .findByYearAndSemesterAndIsPrimaryTrue(targetYear, targetSemester)
                            .map { it.userId }
                            .toSet()
                    val assigned =
                        timetables
                            .filter { it.userId !in userIdsWithPrimary }
                            .groupBy { it.userId }
                            .map { (_, userTimetables) -> userTimetables.maxBy { it.updatedAt ?: java.time.Instant.EPOCH } }
                    assigned.forEach { it.isPrimary = true }
                    log.info("대표 시간표 자동 지정: {}명", assigned.size)
                    RepeatStatus.FINISHED
                },
                transactionManager,
            ).build()
    }

    companion object {
        const val JOB_NAME = "primaryTimetableAutoSetJob"
    }
}
