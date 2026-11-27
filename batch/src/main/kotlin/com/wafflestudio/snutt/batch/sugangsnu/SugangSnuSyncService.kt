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
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

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
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * 학기 하나를 동기화한다. 사용자 요청과 같은 락 순서(timetable → timetable_lecture → 리마인더)를 지키고, 사용자 요청 하나와 충돌해도
     * 학기 전체가 롤백되지 않도록 세 단계로 나눈다.
     *
     * 1. 강의 반영(한 트랜잭션): 고칠 강의 행을 먼저 잠그고 강의·수업 시간·강의평 과목을 반영한다.
     *    시간표는 잠그지 않는다. 시간표에 담는 요청은 강의 행을 공유 락으로 읽으므로, 옛 수업 시간으로 겹침을 검사한 요청은 이 단계보다 먼저
     *    커밋되고(2단계가 그 시간표를 본다), 그 밖의 요청은 바뀐 수업 시간을 본다.
     * 2. 시간표 반영(시간표 [TIMETABLE_CHUNK_SIZE]개씩 트랜잭션): 바뀐·폐강 강의를 담은 시간표를 id 순으로 잠근 뒤 최신 상태를 다시 읽어,
     *    겹쳐서 밀려난 강의와 폐강 강의를 빼고 리마인더 발송 시각을 다시 계산한다. 락 충돌이 나면 그 묶음만 다시 돈다.
     *    그다음 관심강좌 변경 알림을 남긴다.
     * 3. 폐강 강의 삭제: 강의 행을 잠그고(시간표에 담기는 중인 강의는 건너뛴다) 아직 담긴 시간표가 없는 강의만 지운다. 그사이 담긴 시간표는
     *    2단계처럼 먼저 뺀다. 끝까지 지우지 못한 강의는 엑셀에 없으므로 다음 동기화가 다시 닫는다.
     *
     * 2단계 묶음이 락 충돌이 아닌 이유로 실패하면 나머지 묶음과 3단계를 마치고 푸시를 보낸 뒤 예외를 던진다.
     */
    fun sync(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
        preserveEnglish: Boolean = false,
    ) {
        val synced =
            retryOnLockFailure("강의 반영") {
                checkNotNull(transactionTemplate.execute { syncLectures(year, semester, rows, preserveEnglish) })
            }
        val changes = TimetableChanges()
        val lectureIds = synced.updated.map { it.lecture.id!! } + synced.deleted.map { it.id!! }
        if (lectureIds.isNotEmpty()) {
            syncTimetables(
                timetableLectureRepository.findTimetableIdsByLectureIdIn(lectureIds),
                synced.updated.filter { it.notifiable },
                synced.deleted,
                changes,
            )
        }
        // 알림 순서를 예전(시간표 알림 다음 관심강좌 알림)과 같게 둔다.
        transactionTemplate.executeWithoutResult {
            notificationRepository.insertAll(bookmarkUpdateNotifications(synced.updated.filter { it.notifiable }))
        }
        val deleted = deleteClosedLectures(synced.deleted, changes)

        pushService.sendTargetedPushes(
            changes.countsByUserId.mapValues { (_, counts) ->
                counts.toMessage().copy(urlScheme = deeplinkResolver.build(Deeplink.Notifications))
            },
            PushPreferenceType.LECTURE_UPDATE,
        )

        val inputs = synced.created + synced.updated.map { it.input }
        lectureBuildingSync.sync(inputs.flatMap { input -> input.classTimes.map { it.place } })

        log.info("sugang sync: created={}, updated={}, deleted={}", synced.created.size, synced.updated.size, deleted.size)
        if (changes.failures.isNotEmpty()) {
            throw IllegalStateException("시간표 반영 ${changes.failures.size}묶음이 실패했다", changes.failures.first()).apply {
                changes.failures.drop(1).forEach(::addSuppressed)
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
                val new = row.toLecture(year, semester).also { if (preserveEnglish) it.copyEnglishFrom(old) }
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

    private fun bookmarkUpdateNotifications(notifiableUpdates: List<LectureUpdate>): List<Notification> {
        if (notifiableUpdates.isEmpty()) return emptyList()
        val bookmarkUserIds = bookmarkUserIdsByLectureId(notifiableUpdates.map { it.lecture.id!! })
        return notifiableUpdates.flatMap { update ->
            val lecture = update.lecture
            val labels = update.changedLabels.joinLocalized()
            bookmarkUserIds[lecture.id].orEmpty().map { userId ->
                bookmarkNotification(
                    userId,
                    lecture,
                    NotificationTemplate.BOOKMARK_LECTURE_UPDATED,
                    NotificationType.LECTURE_UPDATE,
                    labels = labels,
                )
            }
        }
    }

    private fun bookmarkUserIdsByLectureId(lectureIds: Collection<Long>): Map<Long, List<Long>> =
        bookmarkLectureRepository
            .findByLectureIdIn(lectureIds)
            .groupBy({ it.lectureId }, { it.userId })
            .mapValues { (_, userIds) -> userIds.distinct() }

    /** [timetableIds]를 id 순 [TIMETABLE_CHUNK_SIZE]개씩 잠그고 반영한다. 실패한 묶음은 [changes]에 남기고 다음 묶음으로 넘어간다. */
    private fun syncTimetables(
        timetableIds: Collection<Long>,
        notifiableUpdates: List<LectureUpdate>,
        closed: List<Lecture>,
        changes: TimetableChanges,
    ) {
        timetableIds.sorted().chunked(TIMETABLE_CHUNK_SIZE).forEach { chunk ->
            try {
                val counts =
                    retryOnLockFailure("시간표 반영") {
                        checkNotNull(transactionTemplate.execute { syncTimetableChunk(chunk, notifiableUpdates, closed) })
                    }
                changes.add(counts)
            } catch (e: Exception) {
                log.error("sugang sync: 시간표 {}개 반영 실패 (첫 id {})", chunk.size, chunk.first(), e)
                changes.failures += e
            }
        }
    }

    private fun syncTimetableChunk(
        timetableIds: List<Long>,
        notifiableUpdates: List<LectureUpdate>,
        closed: List<Lecture>,
    ): Map<Long, TimetableChangeCount> {
        // 잠근 뒤에 읽는다. 잠그기 전에 본 시간표 내용은 사용자 요청이 그사이 바꿨을 수 있다.
        val timetables = timetableRepository.findAllForUpdateByIdIn(timetableIds)
        if (timetables.isEmpty()) return emptyMap()
        val affectedLectureIds = (notifiableUpdates.map { it.lecture.id!! } + closed.map { it.id!! }).toSet()
        val affected = loadAffectedTimetables(timetables, affectedLectureIds)

        val notifications = mutableListOf<Notification>()
        val timetableChangeCounts = mutableMapOf<Long, TimetableChangeCount>()
        val removedEntryIds = mutableSetOf<Long>()

        notifiableUpdates.forEach { update ->
            val lecture = update.lecture
            val labels = update.changedLabels.joinLocalized()
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                val counts = timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }
                val overwritten =
                    update.classTimesChanged &&
                        affected.overlapsOtherLecture(timetable.id!!, lecture.id!!, update.input.classTimes, removedEntryIds)
                if (overwritten) {
                    removedEntryIds += entry.id!!
                    counts.deleted += 1
                    notifications +=
                        timetableNotification(
                            timetable,
                            NotificationTemplate.TIMETABLE_LECTURE_OVERWRITTEN,
                            NotificationType.LECTURE_REMOVE,
                            lecture.localizedCourseTitle,
                        )
                } else {
                    if (update.classTimesChanged && entry.overrides?.classPlaceAndTimes == null) {
                        timetableLectureReminderService.recomputeForTimetableLecture(entry.id!!, update.input.classTimes)
                    }
                    counts.updated += 1
                    notifications +=
                        timetableNotification(
                            timetable,
                            NotificationTemplate.TIMETABLE_LECTURE_UPDATED,
                            NotificationType.LECTURE_UPDATE,
                            lecture.localizedCourseTitle,
                            labels = labels,
                            deeplink = deeplinkResolver.build(Deeplink.TimetableLecture(timetable.id!!, lecture.id!!)),
                        )
                }
            }
        }

        closed.forEach { lecture ->
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                removedEntryIds += entry.id!!
                timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }.deleted += 1
                notifications +=
                    timetableNotification(
                        timetable,
                        NotificationTemplate.TIMETABLE_LECTURE_CLOSED,
                        NotificationType.LECTURE_REMOVE,
                        lecture.localizedCourseTitle,
                    )
            }
        }

        if (removedEntryIds.isNotEmpty()) {
            timetableLectureRepository.deleteAllByIdInBatch(removedEntryIds)
        }
        notificationRepository.insertAll(notifications)
        timetableRepository.touchUpdatedAt(timetables.map { it.id!! }, clock.instant())
        return timetableChangeCounts
    }

    /**
     * 폐강 강의를 지운다. 강의 행을 잠근 뒤에는 새로 담기지 않으므로(시간표에 담을 때 강의 행을 공유 락으로 읽는다), 그때 아무 시간표에도
     * 없는 강의만 지운다. 담기는 중이라 잠그지 못했거나 그사이 담긴 강의는 그 시간표에서 먼저 빼고 다시 시도한다.
     * 지운 강의만 돌려준다. 관심강좌 폐강 알림은 실제로 지울 때 남긴다.
     */
    private fun deleteClosedLectures(
        closed: List<Lecture>,
        changes: TimetableChanges,
    ): List<Lecture> {
        val deleted = mutableListOf<Lecture>()
        var remaining = closed
        repeat(CLOSE_ATTEMPTS) {
            if (remaining.isEmpty()) return deleted
            val referencingTimetableIds = timetableLectureRepository.findTimetableIdsByLectureIdIn(remaining.map { it.id!! })
            if (referencingTimetableIds.isNotEmpty()) syncTimetables(referencingTimetableIds, emptyList(), remaining, changes)
            val removed = retryOnLockFailure("폐강 강의 삭제") { checkNotNull(transactionTemplate.execute { deleteUnreferenced(remaining) }) }
            val removedIds = removed.map { it.id!! }.toSet()
            deleted += removed
            remaining = remaining.filter { it.id !in removedIds }
        }
        if (remaining.isNotEmpty()) {
            log.warn("sugang sync: 폐강 강의 {}개를 지우지 못했다(시간표에 담기는 중). 다음 동기화에서 다시 닫는다: {}", remaining.size, remaining.map { it.id })
        }
        return deleted
    }

    private fun deleteUnreferenced(lectures: List<Lecture>): List<Lecture> {
        val locked = lectureRepository.lockAllByIdInSkipLocked(lectures.map { it.id!! }).toSet()
        if (locked.isEmpty()) return emptyList()
        // 잠근 뒤의 첫 일반 SELECT라 이 시점에 커밋된 상태를 본다.
        val referenced = timetableLectureRepository.findReferencedLectureIds(locked).toSet()
        val deletable = lectures.filter { it.id in locked && it.id !in referenced }
        if (deletable.isEmpty()) return emptyList()
        val bookmarkUserIds = bookmarkUserIdsByLectureId(deletable.map { it.id!! })
        notificationRepository.insertAll(
            deletable.flatMap { lecture ->
                bookmarkUserIds[lecture.id].orEmpty().map { userId ->
                    bookmarkNotification(userId, lecture, NotificationTemplate.BOOKMARK_LECTURE_CLOSED, NotificationType.LECTURE_REMOVE)
                }
            },
        )
        lectureRepository.deleteAllByIdInBatch(deletable.map { it.id!! })
        // course.latest_lecture_id는 강의 삭제 때 NULL이 되므로 남은 강의로 다시 정한다.
        val courseIds = deletable.mapNotNull { it.courseId }.distinct()
        if (courseIds.isNotEmpty()) courseRepository.refreshLatestLectures(courseIds)
        return deletable
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

    /** 2·3단계에서 커밋된 시간표 변경을 사용자별로 모은다. 푸시는 모든 단계가 끝난 뒤 한 번에 보낸다. */
    private class TimetableChanges {
        val countsByUserId = mutableMapOf<Long, TimetableChangeCount>()
        val failures = mutableListOf<Exception>()

        fun add(counts: Map<Long, TimetableChangeCount>) {
            counts.forEach { (userId, count) ->
                countsByUserId.getOrPut(userId) { TimetableChangeCount() }.apply {
                    updated += count.updated
                    deleted += count.deleted
                }
            }
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

    private fun Lecture.copyEnglishFrom(other: Lecture) {
        courseTitleEn = other.courseTitleEn
        instructorEn = other.instructorEn
        departmentEn = other.departmentEn
        academicYearEn = other.academicYearEn
        categoryEn = other.categoryEn
        classificationEn = other.classificationEn
        remarkEn = other.remarkEn
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val COURSE_LOOKUP_CHUNK_SIZE = 500
        private const val TIMETABLE_CHUNK_SIZE = 100
        private const val LOCK_RETRY_ATTEMPTS = 3
        private const val CLOSE_ATTEMPTS = 5
    }
}
