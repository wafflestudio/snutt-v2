package com.wafflestudio.snutt.core.domain.vacancy.dto

import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus

data class VacancyLectureDisplay(
    val lecture: Lecture,
    val status: LectureRegistrationStatus?,
)
