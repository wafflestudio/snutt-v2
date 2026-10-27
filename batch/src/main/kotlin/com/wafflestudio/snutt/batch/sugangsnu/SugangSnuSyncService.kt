package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.client.joinLocalized
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.enums.yearAndSemesterLabel
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
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun sync(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
    ) {
        val synced = checkNotNull(transactionTemplate.execute { syncLectures(year, semester, rows) })

        pushService.sendTargetedPushes(
            synced.timetableChangeCounts.mapValues { (_, counts) ->
                counts.toMessage().copy(urlScheme = "snutt://notifications")
            },
            PushPreferenceType.LECTURE_UPDATE,
        )

        val inputs = synced.created + synced.updated.map { it.input }
        lectureBuildingSync.sync(inputs.flatMap { input -> input.classTimes.map { it.place } })

        log.info("sugang sync: created={}, updated={}, deleted={}", synced.created.size, synced.updated.size, synced.deleted.size)
    }

    private fun syncLectures(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
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
                val new = row.toLecture(year, semester)
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

        val courseIdsBeforeSync = oldLectures.mapNotNull { it.courseId }
        upsertLectures(created, updated)
        val affectedEntries = timetableLectureRepository.findByLectureIdIn(updated.map { it.lecture.id!! } + deleted.map { it.id!! })
        val timetableChangeCounts = syncUserLectures(updated, deleted, affectedEntries)
        deleted.forEach(lectureRepository::delete)
        lectureRepository.flush()
        val changedTimetableIds = affectedEntries.map { it.timetableId }.distinct()
        if (changedTimetableIds.isNotEmpty()) timetableRepository.touchUpdatedAt(changedTimetableIds, Instant.now())
        val affectedCourses =
            (courseIdsBeforeSync + (oldLectures + created.map { it.lecture }).mapNotNull { it.courseId })
                .distinct()
        courseRepository.refreshLatestLectures(affectedCourses)
        return SyncedLectures(created, updated, deleted, timetableChangeCounts)
    }

    private fun upsertLectures(
        created: List<LectureInput>,
        updated: List<LectureUpdate>,
    ) {
        val courses = loadCourses(created.map { it.lecture } + updated.map { it.input.lecture })
        created.forEach { input ->
            val lecture = input.lecture
            lecture.courseId = resolveCourseId(lecture, courses)
            lectureRepository.save(lecture)
            saveClassTimes(lecture, input.classTimes)
        }
        updated.forEach { update ->
            val old = update.lecture
            val instructorChanged = old.instructor != update.input.lecture.instructor
            old.copyMetadataFrom(update.input.lecture)
            if (instructorChanged || old.courseId == null) {
                old.courseId = resolveCourseId(old, courses)
            }
            if (update.classTimesChanged) {
                lectureClassTimeRepository.deleteByLectureId(old.id!!)
                saveClassTimes(old, update.input.classTimes)
            }
        }
    }

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

    private fun saveClassTimes(
        lecture: Lecture,
        classTimes: List<ClassPlaceAndTime>,
    ) {
        lectureClassTimeRepository.saveAll(
            classTimes.map {
                LectureClassTime(
                    lectureId = lecture.id!!,
                    day = it.day,
                    place = it.place,
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                )
            },
        )
    }

    private fun syncUserLectures(
        updated: List<LectureUpdate>,
        deleted: List<Lecture>,
        changedEntries: List<TimetableLecture>,
    ): Map<Long, TimetableChangeCount> {
        val notifiableUpdates = updated.filter { it.notifiable }
        val affectedLectureIds = (notifiableUpdates.map { it.lecture.id!! } + deleted.map { it.id!! }).toSet()
        if (affectedLectureIds.isEmpty()) return emptyMap()

        val affected = loadAffectedTimetables(changedEntries.filter { it.lectureId in affectedLectureIds })
        val bookmarkUserIds =
            bookmarkLectureRepository
                .findByLectureIdIn(affectedLectureIds)
                .groupBy({ it.lectureId }, { it.userId })
                .mapValues { (_, userIds) -> userIds.distinct() }

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
                    timetableLectureRepository.deleteByTimetableIdAndId(timetable.id!!, entry.id!!)
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
                            deeplink = "snutt://timetable-lecture?timetableId=${timetable.id}&lectureId=${entry.id}",
                        )
                }
            }
            bookmarkUserIds[lecture.id].orEmpty().forEach { userId ->
                notifications +=
                    bookmarkNotification(
                        userId,
                        lecture,
                        NotificationTemplate.BOOKMARK_LECTURE_UPDATED,
                        NotificationType.LECTURE_UPDATE,
                        labels = labels,
                    )
            }
        }

        deleted.forEach { lecture ->
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                timetableLectureRepository.deleteByTimetableIdAndId(timetable.id!!, entry.id!!)
                timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }.deleted += 1
                notifications +=
                    timetableNotification(
                        timetable,
                        NotificationTemplate.TIMETABLE_LECTURE_CLOSED,
                        NotificationType.LECTURE_REMOVE,
                        lecture.localizedCourseTitle,
                    )
            }
            bookmarkUserIds[lecture.id].orEmpty().forEach { userId ->
                notifications +=
                    bookmarkNotification(
                        userId,
                        lecture,
                        NotificationTemplate.BOOKMARK_LECTURE_CLOSED,
                        NotificationType.LECTURE_REMOVE,
                    )
            }
        }

        notificationRepository.saveAll(notifications)
        return timetableChangeCounts.toMap()
    }

    private fun loadAffectedTimetables(affectedEntries: List<TimetableLecture>): AffectedTimetables {
        val timetablesById =
            timetableRepository.findAllById(affectedEntries.map { it.timetableId }.distinct()).associateBy { it.id!! }
        val entriesByTimetableId =
            timetableLectureRepository.findByTimetableIdIn(timetablesById.keys).groupBy { it.timetableId }
        val classTimesByLectureId =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(
                    entriesByTimetableId.values
                        .flatten()
                        .mapNotNull { it.lectureId }
                        .distinct(),
                ).groupBy({ it.lectureId }, { it.toClassPlaceAndTime() })
        return AffectedTimetables(
            timetablesById = timetablesById,
            entriesByLectureId = affectedEntries.filter { it.timetableId in timetablesById }.groupBy { it.lectureId!! },
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
        val timetableChangeCounts: Map<Long, TimetableChangeCount>,
    )

    private class TimetableChangeCount(
        var updated: Int = 0,
        var deleted: Int = 0,
    ) {
        fun toMessage(): LocalizedPushMessage =
            when {
                updated > 0 && deleted > 0 -> NotificationTemplate.COURSEBOOK_UPDATE_CHANGED_AND_DELETED.render(updated, deleted)
                updated > 0 -> NotificationTemplate.COURSEBOOK_UPDATE_CHANGED.render(updated)
                else -> NotificationTemplate.COURSEBOOK_UPDATE_DELETED.render(deleted)
            }
    }

    private fun timetableNotification(
        timetable: Timetable,
        template: NotificationTemplate,
        type: NotificationType,
        courseTitle: LocalizedMessage,
        labels: LocalizedMessage? = null,
        deeplink: String = "snutt://notifications",
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
            .copy(urlScheme = "snutt://bookmarks?year=${lecture.year}&semester=${lecture.semester.value}&lectureId=${lecture.id}")
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

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val COURSE_LOOKUP_CHUNK_SIZE = 500
    }
}
