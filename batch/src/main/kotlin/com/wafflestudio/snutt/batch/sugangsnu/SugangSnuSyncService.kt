package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.client.joinLocalized
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.enums.yearAndSemesterLabel
import com.wafflestudio.snutt.core.common.push.Deeplink
import com.wafflestudio.snutt.core.common.push.DeeplinkResolver
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.model.localizedCourseTitle
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.model.lectureCount
import com.wafflestudio.snutt.core.domain.notification.model.toNotification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.ClassTimeUtils
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

private data class LectureInput(
    val lecture: Lecture,
    val classTimes: List<ClassPlaceAndTime>,
)

private data class LectureUpdate(
    val lecture: Lecture,
    val input: LectureInput,
    val changedLabels: List<LocalizedMessage>,
    val notifiable: Boolean,
    val classTimesChanged: Boolean,
)

private data class FieldChange(
    val label: LocalizedMessage,
    val notifiable: Boolean,
)

private enum class LectureField(
    val label: LocalizedMessage,
) {
    CLASSIFICATION(LocalizedMessage(ko = "교과 구분", en = "Classification")),
    DEPARTMENT(LocalizedMessage(ko = "학부", en = "Department")),
    ACADEMIC_YEAR(LocalizedMessage(ko = "학년", en = "Academic year")),
    COURSE_TITLE(LocalizedMessage(ko = "강의명", en = "Course title")),
    CREDIT(LocalizedMessage(ko = "학점", en = "Credits")),
    INSTRUCTOR(LocalizedMessage(ko = "교수", en = "Instructor")),
    QUOTA(LocalizedMessage(ko = "정원", en = "Quota")),
    ETC(LocalizedMessage(ko = "기타", en = "Other")),
    REMARK(LocalizedMessage(ko = "비고", en = "Remarks")),
    CATEGORY(LocalizedMessage(ko = "교양영역", en = "Liberal education area")),
    CATEGORY_PRE_2025(LocalizedMessage(ko = "구) 교양영역", en = "Liberal education area (old)")),
    CLASS_TIME_AND_PLACE(LocalizedMessage(ko = "강의 시간/장소", en = "Class time/place")),
}

