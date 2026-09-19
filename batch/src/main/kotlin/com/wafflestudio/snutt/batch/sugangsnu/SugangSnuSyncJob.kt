package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.batch.BatchJob
import com.wafflestudio.snutt.batch.YearSemesterArgs
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SugangSnuSyncJob(
    private val sugangSnuLectureApi: SugangSnuLectureApi,
    private val sugangSnuXlsxParser: SugangSnuXlsxParser,
    private val sugangSnuLectureEnricher: SugangSnuLectureEnricher,
    private val sugangSnuSyncService: SugangSnuSyncService,
    private val registrationPeriodExtractor: RegistrationPeriodExtractor,
    private val coursebookService: CoursebookService,
    private val coursebookRepository: CoursebookRepository,
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val pushService: PushService,
) : BatchJob {
    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "sugangSnuSync"

    override fun run(args: YearSemesterArgs) {
        if (args.year != null && args.semester != null) {
            syncSemester(args.year, args.semester)
            return
        }
        val condition = sugangSnuLectureApi.getCoursebookCondition()
        val latest = coursebookService.findLatestCoursebook()
        when {
            latest == null -> {
                log.info("첫 수강편람 생성: {} {}", condition.latestYear, condition.latestSemester)
                syncCoursebook(coursebookRepository.save(Coursebook(condition.latestYear, condition.latestSemester)))
            }
            condition.latestYear == latest.year && condition.latestSemester == latest.semester -> {
                syncCoursebook(latest)
                coursebookRepository.touchUpdatedAt(latest.id!!)
            }
            else -> {
                val next = nextCoursebook(latest)
                log.info("신규 수강편람 감지: {} {}", next.year, next.semester)
                vacancyNotificationRepository.deleteAll()
                syncCoursebook(coursebookRepository.save(next))
                pushService.sendGlobalPushAndNotification(
                    PushMessage(title = "신규 수강편람", body = "${next.year}년도 ${next.semester.fullName} 수강편람이 추가되었습니다."),
                    NotificationType.COURSEBOOK,
                )
            }
        }
    }

    private fun syncCoursebook(coursebook: Coursebook) {
        registrationPeriodExtractor.extract(coursebook.year, coursebook.semester)
        syncSemester(coursebook.year, coursebook.semester)
    }

    private fun syncSemester(
        year: Int,
        semester: Semester,
    ) {
        val englishByKey = sugangSnuXlsxParser.parseEnglish(sugangSnuLectureApi.downloadLectureXlsx(year, semester, "en"))
        val rows =
            sugangSnuXlsxParser
                .parse(sugangSnuLectureApi.downloadLectureXlsx(year, semester, "ko"))
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
            Semester.SPRING -> Coursebook(coursebook.year, Semester.SUMMER)
            Semester.SUMMER -> Coursebook(coursebook.year, Semester.AUTUMN)
            Semester.AUTUMN -> Coursebook(coursebook.year, Semester.WINTER)
            Semester.WINTER -> Coursebook(coursebook.year + 1, Semester.SPRING)
        }
}
