package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.enums.Semester
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock

@Configuration
class BatchConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@Component
class JobRunner(
    jobs: List<BatchJob>,
    @param:Value("\${job.name:}") private val jobName: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobsByName = jobs.associateBy { it.name }

    override fun run(args: ApplicationArguments) {
        if (jobName.isBlank()) {
            log.info("JOB_NAME이 비어 있어 실행할 잡이 없습니다. 사용 가능한 잡: {}", jobsByName.keys)
            return
        }
        val job = requireNotNull(jobsByName[jobName]) { "등록되지 않은 잡: $jobName (사용 가능: ${jobsByName.keys})" }
        val year = args.getOptionValues("year")?.single()?.toInt()
        val semester = args.getOptionValues("semester")?.single()?.let { Semester.fromValue(it.toInt()) }
        require((year == null) == (semester == null)) { "year와 semester는 함께 지정해야 한다" }
        job.run(YearSemesterArgs(year, semester))
    }
}
