package com.wafflestudio.snutt.core.domain.lecture.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.enums.DayOfWeek

data class ClassPlaceAndTime(
    val day: DayOfWeek,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
) {
    companion object {
        @JsonCreator
        @JvmStatic
        fun of(
            @JsonProperty("day") day: DayOfWeek,
            @JsonProperty("place") place: String?,
            @JsonProperty("startMinute") startMinute: Int,
            @JsonProperty("endMinute") endMinute: Int,
        ) = ClassPlaceAndTime(day, place.orEmpty(), startMinute, endMinute)
    }
}
