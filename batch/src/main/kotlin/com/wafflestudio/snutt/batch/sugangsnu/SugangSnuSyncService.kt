package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.notification.service.TargetedPush
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.ClassTimeUtils
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
    private val lectureRegistrationStatusRepository: LectureRegistrationStatusRepository,
    private val courseRepository: CourseRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableRepository: TimetableRepository,
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val notificationRepository: NotificationRepository,
    private val pushService: PushService,
    private val lectureBuildingSync: LectureBuildingSync,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun sync(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
    ): SugangSnuSyncResult {
        val oldLectures = lectureRepository.findByYearAndSemester(year, semester)
        val oldMap = oldLectures.associateBy { it.courseNumber to it.lectureNumber }
        val newKeys = rows.map { it.courseNumber to it.lectureNumber }.toSet()
        val oldClassTimesMap =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(oldLectures.mapNotNull { it.id })
                .groupBy({ it.lectureId!! }, { it.toClassPlaceAndTime() })

        val created =
            rows
                .filter { (it.courseNumber to it.lectureNumber) !in oldMap }
                .map { LectureInput(it.toLecture(year, semester), it.classPlaceAndTimes) }
        val updated =
            rows.mapNotNull { row ->
                val old = oldMap[row.courseNumber to row.lectureNumber] ?: return@mapNotNull null
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
        val deleted = oldLectures.filter { (it.courseNumber to it.lectureNumber) !in newKeys }

        // DB 변경은 하나의 짧은 트랜잭션으로 묶고, 푸시는 커밋된 뒤에 보낸다(롤백 시 유령 알림 방지)
        var timetableChangeCounts: Map<Long, TimetableChangeCount> = emptyMap()
        transactionTemplate.executeWithoutResult {
            upsertLectures(created, updated)
            val lectureByKey =
                oldMap + created.associateBy { it.lecture.courseNumber to it.lecture.lectureNumber }.mapValues { it.value.lecture }
            syncRegistrationCounts(year, semester, rows, lectureByKey)
            timetableChangeCounts = syncUserLectures(updated, deleted)
            deleted.forEach(lectureRepository::delete)
        }

        runCatching {
            lectureBuildingSync.sync((created + updated.map { it.input }).flatMap { input -> input.classTimes.map { it.place } })
        }.onFailure { log.error("강의 건물 갱신 실패: {}", it.message) }

        pushService.sendTargetedPushes(
            timetableChangeCounts.mapValues { (_, counts) ->
                TargetedPush(title = "수강편람 업데이트", body = counts.toMessage(), urlScheme = "snutt://notifications")
            },
            PushPreferenceType.LECTURE_UPDATE,
        )

        log.info("sugang sync: created={} updated={} deleted={}", created.size, updated.size, deleted.size)
        return SugangSnuSyncResult(createdCount = created.size, updatedCount = updated.size, deletedCount = deleted.size)
    }

    private fun upsertLectures(
        created: List<LectureInput>,
        updated: List<LectureUpdate>,
    ) {
        created.forEach { input ->
            val lecture = input.lecture
            lecture.courseId = resolveCourseId(lecture)
            lectureRepository.save(lecture)
            saveClassTimes(lecture, input.classTimes)
        }
        updated.forEach { update ->
            val old = update.lecture
            val instructorChanged = old.instructor != update.input.lecture.instructor
            old.copyMetadataFrom(update.input.lecture)
            // 강사가 바뀌면 과목-교수 정체성이 바뀐 것이므로 기존 코스와의 연결을 끊고 새 코스로 연결한다(기존 평가는 기존 코스에 남는다)
            if (instructorChanged || old.courseId == null) {
                old.courseId = resolveCourseId(old)
            }
            if (update.classTimesChanged) {
                lectureClassTimeRepository.deleteByLectureId(old.id!!)
                saveClassTimes(old, update.input.classTimes)
            }
        }
    }

    // was_full은 크롤러 소유라 건드리지 않는다
    private fun syncRegistrationCounts(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
        lectureByKey: Map<Pair<String, String>, Lecture>,
    ) {
        val statuses = lectureRegistrationStatusRepository.findByYearAndSemester(year, semester).associateBy { it.lectureId }
        rows.forEach { row ->
            val lectureId = lectureByKey[row.courseNumber to row.lectureNumber]?.id ?: return@forEach
            val status = statuses[lectureId]
            if (status == null) {
                lectureRegistrationStatusRepository.save(
                    LectureRegistrationStatus(lectureId = lectureId, registrationCount = row.registrationCount),
                )
            } else {
                status.registrationCount = row.registrationCount
            }
        }
    }

    private fun resolveCourseId(lecture: Lecture): Long? {
        val instructor = lecture.instructor?.takeIf { it.isNotBlank() } ?: return null
        val course =
            courseRepository.findByCourseNumberAndInstructor(lecture.courseNumber, instructor)
                ?: courseRepository.save(
                    Course(
                        courseNumber = lecture.courseNumber,
                        instructor = instructor,
                        title = lecture.courseTitle,
                        department = lecture.department,
                        credit = lecture.credit,
                        academicYear = lecture.academicYear,
                        category = lecture.category,
                        classification = lecture.classification,
                    ),
                )
        return course.id
    }

    private fun saveClassTimes(
        lecture: Lecture,
        classTimes: List<ClassPlaceAndTime>,
    ) {
        lectureClassTimeRepository.saveAll(
            classTimes.map {
                LectureClassTime(lecture = lecture, day = it.day, place = it.place, startMinute = it.startMinute, endMinute = it.endMinute)
            },
        )
    }

    private fun syncUserLectures(
        updated: List<LectureUpdate>,
        deleted: List<Lecture>,
    ): Map<Long, TimetableChangeCount> {
        val notifications = mutableListOf<Notification>()
        val timetableChangeCounts = mutableMapOf<Long, TimetableChangeCount>()

        updated.filter { it.notifiable }.forEach { update ->
            val lecture = update.lecture
            val labels = update.changedLabels.joinToString()
            forEachContainingTimetable(lecture) { timetable, entryId ->
                val counts = timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }
                if (update.classTimesChanged && overlapsOtherLecture(timetable, lecture, update.input.classTimes)) {
                    timetableLectureRepository.deleteByTimetableIdAndId(timetable.id!!, entryId)
                    counts.deleted += 1
                    notifications +=
                        timetableNotification(
                            timetable,
                            "'${lecture.courseTitle}' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
                            NotificationType.LECTURE_REMOVE,
                        )
                } else {
                    counts.updated += 1
                    notifications +=
                        timetableNotification(
                            timetable,
                            "'${lecture.courseTitle}' 강의가 업데이트 되었습니다.(항목: $labels)",
                            NotificationType.LECTURE_UPDATE,
                            deeplink = "snutt://timetable-lecture?timetableId=${timetable.id}&lectureId=$entryId",
                        )
                }
            }
            forEachContainingBookmark(lecture) { userId ->
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
            forEachContainingTimetable(lecture) { timetable, entryId ->
                timetableLectureRepository.deleteByTimetableIdAndId(timetable.id!!, entryId)
                timetableChangeCounts.getOrPut(timetable.userId) { TimetableChangeCount() }.deleted += 1
                notifications +=
                    timetableNotification(
                        timetable,
                        "'${lecture.courseTitle}' 강의가 폐강되어 삭제되었습니다.",
                        NotificationType.LECTURE_REMOVE,
                    )
            }
            forEachContainingBookmark(lecture) { userId ->
                notifications +=
                    bookmarkNotification(userId, lecture, "'${lecture.courseTitle}' 강의가 폐강되어 삭제되었습니다.", NotificationType.LECTURE_REMOVE)
            }
        }

        notificationRepository.saveAll(notifications)
        return timetableChangeCounts.toMap()
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

    private fun forEachContainingTimetable(
        lecture: Lecture,
        action: (Timetable, Long) -> Unit,
    ) {
        val entries = timetableLectureRepository.findByLectureIdIn(listOf(lecture.id!!))
        if (entries.isEmpty()) return
        val timetables = timetableRepository.findAllById(entries.map { it.timetableId }.distinct()).associateBy { it.id!! }
        entries.forEach { entry -> timetables[entry.timetableId]?.let { action(it, entry.id!!) } }
    }

    private fun forEachContainingBookmark(
        lecture: Lecture,
        action: (userId: Long) -> Unit,
    ) {
        bookmarkLectureRepository
            .findByLectureIdIn(listOf(lecture.id!!))
            .asSequence()
            .map { it.userId }
            .distinct()
            .forEach(action)
    }

    private fun overlapsOtherLecture(
        timetable: Timetable,
        lecture: Lecture,
        newTimes: List<ClassPlaceAndTime>,
    ): Boolean {
        val entries = timetableLectureRepository.findByTimetableId(timetable.id!!)
        if (entries.any { it.lectureId == lecture.id && it.overrides?.classPlaceAndTimes != null }) return false
        val otherEntries = entries.filter { it.lectureId != lecture.id }
        val otherLectureTimes =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(otherEntries.mapNotNull { it.lectureId })
                .groupBy({ it.lectureId!! }, { it.toClassPlaceAndTime() })
        return otherEntries.any { entry ->
            val times = entry.overrides?.classPlaceAndTimes ?: entry.lectureId?.let { otherLectureTimes[it] }.orEmpty()
            ClassTimeUtils.timesOverlap(times, newTimes)
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
        instructor = instructor,
        department = department,
        academicYear = academicYear,
        category = category,
        classification = classification,
        credit = credit,
        quota = quota,
        freshmanQuota = freshmanQuota,
        remark = remark,
        categoryPre2025 = categoryPre2025,
        courseTitleEn = courseTitleEn,
        instructorEn = instructorEn,
        departmentEn = departmentEn,
        academicYearEn = academicYearEn,
        categoryEn = categoryEn,
        classificationEn = classificationEn,
        remarkEn = remarkEn,
    )
}
