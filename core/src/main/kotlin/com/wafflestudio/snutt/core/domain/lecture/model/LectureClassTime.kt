package com.wafflestudio.snutt.core.domain.lecture.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
class LectureClassTime(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    var lectureId: Long,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var day: DayOfWeek,
    var place: String? = null,
    @JdbcTypeCode(SqlTypes.SMALLINT)
    var startMinute: Int,
    @JdbcTypeCode(SqlTypes.SMALLINT)
    var endMinute: Int,
) {
    fun toClassPlaceAndTime() = ClassPlaceAndTime(day = day, place = place ?: "", startMinute = startMinute, endMinute = endMinute)
}
