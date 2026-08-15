package com.wafflestudio.snutt.core.domain.lecture.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/**
 * 수강신청 기간의 변동 상태. 빈자리 크롤러가 분 단위로 갱신하므로 고정 정보(lecture)와 분리한다.
 * was_full은 수강스누의 취소여석 마커로, 크롤러만 세운다.
 */
@Entity
@Table(name = "lecture_registration_status")
class LectureRegistrationStatus(
    @Id
    @Column(name = "lecture_id")
    val lectureId: Long,
    var registrationCount: Int = 0,
    var wasFull: Boolean = false,
) {
    @UpdateTimestamp
    @Column(nullable = false)
    val updatedAt: Instant? = null
}
