package com.wafflestudio.snutt.batch

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

// k8s CronJob이 JOB_NAME 환경변수로 실행할 잡을 고른다
// 잡 등록은 Boot의 JobRegistrySmartInitializingSingleton이 담당한다 (Spring Batch 6).
// spring.batch.job.enabled=false면 Boot가 JobRegistry를 만들지 않으므로 직접 정의한다
@Configuration
class BatchJobConfig {
    @Bean
    fun jobRegistry(): org.springframework.batch.core.configuration.JobRegistry =
        org.springframework.batch.core.configuration.support
            .MapJobRegistry()
}

@Component
class JobRunner(
    private val jobLauncher: JobLauncher,
    private val jobRegistry: org.springframework.batch.core.configuration.JobRegistry,
    @Value("\${job.name:}") private val jobName: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (jobName.isBlank()) {
            println("JOB_NAME이 비어 있어 실행할 잡이 없습니다")
            return
        }
        val job = jobRegistry.getJob(jobName)
        // 매 실행을 새 JobInstance로 만든다. 동일 파라미터 재실행은 JobInstanceAlreadyCompleteException으로 막힌다
        val params = JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters()
        val execution = jobLauncher.run(job, params)
        exitProcess(if (execution.status == BatchStatus.COMPLETED) 0 else 1)
    }
}