@Service
class SugangSnuSyncService(
    private val lectureRepository: LectureRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
    private val courseRepository: CourseRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableRepository: TimetableRepository,
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val notificationRepository: NotificationRepository,
    private val pushService: PushService,
    private val lectureBuildingSync: LectureBuildingSync,
    private val timetableLectureReminderService: TimetableLectureReminderService,
    private val deeplinkResolver: DeeplinkResolver,
    private val pendingRepository: SugangSyncPendingRepository,
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * 학기 하나를 동기화한다. 사용자 요청과 같은 락 순서(timetable → timetable_lecture → 리마인더)를 지키고, 사용자 요청 하나와 충돌해도
     * 학기 전체가 롤백되지 않도록 단계를 나눠 따로 커밋한다. 단계 사이에서 실패하거나 프로세스가 죽어도 다음 실행이 이어서 마친다.
     *
     * 0. 지난 실행이 남긴 시간표 반영부터 마친다. 그 강의는 DB가 이미 새 값이라 이번 diff에서 바뀐 강의로 보이지 않는다.
     * 1. 강의 반영(한 트랜잭션): 고칠 강의 행을 먼저 잠그고 강의·수업 시간·강의평 과목을 반영한다. 같은 트랜잭션에서 시간표에 반영할
     *    강의 변경을 `sugang_sync_pending_lecture`에 남긴다. 시간표는 잠그지 않는다. 시간표에 담는 요청은 강의 행을 공유 락으로 읽으므로,
     *    옛 수업 시간으로 겹침을 검사한 요청은 이 단계보다 먼저 커밋되고(2단계가 그 시간표를 본다), 그 밖의 요청은 바뀐 수업 시간을 본다.
     * 2. 시간표 반영([processPending]): 강의 변경마다 그 강의를 담은 시간표를 펼쳐 기록하고, 시간표 [TIMETABLE_CHUNK_SIZE]개씩 id 순으로
     *    잠근 뒤 다시 읽어 겹쳐서 밀려난 강의를 빼고 리마인더 발송 시각을 다시 계산한다. 반영한 기록은 같은 트랜잭션에서 처리했다고 표시하고,
     *    알림은 outbox에 넣는다. 펼친 뒤 복사된 시간표가 없을 때까지 다시 펼친다. 다 반영한 강의 변경은 관심강좌 알림을 outbox에 넣으며 지운다.
     * 3. 폐강([deleteClosedLectures]): 폐강 강의를 담은 시간표에서 빼고, 강의 행을 잠가(시간표에 담기는 중인 강의는 건너뛴다) 아직 담긴
     *    시간표가 없는 강의만 지운다. 폐강 강의는 엑셀에 없는 한 DB에 남아 있으므로, 끝내지 못하면 다음 동기화의 diff가 다시 찾는다.
     * 4. 알림 배출([deliver]): outbox를 notification으로 옮기고 같은 트랜잭션에서 지우며 시간표 변경 수를 사용자별 푸시 outbox에 더한다.
     *    그다음 푸시를 보내고 푸시 outbox를 지운다.
     *
     * 동기화 두 개가 겹치지 않게 MySQL 이름 락([SYNC_LOCK_NAME])을 잡고 돈다.
     * 1단계 뒤의 단계가 실패하면 기록하고 다음 단계로 넘어간다. 알림 배출과 푸시는 항상 마지막에 하고, 그 뒤 실패를 던진다.
     * [onLecturesCommitted]는 1단계가 커밋되면 부른다. 뒤 단계가 실패해 예외로 끝나도 강의가 바뀌었는지 호출자가 알 수 있다.
     */
    fun sync(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
        preserveEnglish: Boolean = false,
        onLecturesCommitted: () -> Unit = {},
    ) = withSyncLock { syncLocked(year, semester, rows, preserveEnglish, onLecturesCommitted) }

    /**
     * 동기화가 둘 겹치지 않게 MySQL 이름 락을 잡는다(남은 일·outbox 테이블은 학기와 상관없이 하나라 두 실행이 같은 알림을 옮길 수 있다).
     * 같은 커넥션에서 풀어야 하므로 [action] 동안 커넥션 하나를 쥔다. 커넥션이 끊기면 MySQL이 락을 푼다.
     */
    private fun <T> withSyncLock(action: () -> T): T =
        checkNotNull(
            jdbcTemplate.execute(
                ConnectionCallback { connection ->
                    val acquired =
                        connection.prepareStatement("SELECT GET_LOCK(?, 0)").use { ps ->
                            ps.setString(1, SYNC_LOCK_NAME)
                            ps.executeQuery().use { it.next() && it.getInt(1) == 1 }
                        }
                    check(acquired) { "다른 수강편람 동기화가 돌고 있다($SYNC_LOCK_NAME)" }
                    try {
                        Result.success(action())
                    } finally {
                        connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { ps ->
                            ps.setString(1, SYNC_LOCK_NAME)
                            ps.executeQuery().close()
                        }
                    }
                },
            ),
        ).getOrThrow()

    private fun syncLocked(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
        preserveEnglish: Boolean,
        onLecturesCommitted: () -> Unit,
    ) {
        val run = SyncRun()
        processPending(run)
        val synced =
            try {
                retryOnLockFailure("강의 반영") {
                    checkNotNull(transactionTemplate.execute { syncLectures(year, semester, rows, preserveEnglish) })
                }
            } catch (e: Exception) {
                deliver(run)
                run.failures.forEach(e::addSuppressed)
                throw e
            }
        onLecturesCommitted()
        processPending(run)
        val deleted = run.step("폐강 강의 삭제") { deleteClosedLectures(synced.deleted, run) }.orEmpty()
        deliver(run)

        val inputs = synced.created + synced.updated.map { it.input }
        lectureBuildingSync.sync(inputs.flatMap { input -> input.classTimes.map { it.place } })

        log.info("sugang sync: created={}, updated={}, deleted={}", synced.created.size, synced.updated.size, deleted.size)
        if (run.failures.isNotEmpty()) {
            throw IllegalStateException("수강편람 동기화 ${run.failures.size}건이 실패했다. 남은 일은 다음 실행이 마친다", run.failures.first()).apply {
                run.failures.drop(1).forEach(::addSuppressed)
            }
        }
    }

    private fun syncLectures(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
        preserveEnglish: Boolean,
    ): SyncedLectures {
        val newMap = rows.associateBy { it.courseNumber to it.lectureNumber }
        val oldLectures = lectureRepository.findByYearAndSemester(year, semester)
        val oldMap = oldLectures.associateBy { it.courseNumber to it.lectureNumber }
        val oldClassTimesMap =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(oldLectures.mapNotNull { it.id })
                .groupBy({ it.lectureId }, { it.toClassPlaceAndTime() })

        val created =
            (newMap - oldMap.keys)
                .values
                .map { LectureInput(it.toLecture(year, semester), it.classPlaceAndTimes) }
        val updated =
            newMap.mapNotNull { (key, row) ->
                val old = oldMap[key] ?: return@mapNotNull null
                val new = row.toLecture(year, semester).also { if (preserveEnglish) it.fillMissingEnglishFrom(old) }
                val oldTimes = oldClassTimesMap[old.id].orEmpty()
                val changes = changedFields(old, new, oldTimes, row.classPlaceAndTimes)
                if (changes.isEmpty()) return@mapNotNull null
                LectureUpdate(
                    lecture = old,
                    input = LectureInput(new, row.classPlaceAndTimes),
                    changedLabels = changes.map { it.label }.distinct(),
                    notifiable = changes.any { it.notifiable },
                    classTimesChanged = oldTimes != row.classPlaceAndTimes,
                )
            }
        val deleted = oldLectures.filter { (it.courseNumber to it.lectureNumber) !in newMap }

        // 수업 시간만 바뀌어 lecture 행이 그대로여도 잠근다. 시간표에 담는 요청(강의 공유 락)이 이 커밋 뒤의 수업 시간을 보게 한다.
        if (updated.isNotEmpty()) lectureRepository.lockAllByIdIn(updated.map { it.lecture.id!! })
        val courseIdsBeforeSync = oldLectures.mapNotNull { it.courseId }
        upsertLectures(created, updated)
        lectureRepository.flush()
        val affectedCourses =
            (courseIdsBeforeSync + (oldLectures + created.map { it.lecture }).mapNotNull { it.courseId })
                .distinct()
        if (affectedCourses.isNotEmpty()) courseRepository.refreshLatestLectures(affectedCourses)
        // 시간표 반영(2단계)이 할 일을 강의 반영과 같은 트랜잭션에 남긴다. 2단계가 끝나기 전에 실패해도 다음 실행이 이어서 한다.
        // 외래 키 없는 테이블이라 강의 X락을 쥔 채 넣어도 다른 행을 잠그지 않는다.
        pendingRepository.insertLectures(
            updated.map {
                PendingLecture(
                    id = 0,
                    lectureId = it.lecture.id!!,
                    classTimesChanged = it.classTimesChanged,
                    changedLabels = it.changedLabels.joinLocalized(),
                    notifiable = it.notifiable,
                )
            },
        )
        return SyncedLectures(created, updated, deleted)
    }

    private fun upsertLectures(
        created: List<LectureInput>,
        updated: List<LectureUpdate>,
    ) {
        val courses = loadCourses(created.map { it.lecture } + updated.map { it.input.lecture })
        if (created.isNotEmpty()) {
            created.forEach { input -> input.lecture.courseId = resolveCourseId(input.lecture, courses) }
            lectureRepository.saveAll(created.map { it.lecture })
        }
        updated.forEach { update ->
            val old = update.lecture
            val instructorChanged = old.instructor != update.input.lecture.instructor
            old.copyMetadataFrom(update.input.lecture)
            if (instructorChanged || old.courseId == null) {
                old.courseId = resolveCourseId(old, courses)
            }
        }
        val classTimesUpdated = updated.filter { it.classTimesChanged }
        if (classTimesUpdated.isNotEmpty()) {
            lectureClassTimeRepository.deleteByLectureIdIn(classTimesUpdated.map { it.lecture.id!! })
        }
        val newClassTimes =
            created.flatMap { input -> input.classTimes.map { toClassTimeEntity(input.lecture.id!!, it) } } +
                classTimesUpdated.flatMap { update -> update.input.classTimes.map { toClassTimeEntity(update.lecture.id!!, it) } }
        lectureClassTimeRepository.insertAll(newClassTimes)
    }

    private fun toClassTimeEntity(
        lectureId: Long,
        classTime: ClassPlaceAndTime,
    ) = LectureClassTime(
        lectureId = lectureId,
        day = classTime.day,
        place = classTime.place,
        startMinute = classTime.startMinute,
        endMinute = classTime.endMinute,
    )

    private fun loadCourses(lectures: List<Lecture>): MutableMap<Pair<String, String>, Course> =
        lectures
            .map { it.courseNumber }
            .distinct()
            .chunked(COURSE_LOOKUP_CHUNK_SIZE)
            .flatMap(courseRepository::findAllByCourseNumberIn)
            .associateBy { it.courseNumber to it.instructor }
            .toMutableMap()

    private fun resolveCourseId(
        lecture: Lecture,
        courses: MutableMap<Pair<String, String>, Course>,
    ): Long? {
        val instructor = lecture.instructor.orEmpty()
        val course =
            courses.getOrPut(lecture.courseNumber to instructor) {
                courseRepository.findByCourseNumberAndInstructor(lecture.courseNumber, instructor)
                    ?: courseRepository.save(
                        Course(
                            courseNumber = lecture.courseNumber,
                            instructor = instructor,
                            title = lecture.courseTitle,
                        ),
                    )
            }
        return course.id
    }

    private fun bookmarkUserIdsByLectureId(lectureIds: Collection<Long>): Map<Long, List<Long>> =
        bookmarkLectureRepository
            .findByLectureIdIn(lectureIds)
            .groupBy({ it.lectureId }, { it.userId })
            .mapValues { (_, userIds) -> userIds.distinct() }

    /**
     * 남은 강의 변경을 시간표에 반영한다. 한 바퀴마다 강의 변경이 닿는 시간표를 펼쳐 기록하고([expandPending]), 아직 반영하지 않은 시간표를
     * 묶음으로 반영한다([applyPendingChunk]). 펼친 뒤 새로 나타난 시간표(반영 전 시간표의 복사본)는 다음 바퀴가 찾는다. 반영할 시간표가 더
     * 없으면 다 반영한 강의 변경을 마무리한다([finishPending]). 이번 실행에서 실패한 시간표는 다시 시도하지 않고 다음 실행에 남긴다.
     */
    private fun processPending(run: SyncRun) {
        // 두 번째 바퀴부터는 앞 바퀴가 펼치기 시작한 뒤(여유를 두고) 담긴 행만 읽는다. 복사본의 행은 복사할 때 담긴다.
        var since: Instant? = null
        repeat(PENDING_ROUNDS) {
            val pendings = pendingRepository.findLectures()
            if (pendings.isEmpty()) return
            val expandedFrom = SYSTEM_CLOCK.instant().minus(REEXPAND_MARGIN)
            run.step("시간표 펼치기") { transactionTemplate.executeWithoutResult { expandPending(pendings, since) } } ?: return
            since = expandedFrom
            val todo = pendingRepository.findUnprocessedTimetableIds() - run.failedTimetableIds
            if (todo.isEmpty()) {
                run.step("강의 변경 마무리") {
                    retryOnLockFailure("강의 변경 마무리") { transactionTemplate.executeWithoutResult { finishPending(pendings) } }
                }
                return
            }
            val pendingsById = pendings.associateBy { it.id }
            syncTimetables(todo, run) { chunk -> applyPendingChunk(chunk, pendingsById) }
        }
        log.warn("sugang sync: 시간표 반영을 {}바퀴 돌아도 새로 복사된 시간표가 남았다. 다음 실행이 이어서 반영한다", PENDING_ROUNDS)
    }

    /**
     * 강의 변경마다 그 강의를 담은 시간표를 기록한다. 1단계가 커밋되기 전부터 담고 있던 시간표(행의 created_at이 강의 변경의 created_at
     * 이하)는 변경 알림 대상이다. 그 뒤에 담은 시간표는 바뀐 수업 시간으로 겹침을 검사했거나 반영 전 시간표를 복사한 것이라, 겹침만 바로잡고
     * 변경 알림은 보내지 않는다. 1단계 직후 죽어 펼치기가 몇 시간 늦어져도 그사이 강의를 담은 사용자에게 변경 알림이 가지 않는다.
     * 락 없이 읽는다. 펼친 뒤 커밋된 복사본은 다음 바퀴가 찾는다.
     */
    private fun expandPending(
        pendings: List<PendingLecture>,
        since: Instant?,
    ) {
        val rowsByLectureId =
            timetableLectureRepository
                .findLectureTimetableIdsByLectureIdIn(pendings.map { it.lectureId }.distinct(), since)
                .groupBy { it.lectureId }
        val candidates =
            pendings.flatMap { pending ->
                rowsByLectureId[pending.lectureId].orEmpty().distinctBy { it.timetableId }.map { row ->
                    PendingTimetable(pending.id, row.timetableId, notify = row.createdAt?.let { it <= pending.createdAt } ?: true)
                }
            }
        val known = pendingRepository.findTimetablePairs(candidates.map { it.timetableId }.distinct())
        pendingRepository.insertTimetables(candidates.filter { (it.pendingLectureId to it.timetableId) !in known })
    }

    /** 시간표 묶음을 잠그고 아직 반영하지 않은 강의 변경을 반영한다. 반영했다는 표시와 알림(outbox)을 같은 트랜잭션에 남긴다. */
    private fun applyPendingChunk(
        timetableIds: List<Long>,
        pendingsById: Map<Long, PendingLecture>,
    ) {
        // 잠근 뒤에 읽는다. 잠그기 전에 본 시간표 내용은 사용자 요청이 그사이 바꿨을 수 있다.
        val timetables = timetableRepository.findAllForUpdateByIdIn(timetableIds)
        val targets = pendingRepository.findUnprocessedByTimetableIdIn(timetableIds).associateBy { it.pendingLectureId to it.timetableId }
        val pendings =
            targets.keys
                .mapNotNull { pendingsById[it.first] }
                .distinct()
                .sortedBy { it.id }
        val lectures = lectureRepository.findAllById(pendings.map { it.lectureId }.distinct()).associateBy { it.id!! }
        val affected = loadAffectedTimetables(timetables, lectures.keys)

        val outbox = mutableListOf<Pair<Notification, PushKind?>>()
        val removedEntryIds = mutableSetOf<Long>()
        val changedTimetableIds = mutableSetOf<Long>()
        // 강의 변경을 넣은 순서(엑셀 행 순서)로 본다. 앞서 본 강의가 밀려나면 뒤의 강의는 그 강의와 겹치지 않는다.
        pendings.forEach { pending ->
            // 그사이 폐강돼 지워진 강의다. 그 강의를 담은 시간표도 없다.
            val lecture = lectures[pending.lectureId] ?: return@forEach
            val newTimes = affected.classTimesOf(lecture.id!!)
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                val target = targets[pending.id to timetable.id!!] ?: return@forEachEntry
                if (entry.id in removedEntryIds) return@forEachEntry
                val overwritten =
                    pending.classTimesChanged && affected.overlapsOtherLecture(timetable.id!!, lecture.id!!, newTimes, removedEntryIds)
                if (overwritten) {
                    removedEntryIds += entry.id!!
                    changedTimetableIds += timetable.id!!
                    outbox +=
                        timetableNotification(
                            timetable,
                            NotificationTemplate.TIMETABLE_LECTURE_OVERWRITTEN,
                            NotificationType.LECTURE_REMOVE,
                            lecture.localizedCourseTitle,
                        ) to PushKind.DELETED
                    return@forEachEntry
                }
                if (pending.classTimesChanged && entry.overrides?.classPlaceAndTimes == null) {
                    timetableLectureReminderService.recomputeForTimetableLecture(entry.id!!, newTimes)
                    changedTimetableIds += timetable.id!!
                }
                if (target.notify) changedTimetableIds += timetable.id!!
                if (target.notify && pending.notifiable) {
                    outbox +=
                        timetableNotification(
                            timetable,
                            NotificationTemplate.TIMETABLE_LECTURE_UPDATED,
                            NotificationType.LECTURE_UPDATE,
                            lecture.localizedCourseTitle,
                            labels = pending.changedLabels,
                            deeplink = deeplinkResolver.build(Deeplink.TimetableLecture(timetable.id!!, lecture.id!!)),
                        ) to PushKind.UPDATED
                }
            }
        }

        if (removedEntryIds.isNotEmpty()) timetableLectureRepository.deleteAllByIdInBatch(removedEntryIds)
        if (changedTimetableIds.isNotEmpty()) timetableRepository.touchUpdatedAt(changedTimetableIds, clock.instant())
        // notification이 아니라 outbox에 넣는다. notification INSERT는 사용자 행에 FK S락을 잡는데, 시간표를 잠근 채 사용자 행을 기다리면
        // 사용자 행 → 시간표 순으로 잠그는 요청(시간표 이름 변경·대표 지정·테마 변경 등)과 데드락이 난다.
        pendingRepository.insertOutbox(outbox)
        pendingRepository.markProcessed(timetableIds)
    }

    /** 모든 시간표를 반영한 강의 변경을 지우고 관심강좌 알림을 outbox에 넣는다. 반영하지 못한 시간표가 남은 강의 변경은 다음 실행에 남긴다. */
    private fun finishPending(pendings: List<PendingLecture>) {
        val unfinished = pendingRepository.findLectureIdsWithUnprocessed(pendings.map { it.id })
        val done = pendings.filter { it.id !in unfinished }
        if (done.isEmpty()) return
        val notifiable = done.filter { it.notifiable }
        val lectures = lectureRepository.findAllById(notifiable.map { it.lectureId }.distinct()).associateBy { it.id!! }
        val bookmarkUserIds = if (lectures.isEmpty()) emptyMap() else bookmarkUserIdsByLectureId(lectures.keys)
        val outbox =
            notifiable.flatMap { pending ->
                val lecture = lectures[pending.lectureId] ?: return@flatMap emptyList()
                bookmarkUserIds[lecture.id].orEmpty().map { userId ->
                    bookmarkNotification(
                        userId,
                        lecture,
                        NotificationTemplate.BOOKMARK_LECTURE_UPDATED,
                        NotificationType.LECTURE_UPDATE,
                        labels = pending.changedLabels,
                    ) to null
                }
            }
        pendingRepository.insertOutbox(outbox)
        pendingRepository.deleteLectures(done.map { it.id })
    }

    /** [timetableIds]를 id 순 [TIMETABLE_CHUNK_SIZE]개씩 잠그고 [apply]한다. 실패한 묶음은 [run]에 남기고 다음 묶음으로 넘어간다. */
    private fun syncTimetables(
        timetableIds: Collection<Long>,
        run: SyncRun,
        apply: (List<Long>) -> Unit,
    ) {
        timetableIds.sorted().chunked(TIMETABLE_CHUNK_SIZE).forEach { chunk ->
            try {
                retryOnLockFailure("시간표 반영") { transactionTemplate.executeWithoutResult { apply(chunk) } }
            } catch (e: Exception) {
                log.error("sugang sync: 시간표 {}개 반영 실패 (첫 id {}). 다음 실행이 다시 반영한다", chunk.size, chunk.first(), e)
                run.failures += e
                run.failedTimetableIds += chunk
            }
        }
    }

    /** 폐강 강의를 시간표 묶음에서 뺀다. 폐강 강의는 엑셀에 없는 한 DB에 남으므로 이 반영은 기록 없이도 다음 실행이 다시 한다. */
    private fun closeInChunk(
        timetableIds: List<Long>,
        closed: List<Lecture>,
    ) {
        val timetables = timetableRepository.findAllForUpdateByIdIn(timetableIds)
        val affected = loadAffectedTimetables(timetables, closed.map { it.id!! }.toSet())
        val outbox = mutableListOf<Pair<Notification, PushKind?>>()
        val removedEntryIds = mutableSetOf<Long>()
        val changedTimetableIds = mutableSetOf<Long>()
        closed.forEach { lecture ->
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                removedEntryIds += entry.id!!
                changedTimetableIds += timetable.id!!
                outbox +=
                    timetableNotification(
                        timetable,
                        NotificationTemplate.TIMETABLE_LECTURE_CLOSED,
                        NotificationType.LECTURE_REMOVE,
                        lecture.localizedCourseTitle,
                    ) to PushKind.DELETED
            }
        }
        if (removedEntryIds.isEmpty()) return
        timetableLectureRepository.deleteAllByIdInBatch(removedEntryIds)
        timetableRepository.touchUpdatedAt(changedTimetableIds, clock.instant())
        pendingRepository.insertOutbox(outbox)
    }

    /**
     * 폐강 강의를 지운다. 강의 행을 잠근 뒤에는 새로 담기지 않으므로(시간표에 담을 때 강의 행을 공유 락으로 읽는다), 그때 아무 시간표에도
     * 없는 강의만 지운다. 담기는 중이라 잠그지 못했거나 그사이 담긴(복사된) 강의는 그 시간표에서 먼저 빼고 다시 시도한다.
     * 지운 강의만 돌려준다. 관심강좌 폐강 알림은 지우는 트랜잭션에서 outbox에 넣는다.
     */
    private fun deleteClosedLectures(
        closed: List<Lecture>,
        run: SyncRun,
    ): List<Lecture> {
        val deleted = mutableListOf<Lecture>()
        var remaining = closed
        repeat(CLOSE_ATTEMPTS) {
            if (remaining.isEmpty()) return deleted
            val referencingTimetableIds =
                timetableLectureRepository.findTimetableIdsByLectureIdIn(remaining.map { it.id!! }) - run.failedTimetableIds
            if (referencingTimetableIds.isNotEmpty()) {
                val closing = remaining
                syncTimetables(referencingTimetableIds, run) { chunk -> closeInChunk(chunk, closing) }
            }
            val removed =
                retryOnLockFailure("폐강 강의 삭제") { checkNotNull(transactionTemplate.execute { deleteUnreferenced(remaining) }) }
            val removedIds = removed.map { it.id!! }.toSet()
            deleted += removed
            remaining = remaining.filter { it.id !in removedIds }
        }
        if (remaining.isNotEmpty()) {
            log.warn("sugang sync: 폐강 강의 {}개를 지우지 못했다(시간표에 담기는 중). 다음 동기화에서 다시 닫는다: {}", remaining.size, remaining.map { it.id })
        }
        return deleted
    }

    /** 지운 강의를 돌려준다. 관심강좌 폐강 알림은 outbox에 넣는다(강의 X락을 쥔 채 사용자 FK S락을 잡지 않도록). */
    private fun deleteUnreferenced(lectures: List<Lecture>): List<Lecture> {
        val locked = lectureRepository.lockAllByIdInSkipLocked(lectures.map { it.id!! }).toSet()
        if (locked.isEmpty()) return emptyList()
        // 잠금 읽기라 읽기 뷰 생성 시점과 상관없이 커밋된 최신 행을 본다.
        val referenced = timetableLectureRepository.lockReferencedLectureIds(locked).toSet()
        val deletable = lectures.filter { it.id in locked && it.id !in referenced }
        if (deletable.isEmpty()) return emptyList()
        // 강의를 지우면 관심강좌가 cascade로 지워지므로 그 전에 읽는다.
        val bookmarkUserIds = bookmarkUserIdsByLectureId(deletable.map { it.id!! })
        pendingRepository.insertOutbox(
            deletable.flatMap { lecture ->
                bookmarkUserIds[lecture.id].orEmpty().map { userId ->
                    bookmarkNotification(userId, lecture, NotificationTemplate.BOOKMARK_LECTURE_CLOSED, NotificationType.LECTURE_REMOVE) to
                        null
                }
            },
        )
        lectureRepository.deleteAllByIdInBatch(deletable.map { it.id!! })
        // course.latest_lecture_id는 강의 삭제 때 NULL이 되므로 남은 강의로 다시 정한다.
        val courseIds = deletable.mapNotNull { it.courseId }.distinct()
        if (courseIds.isNotEmpty()) courseRepository.refreshLatestLectures(courseIds)
        return deletable
    }

    /**
     * outbox의 알림을 먼저 넣은 순서로 notification에 옮긴다. 옮긴 행은 같은 트랜잭션에서 지우고(정확히 한 번), 시간표 변경 수는 같은
     * 트랜잭션에서 사용자별 푸시 outbox에 더한다. 옮기지 못한 알림은 outbox에 남고 다음 실행이 옮긴다. 그다음 푸시 outbox를 보내고 지운다.
     * 보낸 뒤 지우기 전에 죽으면 다음 실행이 같은 푸시를 한 번 더 보낸다(잃지 않는 쪽을 골랐다).
     */
    private fun deliver(run: SyncRun) {
        run.step("알림 넣기") {
            while (true) {
                val moved = retryOnLockFailure("알림 넣기") { checkNotNull(transactionTemplate.execute { moveOutbox() }) }
                if (moved == 0) break
            }
        }
        run.step("푸시 보내기") { sendPushes() }
    }

    /** outbox 한 묶음을 옮기고 옮긴 행 수를 돌려준다. 그사이 지워진 사용자의 알림은 버린다(외래 키 위반으로 outbox 전체가 막히지 않게). */
    private fun moveOutbox(): Int {
        val entries = pendingRepository.findOutbox(OUTBOX_BATCH_SIZE)
        if (entries.isEmpty()) return 0
        val userIds = entries.map { it.notification.userId!! }.distinct()
        val existing = userRepository.findAllById(userIds).map { it.id!! }.toSet()
        val (deliverable, orphaned) = entries.partition { it.notification.userId in existing }
        if (orphaned.isNotEmpty()) {
            log.warn("sugang sync: 없는 사용자의 알림 {}개를 버린다: {}", orphaned.size, orphaned.map { it.notification.userId }.distinct())
        }
        notificationRepository.insertAll(deliverable.map { it.notification })
        pendingRepository.addPushCounts(
            deliverable
                .filter { it.pushKind != null }
                .groupBy { it.notification.userId!! }
                .mapValues { (_, rows) ->
                    rows.count { it.pushKind == PushKind.UPDATED } to rows.count { it.pushKind == PushKind.DELETED }
                },
        )
        pendingRepository.deleteOutbox(entries.map { it.id })
        return entries.size
    }

    private fun sendPushes() {
        val counts = pendingRepository.findPushCounts()
        if (counts.isEmpty()) return
        pushService.sendTargetedPushes(
            counts.mapValues { (_, count) ->
                TimetableChangeCount(count.first, count.second).toMessage().copy(urlScheme = deeplinkResolver.build(Deeplink.Notifications))
            },
            PushPreferenceType.LECTURE_UPDATE,
        )
        pendingRepository.deletePushCounts(counts.keys)
    }

    private fun <T> retryOnLockFailure(
        step: String,
        action: () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return action()
            } catch (e: PessimisticLockingFailureException) {
                if (attempt >= LOCK_RETRY_ATTEMPTS) throw e
                log.warn("sugang sync {}: 락 충돌로 다시 시도한다 ({}/{})", step, attempt, LOCK_RETRY_ATTEMPTS, e)
                attempt++
            }
        }
    }

    private fun loadAffectedTimetables(
        timetables: List<Timetable>,
        affectedLectureIds: Set<Long>,
    ): AffectedTimetables {
        val timetablesById = timetables.associateBy { it.id!! }
        val entriesByTimetableId =
            timetableLectureRepository.findByTimetableIdIn(timetablesById.keys).groupBy { it.timetableId }
        val entries = entriesByTimetableId.values.flatten()
        val classTimesByLectureId =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(entries.mapNotNull { it.lectureId }.distinct())
                .groupBy({ it.lectureId }, { it.toClassPlaceAndTime() })
        return AffectedTimetables(
            timetablesById = timetablesById,
            entriesByLectureId = entries.filter { it.lectureId in affectedLectureIds }.groupBy { it.lectureId!! },
            entriesByTimetableId = entriesByTimetableId,
            classTimesByLectureId = classTimesByLectureId,
        )
    }

    private class AffectedTimetables(
        private val timetablesById: Map<Long, Timetable>,
        private val entriesByLectureId: Map<Long, List<TimetableLecture>>,
        private val entriesByTimetableId: Map<Long, List<TimetableLecture>>,
        private val classTimesByLectureId: Map<Long, List<ClassPlaceAndTime>>,
    ) {
        fun forEachEntry(
            lectureId: Long,
            action: (Timetable, TimetableLecture) -> Unit,
        ) {
            entriesByLectureId[lectureId].orEmpty().forEach { entry ->
                timetablesById[entry.timetableId]?.let { action(it, entry) }
            }
        }

        fun classTimesOf(lectureId: Long): List<ClassPlaceAndTime> = classTimesByLectureId[lectureId].orEmpty()

        fun overlapsOtherLecture(
            timetableId: Long,
            lectureId: Long,
            newTimes: List<ClassPlaceAndTime>,
            removedEntryIds: Set<Long>,
        ): Boolean {
            val entries = entriesByTimetableId[timetableId].orEmpty().filter { it.id !in removedEntryIds }
            if (entries.any { it.lectureId == lectureId && it.overrides?.classPlaceAndTimes != null }) return false
            return entries.filter { it.lectureId != lectureId }.any { entry ->
                val times = entry.overrides?.classPlaceAndTimes ?: entry.lectureId?.let { classTimesByLectureId[it] }.orEmpty()
                ClassTimeUtils.timesOverlap(times, newTimes)
            }
        }
    }

    private data class SyncedLectures(
        val created: List<LectureInput>,
        val updated: List<LectureUpdate>,
        val deleted: List<Lecture>,
    )

    /** 한 번의 [sync]에서 실패한 단계와, 다시 시도하지 않고 다음 실행에 남길 시간표. */
    private inner class SyncRun {
        val failures = mutableListOf<Exception>()
        val failedTimetableIds = mutableSetOf<Long>()

        /** [action]이 실패하면 기록하고 null을 돌려준다. 다음 단계는 계속한다. */
        fun <T> step(
            name: String,
            action: () -> T,
        ): T? =
            try {
                action()
            } catch (e: Exception) {
                log.error("sugang sync: {} 실패. 다음 실행이 이어서 한다", name, e)
                failures += e
                null
            }
    }

    private class TimetableChangeCount(
        var updated: Int = 0,
        var deleted: Int = 0,
    ) {
        fun toMessage(): LocalizedPushMessage =
            when {
                updated > 0 && deleted > 0 ->
                    NotificationTemplate.COURSEBOOK_UPDATE_CHANGED_AND_DELETED
                        .render(lectureCount(updated), lectureCount(deleted))
                updated > 0 -> NotificationTemplate.COURSEBOOK_UPDATE_CHANGED.render(lectureCount(updated))
                else -> NotificationTemplate.COURSEBOOK_UPDATE_DELETED.render(lectureCount(deleted))
            }
    }

    private fun timetableNotification(
        timetable: Timetable,
        template: NotificationTemplate,
        type: NotificationType,
        courseTitle: LocalizedMessage,
        labels: LocalizedMessage? = null,
        deeplink: String = deeplinkResolver.build(Deeplink.Notifications),
    ): Notification {
        val args =
            listOfNotNull(
                yearAndSemesterLabel(timetable.year, timetable.semester),
                LocalizedMessage.same(timetable.title),
                courseTitle,
                labels,
            )
        return template
            .render(*args.toTypedArray())
            .copy(urlScheme = deeplink)
            .toNotification(timetable.userId, type)
    }

    private fun bookmarkNotification(
        userId: Long,
        lecture: Lecture,
        template: NotificationTemplate,
        type: NotificationType,
        labels: LocalizedMessage? = null,
    ): Notification {
        val args = listOfNotNull(yearAndSemesterLabel(lecture.year, lecture.semester), lecture.localizedCourseTitle, labels)
        return template
            .render(*args.toTypedArray())
            .copy(urlScheme = deeplinkResolver.build(Deeplink.Bookmarks(lecture.year, lecture.semester, lecture.id!!)))
            .toNotification(userId, type)
    }

    private fun changedFields(
        old: Lecture,
        new: Lecture,
        oldTimes: List<ClassPlaceAndTime>,
        newTimes: List<ClassPlaceAndTime>,
    ): List<FieldChange> =
        buildList {
            fun diff(
                field: LectureField,
                notifiable: Boolean,
                a: Any?,
                b: Any?,
            ) {
                if (a != b) add(FieldChange(field.label, notifiable))
            }
            diff(LectureField.CLASSIFICATION, true, old.classification, new.classification)
            diff(LectureField.DEPARTMENT, true, old.department, new.department)
            diff(LectureField.ACADEMIC_YEAR, true, old.academicYear, new.academicYear)
            diff(LectureField.COURSE_TITLE, true, old.courseTitle, new.courseTitle)
            diff(LectureField.CREDIT, true, old.credit, new.credit)
            diff(LectureField.INSTRUCTOR, true, old.instructor, new.instructor)
            diff(LectureField.QUOTA, true, old.quota, new.quota)
            diff(LectureField.ETC, true, old.freshmanQuota, new.freshmanQuota)
            diff(LectureField.REMARK, true, old.remark, new.remark)
            diff(LectureField.CATEGORY, true, old.category, new.category)
            diff(LectureField.CATEGORY_PRE_2025, true, old.categoryPre2025, new.categoryPre2025)
            diff(LectureField.CLASS_TIME_AND_PLACE, true, oldTimes, newTimes)
            diff(LectureField.CLASSIFICATION, false, old.classificationEn, new.classificationEn)
            diff(LectureField.DEPARTMENT, false, old.departmentEn, new.departmentEn)
            diff(LectureField.ACADEMIC_YEAR, false, old.academicYearEn, new.academicYearEn)
            diff(LectureField.COURSE_TITLE, false, old.courseTitleEn, new.courseTitleEn)
            diff(LectureField.INSTRUCTOR, false, old.instructorEn, new.instructorEn)
            diff(LectureField.REMARK, false, old.remarkEn, new.remarkEn)
            diff(LectureField.CATEGORY, false, old.categoryEn, new.categoryEn)
        }

    private fun SugangLectureRow.toLecture(
        year: Int,
        semester: Semester,
    ) = Lecture(
        year = year,
        semester = semester,
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        courseTitle = courseTitle,
        instructor = instructor.nullIfBlank(),
        department = department.nullIfBlank(),
        academicYear = academicYear.nullIfBlank(),
        category = category.nullIfBlank(),
        classification = classification.nullIfBlank(),
        credit = credit,
        quota = quota,
        freshmanQuota = freshmanQuota,
        remark = remark.nullIfBlank(),
        categoryPre2025 = categoryPre2025.nullIfBlank(),
        courseTitleEn = courseTitleEn.nullIfBlank(),
        instructorEn = instructorEn.nullIfBlank(),
        departmentEn = departmentEn.nullIfBlank(),
        academicYearEn = academicYearEn.nullIfBlank(),
        categoryEn = categoryEn.nullIfBlank(),
        classificationEn = classificationEn.nullIfBlank(),
        remarkEn = remarkEn.nullIfBlank(),
    )

    /**
     * 영어 엑셀이 비었을 때 쓴다. 상세 API가 준 영어 값은 그대로 두고(영어 엑셀이 있을 때도 상세 API가 우선이다), 없는 필드만
     * 기존 강의 값으로 채운다. 비워 두면 영어 엑셀이 공개될 때까지 기존 영어 값이 사라진다.
     */
    private fun Lecture.fillMissingEnglishFrom(other: Lecture) {
        courseTitleEn = courseTitleEn ?: other.courseTitleEn
        instructorEn = instructorEn ?: other.instructorEn
        departmentEn = departmentEn ?: other.departmentEn
        academicYearEn = academicYearEn ?: other.academicYearEn
        categoryEn = categoryEn ?: other.categoryEn
        classificationEn = classificationEn ?: other.classificationEn
        remarkEn = remarkEn ?: other.remarkEn
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val COURSE_LOOKUP_CHUNK_SIZE = 500
        private const val TIMETABLE_CHUNK_SIZE = 100
        private const val LOCK_RETRY_ATTEMPTS = 3
        private const val CLOSE_ATTEMPTS = 5
        private const val PENDING_ROUNDS = 5
        private const val OUTBOX_BATCH_SIZE = 1_000
        private const val SYNC_LOCK_NAME = "snutt.sugang_sync"

        /** timetable_lecture.created_at(시스템 시각)과 비교하는 시각. 주입된 Clock이 아니다. */
        private val SYSTEM_CLOCK: Clock = Clock.systemUTC()

        /** 다시 펼칠 때 앞 바퀴 시작보다 이만큼 앞서 담긴 행까지 읽는다. 복사 트랜잭션이 펼치기 전에 행을 넣고 늦게 커밋해도 놓치지 않게. */
        private val REEXPAND_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
