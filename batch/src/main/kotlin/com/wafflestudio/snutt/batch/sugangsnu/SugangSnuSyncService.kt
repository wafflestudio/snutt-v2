package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSearchRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
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

data class SugangSnuSyncResult(
    val createdCount: Int,
    val updatedCount: Int,
    val deletedCount: Int,
)

private data class LectureInput(
    val lecture: Lecture,
    val classTimes: List<ClassPlaceAndTime>,
)

private data class LectureUpdate(
    val lecture: Lecture,
    val input: LectureInput,
    val changedLabels: List<String>,
    val notifiable: Boolean,
    val classTimesChanged: Boolean,
)

private data class FieldChange(
    val label: String,
    val notifiable: Boolean,
)

@Service
class SugangSnuSyncService(
    private val lectureRepository: LectureRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
    private val courseRepository: CourseRepository,
    private val courseSearchRepository: CourseSearchRepository,
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
    ): SugangSnuSyncResult {
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
        val timetableChangeCounts =
            transactionTemplate
                .execute {
                    upsertLectures(created, updated)
                    val changeCounts = syncUserLectures(updated, deleted)
                    deleted.forEach(lectureRepository::delete)
                    lectureRepository.flush()
                    val affectedCourses =
                        (courseIdsBeforeSync + (oldLectures + created.map { it.lecture }).mapNotNull { it.courseId })
                            .distinct()
                    courseRepository.refreshLatestLectures(affectedCourses)
                    val latest = courseSearchRepository.findLatestLectures(affectedCourses).associateBy { it.courseId }
                    courseRepository.findAllById(latest.keys.filterNotNull()).forEach { course ->
                        course.title = latest.getValue(course.id!!).courseTitle
                    }
                    changeCounts
                }.orEmpty()

        pushService.sendTargetedPushes(
            timetableChangeCounts.mapValues { (_, counts) ->
                PushMessage(title = "수강편람 업데이트", body = counts.toMessage(), urlScheme = "snutt://notifications")
            },
            PushPreferenceType.LECTURE_UPDATE,
        )

        lectureBuildingSync.sync((created + updated.map { it.input }).flatMap { input -> input.classTimes.map { it.place } })

        log.info("sugang sync: created={} updated={} deleted={}", created.size, updated.size, deleted.size)
        return SugangSnuSyncResult(createdCount = created.size, updatedCount = updated.size, deletedCount = deleted.size)
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
            lectureRepository.save(old)
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
    ): Map<Long, TimetableChangeCount> {
        val notifiableUpdates = updated.filter { it.notifiable }
        val affectedLectureIds = notifiableUpdates.map { it.lecture.id!! } + deleted.map { it.id!! }
        if (affectedLectureIds.isEmpty()) return emptyMap()

        val affected = loadAffectedTimetables(affectedLectureIds)
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
            val labels = update.changedLabels.joinToString()
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
                            "'${lecture.courseTitle}' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
                            NotificationType.LECTURE_REMOVE,
                        )
                } else {
                    if (update.classTimesChanged && entry.overrides?.classPlaceAndTimes == null) {
                        timetableLectureReminderService.recomputeForTimetableLecture(entry.id!!, update.input.classTimes)
                    }
                    counts.updated += 1
                    notifications +=
                        timetableNotification(
                            timetable,
                            "'${lecture.courseTitle}' 강의가 업데이트 되었습니다.(항목: $labels)",
                            NotificationType.LECTURE_UPDATE,
                            deeplink = "snutt://timetable-lecture?timetableId=${timetable.id}&lectureId=${entry.id}",
                        )
                }
            }
            bookmarkUserIds[lecture.id].orEmpty().forEach { userId ->
                notifications +=
                    bookmarkNotification(
                        userId,
                        lecture,
                        "'${lecture.courseTitle}' 강의가 업데이트 되었습니다.(항목: $labels)",
                        NotificationType.LECTURE_UPDATE,
                    )
            }
        }

        deleted.forEach { lecture ->
            affected.forEachEntry(lecture.id!!) { timetable, entry ->
                timetableLectureRepository.deleteByTimetableIdAndId(timetable.id!!, entry.id!!)
                removedEntryIds += entry.id!!
                timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }.deleted += 1
                notifications +=
                    timetableNotification(
                        timetable,
                        "'${lecture.courseTitle}' 강의가 폐강되어 삭제되었습니다.",
                        NotificationType.LECTURE_REMOVE,
                    )
            }
            bookmarkUserIds[lecture.id].orEmpty().forEach { userId ->
                notifications +=
                    bookmarkNotification(userId, lecture, "'${lecture.courseTitle}' 강의가 폐강되어 삭제되었습니다.", NotificationType.LECTURE_REMOVE)
            }
        }

        notificationRepository.saveAll(notifications)
        return timetableChangeCounts.toMap()
    }

    private fun loadAffectedTimetables(lectureIds: List<Long>): AffectedTimetables {
        val affectedEntries = timetableLectureRepository.findByLectureIdIn(lectureIds)
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

    private class TimetableChangeCount(
        var updated: Int = 0,
        var deleted: Int = 0,
    ) {
        fun toMessage(): String =
            when {
                updated > 0 && deleted > 0 -> "강의 ${updated}개가 변경, ${deleted}개가 삭제되었습니다. 알림함에서 자세히 확인하세요."
                updated > 0 -> "강의 ${updated}개가 변경되었습니다. 알림함에서 자세히 확인하세요."
                else -> "강의 ${deleted}개가 삭제되었습니다. 알림함에서 자세히 확인하세요."
            }
    }

    private fun timetableNotification(
        timetable: Timetable,
        message: String,
        type: NotificationType,
        deeplink: String = "snutt://notifications",
    ) = Notification(
        userId = timetable.userId,
        title = "수강편람 업데이트",
        message = "${timetable.year}-${timetable.semester.fullName} '${timetable.title}' 시간표의 $message",
        type = type,
        deeplink = deeplink,
    )

    private fun bookmarkNotification(
        userId: Long,
        lecture: Lecture,
        message: String,
        type: NotificationType,
    ) = Notification(
        userId = userId,
        title = "수강편람 업데이트",
        message = "${lecture.year}-${lecture.semester.fullName} 관심강좌 목록의 $message",
        type = type,
        deeplink = "snutt://bookmarks?year=${lecture.year}&semester=${lecture.semester.value}&lectureId=${lecture.id}",
    )

    private fun changedFields(
        old: Lecture,
        new: Lecture,
        oldTimes: List<ClassPlaceAndTime>,
        newTimes: List<ClassPlaceAndTime>,
    ): List<FieldChange> =
        buildList {
            fun diff(
                label: String,
                notifiable: Boolean,
                a: Any?,
                b: Any?,
            ) {
                if (a != b) add(FieldChange(label, notifiable))
            }
            diff("교과 구분", true, old.classification, new.classification)
            diff("학부", true, old.department, new.department)
            diff("학년", true, old.academicYear, new.academicYear)
            diff("강의명", true, old.courseTitle, new.courseTitle)
            diff("학점", true, old.credit, new.credit)
            diff("교수", true, old.instructor, new.instructor)
            diff("정원", true, old.quota, new.quota)
            diff("기타", true, old.freshmanQuota, new.freshmanQuota)
            diff("비고", true, old.remark, new.remark)
            diff("교양영역", true, old.category, new.category)
            diff("구) 교양영역", true, old.categoryPre2025, new.categoryPre2025)
            diff("강의 시간/장소", true, oldTimes, newTimes)
            diff("교과 구분", false, old.classificationEn, new.classificationEn)
            diff("학부", false, old.departmentEn, new.departmentEn)
            diff("학년", false, old.academicYearEn, new.academicYearEn)
            diff("강의명", false, old.courseTitleEn, new.courseTitleEn)
            diff("교수", false, old.instructorEn, new.instructorEn)
            diff("비고", false, old.remarkEn, new.remarkEn)
            diff("교양영역", false, old.categoryEn, new.categoryEn)
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
