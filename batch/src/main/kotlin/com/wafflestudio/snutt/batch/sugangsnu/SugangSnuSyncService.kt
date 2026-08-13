package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.tag.model.TagCollection
import com.wafflestudio.snutt.core.domain.tag.model.TagList
import com.wafflestudio.snutt.core.domain.tag.repository.TagListRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class SugangSnuSyncResult(
    val createdCount: Int,
    val updatedCount: Int,
    val deletedCount: Int,
)

// lecture 행과 시간(class_time 테이블)을 함께 나르는 입력
private data class LectureInput(
    val lecture: Lecture,
    val classTimes: List<ClassPlaceAndTime>,
)

// 수강스누 sync: lecture/lecture_class_time/course 3계층을 한 트랜잭션에 upsert하고
// tag_list를 재생성한다. 스냅샷·북마크 전파 스텝은 v2에서 삭제 (PLAN.md §4)
@Service
class SugangSnuSyncService(
    private val lectureRepository: LectureRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
    private val courseRepository: CourseRepository,
    private val tagListRepository: TagListRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableRepository: TimetableRepository,
    private val notificationRepository: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
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

        val created = rows.filter { (it.courseNumber to it.lectureNumber) !in oldMap }
        val updated =
            rows
                .mapNotNull { row ->
                    val old = oldMap[row.courseNumber to row.lectureNumber] ?: return@mapNotNull null
                    val new = row.toLecture(year, semester)
                    val unchanged = old.equalsMetadata(new) && oldClassTimesMap[old.id].orEmpty() == row.classPlaceAndTimes
                    if (unchanged) null else old to LectureInput(new, row.classPlaceAndTimes)
                }
        val deleted = oldLectures.filter { (it.courseNumber to it.lectureNumber) !in newKeys }

        upsertLectures(
            year,
            semester,
            created.map { LectureInput(it.toLecture(year, semester), it.classPlaceAndTimes) },
            updated,
        )
        if (deleted.isNotEmpty()) {
            deleteLectures(deleted)
        }
        rebuildTagList(year, semester, rows)

        log.info("sugang sync: created={} updated={} deleted={}", created.size, updated.size, deleted.size)
        return SugangSnuSyncResult(createdCount = created.size, updatedCount = updated.size, deletedCount = deleted.size)
    }

    private fun upsertLectures(
        year: Int,
        semester: Semester,
        created: List<LectureInput>,
        updated: List<Pair<Lecture, LectureInput>>,
    ) {
        // course 앵커 upsert 후 lecture.course_id 연결 (PLAN.md §2)
        val courses = mutableMapOf<Pair<String, String>, Course>()

        fun resolveCourse(lecture: Lecture): Long? {
            if (lecture.instructor.isNullOrBlank()) return null
            return courses
                .getOrPut(lecture.courseNumber to lecture.instructor!!) {
                    courseRepository.findByCourseNumberAndInstructor(lecture.courseNumber, lecture.instructor!!)
                        ?: courseRepository.save(
                            Course(
                                courseNumber = lecture.courseNumber,
                                instructor = lecture.instructor!!,
                                title = lecture.courseTitle,
                                department = lecture.department,
                                credit = lecture.credit,
                                academicYear = lecture.academicYear,
                                category = lecture.category,
                                classification = lecture.classification,
                            ),
                        )
                }.id
        }

        created.forEach { input ->
            val lecture = input.lecture
            lecture.courseId = resolveCourse(lecture)
            lecture.wasFull = lecture.registrationCount >= lecture.quota
            lectureRepository.save(lecture)
            syncClassTimes(lecture, input.classTimes)
        }
        updated.forEach { (old, input) ->
            val new = input.lecture
            old.apply {
                courseId = old.courseId ?: resolveCourse(new)
                registrationCount = new.registrationCount
                wasFull = new.registrationCount >= new.quota
                academicYear = new.academicYear
                category = new.category
                categoryPre2025 = new.categoryPre2025
                classification = new.classification
                credit = new.credit
                department = new.department
                instructor = new.instructor
                lectureNumber = new.lectureNumber
                quota = new.quota
                freshmanQuota = new.freshmanQuota
                remark = new.remark
                courseNumber = new.courseNumber
                courseTitle = new.courseTitle
            }
            lectureClassTimeRepository.deleteByLectureId(old.id!!)
            syncClassTimes(old, input.classTimes)
            notifyLectureChange(NotificationType.LECTURE_UPDATE, old)
        }
    }

    private fun syncClassTimes(
        lecture: Lecture,
        classTimes: List<ClassPlaceAndTime>,
    ) {
        lectureClassTimeRepository.saveAll(
            classTimes.map {
                LectureClassTime(lecture = lecture, day = it.day, place = it.place, startMinute = it.startMinute, endMinute = it.endMinute)
            },
        )
    }

    private fun deleteLectures(deleted: List<Lecture>) {
        deleted.forEach { lecture ->
            notifyLectureChange(NotificationType.LECTURE_REMOVE, lecture)
            lectureRepository.delete(lecture)
        }
    }

    // 강의 정보 변경/폐지를 해당 강의를 시간표에 담은 사용자에게 알린다 (v1 이벤트 알림 이식)
    private fun notifyLectureChange(
        type: NotificationType,
        lecture: Lecture,
    ) {
        val timetableIds = timetableLectureRepository.findByLectureIdIn(listOf(lecture.id!!)).map { it.timetableId }.toSet()
        if (timetableIds.isEmpty()) return
        val userIds = timetableRepository.findAllById(timetableIds).mapNotNull { it.userId }.toSet()
        val message =
            when (type) {
                NotificationType.LECTURE_UPDATE -> "'${lecture.courseTitle}' 강의 정보가 변경되었습니다"
                NotificationType.LECTURE_REMOVE -> "'${lecture.courseTitle}' 강의가 폐지되었습니다"
                else -> return
            }
        notificationRepository.saveAll(
            userIds.map { userId ->
                Notification(userId = userId, title = "강의 정보 변경", message = message, type = type)
            },
        )
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
        remark = remark,
        registrationCount = registrationCount,
        categoryPre2025 = categoryPre2025,
    )

    private fun rebuildTagList(
        year: Int,
        semester: Semester,
        rows: List<SugangLectureRow>,
    ) {
        val tagCollection =
            TagCollection(
                academicYear = rows.map { it.academicYear }.filter { it.length > 1 }.sorted(),
                classification = rows.map { it.classification }.filter { it.isNotBlank() }.sorted(),
                department = rows.map { it.department }.filter { it.isNotBlank() }.sorted(),
                credit =
                    rows
                        .map { it.credit }
                        .distinct()
                        .sorted()
                        .map { "${it}학점" },
                instructor = rows.map { it.instructor }.filter { it.isNotBlank() }.sorted(),
                category = rows.map { it.category }.filter { it.isNotBlank() }.sorted(),
            )
        val tagList =
            tagListRepository.findByYearAndSemester(year, semester)
                ?: TagList(year = year, semester = semester, tagCollection = tagCollection)
        tagList.tagCollection = tagCollection
        tagListRepository.save(tagList)
    }
}
