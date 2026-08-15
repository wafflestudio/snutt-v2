package com.wafflestudio.snutt.api.testutil

import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository

fun saveLectureWithTimes(
    lectureRepository: LectureRepository,
    classTimeRepository: LectureClassTimeRepository,
    lecture: Lecture,
    times: List<ClassPlaceAndTime>,
): Lecture {
    val saved = lectureRepository.save(lecture)
    classTimeRepository.saveAll(
        times.map {
            LectureClassTime(
                lecture = saved,
                day = it.day,
                place = it.place,
                startMinute = it.startMinute,
                endMinute = it.endMinute,
            )
        },
    )
    return saved
}
