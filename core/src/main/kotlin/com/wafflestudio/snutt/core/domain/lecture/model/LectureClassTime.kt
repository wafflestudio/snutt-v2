package com.wafflestudio.snutt.core.domain.lecture.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "lecture_class_time")
class LectureClassTime(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_id")
    val lecture: Lecture,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var day: DayOfWeek,
    var place: String? = null,
    @JdbcTypeCode(SqlTypes.SMALLINT)
    var startMinute: Int,
    @JdbcTypeCode(SqlTypes.SMALLINT)
    var endMinute: Int,
) {
    @Column(name = "lecture_id", insertable = false, updatable = false)
    var lectureId: Long? = null

    fun toClassPlaceAndTime() = ClassPlaceAndTime(day = day, place = place ?: "", startMinute = startMinute, endMinute = endMinute)
}
