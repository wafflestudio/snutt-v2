package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.batch.BatchJob
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.enums.localizedFullName
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseVocabularyService
import com.wafflestudio.snutt.core.domain.lecture.service.LectureVocabularyService
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

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
    private val lectureVocabularyService: LectureVocabularyService,
    private val courseVocabularyService: CourseVocabularyService,
) : BatchJob {
    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "sugangSnuSync"

    override fun run(target: YearAndSemester?) {
        if (target != null) {
            syncSemester(target.year, target.semester, touchCoursebook = true)
            return
        }
        val condition = sugangSnuLectureApi.getCoursebookCondition()
        val sugangSnu = Coursebook(condition.latestYear, condition.latestSemester)
        val latest = coursebookService.findLatestCoursebook()
        if (latest == null) {
            log.info("첫 수강편람 생성: {} {}", sugangSnu.year, sugangSnu.semester)
            syncCoursebook(sugangSnu)
            coursebookRepository.save(sugangSnu)
            return
        }
        val comparison = compareValuesBy(latest, sugangSnu, Coursebook::year, Coursebook::semester)
        when {
            comparison > 0 -> throw IllegalStateException(
                "최신 수강편람(${latest.year} ${latest.semester})이 수강신청 사이트(${sugangSnu.year} ${sugangSnu.semester})보다 최신이다",
            )
            comparison == 0 -> syncCoursebook(latest, touchCoursebook = true)
            else -> {
                val next = nextCoursebook(latest)
                log.info("신규 수강편람 감지: {} {}", next.year, next.semester)
                syncCoursebook(next)
                coursebookRepository.save(next)
                vacancyNotificationRepository.deleteAll()
                pushService.sendGlobalPushAndNotification(
                    NotificationTemplate.COURSEBOOK_NEW.render(next.year, next.semester.localizedFullName),
                    NotificationType.COURSEBOOK,
                )
            }
        }
    }

    private fun syncCoursebook(
        coursebook: Coursebook,
        touchCoursebook: Boolean = false,
    ) {
        registrationPeriodExtractor.extract(coursebook.year, coursebook.semester)
        syncSemester(coursebook.year, coursebook.semester, touchCoursebook)
    }

    /** [touchCoursebook]이면 강의 반영(1단계)이 커밋됐을 때 수강편람 updated_at을 고친다. 뒤 단계가 실패해도 강의는 바뀌었기 때문이다. */
    private fun syncSemester(
        year: Int,
        semester: Semester,
        touchCoursebook: Boolean = false,
    ) {
        val xlsx =
            sugangSnuXlsxParser.parse(
                sugangSnuLectureApi.downloadLectureXlsx(year, semester, "ko"),
                sugangSnuLectureApi.downloadLectureXlsx(year, semester, "en"),
            )
        val rows = xlsx.rows.map { sugangSnuLectureEnricher.enrich(year, semester, it) }
        // 영어 엑셀이 비면 상세 API가 준 영어 값을 쓰고, 상세 API에 없는 기존 강의의 영어 필드는 보존한다. 신규 강의는 상세 API 값 그대로다.
        // 영어 엑셀이 끝까지 공개되지 않는 학기도 있어서, 신규 강의의 영어 값을 비워 두면 영영 채워지지 않는다.
        var lecturesCommitted = false
        var failure: Throwable? = null
        try {
            sugangSnuSyncService.sync(year, semester, rows, preserveEnglish = !xlsx.englishAvailable) { lecturesCommitted = true }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // 시간표 반영 단계가 실패해도 강의 반영은 이미 커밋됐을 수 있으므로 캐시는 비운다.
            lectureVocabularyService.evict(year, semester)
            courseVocabularyService.evict()
            if (touchCoursebook && lecturesCommitted) {
                // 동기화가 실패한 중이면 그 예외(단계별 실패가 suppressed로 붙어 있다)를 덮지 않는다.
                runCatching { coursebookRepository.touchUpdatedAt(year, semester, Instant.now()) }
                    .onFailure { failure?.addSuppressed(it) ?: throw it }
            }
        }
    }

    private fun nextCoursebook(coursebook: Coursebook): Coursebook =
        when (coursebook.semester) {
            Semester.SPRING -> Coursebook(coursebook.year, Semester.SUMMER)
            Semester.SUMMER -> Coursebook(coursebook.year, Semester.AUTUMN)
            Semester.AUTUMN -> Coursebook(coursebook.year, Semester.WINTER)
            Semester.WINTER -> Coursebook(coursebook.year + 1, Semester.SPRING)
        }
}
