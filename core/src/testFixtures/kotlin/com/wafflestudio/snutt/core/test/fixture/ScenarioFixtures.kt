package com.wafflestudio.snutt.core.test.fixture

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 학기 개설 → 강의 → 사용자 → 시간표 → 시간표에 강의 추가를 리포지토리로 바로 저장하는 시나리오 빌더.
 * 서비스 규칙(시간 겹침 검사, 리마인더 재계산 등)을 거치지 않으므로, 그 규칙 자체를 검증할 때는 서비스나 API를 호출한다.
 *
 * ```
 * val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
 * val lecture = fixtures.lecture(autumn, "운영체제", times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675)))
 * val user = fixtures.user("alice")
 * val timetable = fixtures.timetable(user, autumn)
 * fixtures.addLecture(timetable, lecture)
 * ```
 */
class ScenarioFixtures(
    private val coursebookRepository: CoursebookRepository,
    private val lectureRepository: LectureRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
    private val userRepository: UserRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
) {
    private val sequence = AtomicInteger()

    private fun next(): Int = sequence.incrementAndGet()

    fun coursebook(
        year: Int = 2026,
        semester: Semester = Semester.AUTUMN,
    ): Coursebook = coursebookRepository.save(Coursebook(year = year, semester = semester))

    /** [configure]로 Lecture의 나머지 필드(instructor, quota, courseTitleEn 등)를 채운다. */
    fun lecture(
        coursebook: Coursebook,
        courseTitle: String = "강의${next()}",
        courseNumber: String = "F${next()}",
        lectureNumber: String = "001",
        credit: Int = 3,
        times: List<ClassPlaceAndTime> = emptyList(),
        configure: Lecture.() -> Unit = {},
    ): Lecture =
        saveLectureWithTimes(
            lectureRepository,
            lectureClassTimeRepository,
            Lecture(
                year = coursebook.year,
                semester = coursebook.semester,
                courseNumber = courseNumber,
                lectureNumber = lectureNumber,
                courseTitle = courseTitle,
                credit = credit,
            ).apply(configure),
            times,
        )

    fun user(
        localId: String = "user${next()}",
        email: String? = "$localId@snu.ac.kr",
        configure: User.() -> Unit = {},
    ): User =
        userRepository.save(
            User(
                email = email,
                isEmailVerified = email != null,
                nickname = localId,
                nicknameTag = "0000",
                localId = localId,
            ).apply(configure),
        )

    fun timetable(
        user: User,
        coursebook: Coursebook,
        title: String = "나의 시간표",
        isPrimary: Boolean = true,
        themeId: Long = 2L,
    ): Timetable =
        timetableRepository.save(
            Timetable(
                userId = user.id!!,
                year = coursebook.year,
                semester = coursebook.semester,
                title = title,
                themeId = themeId,
                isPrimary = isPrimary,
            ),
        )

    fun addLecture(
        timetable: Timetable,
        lecture: Lecture,
        paletteIndex: Int = 0,
    ): TimetableLecture =
        timetableLectureRepository.save(
            TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, paletteIndex = paletteIndex),
        )
}

/** AbstractIntegrationTest가 import한다. */
@TestConfiguration(proxyBeanMethods = false)
class ScenarioFixturesConfig {
    @Bean
    fun scenarioFixtures(
        coursebookRepository: CoursebookRepository,
        lectureRepository: LectureRepository,
        lectureClassTimeRepository: LectureClassTimeRepository,
        userRepository: UserRepository,
        timetableRepository: TimetableRepository,
        timetableLectureRepository: TimetableLectureRepository,
    ) = ScenarioFixtures(
        coursebookRepository,
        lectureRepository,
        lectureClassTimeRepository,
        userRepository,
        timetableRepository,
        timetableLectureRepository,
    )
}
