package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.domain.evaluation.model.Course

data class CourseName(
    val title: String,
    val instructor: String,
)

fun Course.nameIn(language: Language): CourseName =
    CourseName(
        title = language.select(title, titleEn),
        instructor = language.select(instructor, instructorEn),
    )
