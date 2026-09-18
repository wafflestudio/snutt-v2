package com.wafflestudio.snutt.core.domain.lecture.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
class LectureRegistrationStatus(
    @Id
    val lectureId: Long,
    var registrationCount: Int = 0,
    var wasFull: Boolean = false,
) {
    @UpdateTimestamp
    @Column(nullable = false)
    val updatedAt: Instant? = null
}
