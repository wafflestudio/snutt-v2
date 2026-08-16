package com.wafflestudio.snutt.batch

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.configuration.JobRegistry
import org.springframework.batch.core.configuration.support.MapJobRegistry
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Configuration
class BatchJobConfig {
    @Bean
    fun jobRegistry(): JobRegistry = MapJobRegistry()
}

@Component
class JobRunner(
    private val jobOperator: JobOperator,
    private val jobRegistry: JobRegistry,
    @param:Value("\${job.name:}") private val jobName: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (jobName.isBlank()) {
            println("JOB_NAME이 비어 있어 실행할 잡이 없습니다")
            return
        }
        val job = requireNotNull(jobRegistry.getJob(jobName)) { "등록되지 않은 잡: $jobName" }
        // 매 실행을 새 JobInstance로 만든다. 동일 파라미터 재실행은 JobInstanceAlreadyCompleteException으로 막힌다
        val params = JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters()
        val execution = jobOperator.start(job, params)
        exitProcess(if (execution.status == BatchStatus.COMPLETED) 0 else 1)
    }
}
