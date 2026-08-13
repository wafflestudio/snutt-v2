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

// lecture.class_place_and_time JSON의 검색용 정규화 사본. 수강스누 sync가 같은 트랜잭션에서 함께 쓴다
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
    // QueryDSL 상관 서브쿼리용 읽기 전용 FK (lecture_id 컬럼의 두 번째 매핑)
    @Column(name = "lecture_id", insertable = false, updatable = false)
    var lectureId: Long? = null

    fun toClassPlaceAndTime() = ClassPlaceAndTime(day = day, place = place ?: "", startMinute = startMinute, endMinute = endMinute)
}
