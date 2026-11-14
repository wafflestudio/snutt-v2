package com.wafflestudio.snutt.batch.timetables

import com.wafflestudio.snutt.batch.BatchJob
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AutoPrimaryJob(
    private val timetableRepository: TimetableRepository,
    private val coursebookService: CoursebookService,
) : BatchJob {
    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "primaryTimetableAutoSet"

    @Transactional
    override fun run(target: YearAndSemester?) {
        val (year, semester) = target ?: coursebookService.getLatestCoursebook().let { YearAndSemester(it.year, it.semester) }
        val assignedCount = timetableRepository.autoAssignPrimaryByYearAndSemester(year, semester.value)
        log.info("대표 시간표 자동 지정: {}명", assignedCount)
    }
}
