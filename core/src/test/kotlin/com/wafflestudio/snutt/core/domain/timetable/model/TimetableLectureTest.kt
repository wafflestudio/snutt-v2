package com.wafflestudio.snutt.core.domain.timetable.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimetableLectureTest {
    @Test
    fun updateOverridesOnFreshLecturePopulatesTheField() {
        val lecture = TimetableLecture(timetableId = 1L, lectureId = 5L)
        lecture.updateOverrides { it.copy(courseTitle = "수정된 이름") }
        assertThat(lecture.overrides).isEqualTo(LectureOverrides(courseTitle = "수정된 이름"))
    }

    @Test
    fun updateOverridesMergesWithoutLosingEarlierFields() {
        val lecture = TimetableLecture(timetableId = 1L, lectureId = 5L)
        lecture.updateOverrides { it.copy(courseTitle = "수정된 이름") }
        lecture.updateOverrides { it.copy(instructor = "수정된 교수") }
        assertThat(lecture.overrides).isEqualTo(LectureOverrides(courseTitle = "수정된 이름", instructor = "수정된 교수"))
    }

    @Test
    fun updateOverridesAfterClearStartsFromEmpty() {
        val lecture = TimetableLecture(timetableId = 1L, lectureId = 5L)
        lecture.updateOverrides { it.copy(courseTitle = "수정된 이름", instructor = "교수") }
        lecture.clearOverrides()
        lecture.updateOverrides { it.copy(instructor = "새 교수") }
        assertThat(lecture.overrides).isEqualTo(LectureOverrides(instructor = "새 교수"))
    }

    @Test
    fun copyForRetargetsAndCarriesOverrides() {
        val lecture = TimetableLecture(timetableId = 1L, lectureId = 5L, colorIndex = 3)
        lecture.updateOverrides { it.copy(courseTitle = "수정된 이름") }
        val copied = lecture.copyFor(99L)
        assertThat(copied.timetableId).isEqualTo(99L)
        assertThat(copied.lectureId).isEqualTo(5L)
        assertThat(copied.overrides).isEqualTo(LectureOverrides(courseTitle = "수정된 이름"))
        assertThat(copied.colorIndex).isEqualTo(3)
    }
}
