package com.wafflestudio.snutt.core.domain.lecture.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 학기별 분반 단위 강의 (PLAN.md §2 lecture 중심 모델). course_id는 평가 도메인 연결용 FK
@Entity
@Table(name = "lecture")
class Lecture(
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var courseNumber: String,
    var lectureNumber: String,
    var courseTitle: String,
    var instructor: String? = null,
    var department: String? = null,
    var academicYear: String? = null,
    var category: String? = null,
    var categoryPre2025: String? = null,
    var classification: String? = null,
    var credit: Int = 0,
    var quota: Int = 0,
    var freshmanQuota: Int? = null,
    // TEXT. 수강편람 원문 그대로 보관 (ⓔ/ⓜⓞ/권장과목 마커 포함)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var remark: String? = null,
    var registrationCount: Int = 0,
    var wasFull: Boolean = false,
    @Column(name = "course_id")
    var courseId: Long? = null,
    // 표시용 사본. 시간 필터 검색은 lecture_class_time 정규화 테이블 사용
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var classPlaceAndTime: List<ClassPlaceAndTime> = emptyList(),
) : ExternalIdEntity() {
    // 수강스누 sync diff 기준 (v1 Lecture.equalsMetadata 이식). 집계/신청 인원은 제외
    fun equalsMetadata(other: Lecture): Boolean =
        academicYear == other.academicYear &&
            category == other.category &&
            categoryPre2025 == other.categoryPre2025 &&
            classPlaceAndTime == other.classPlaceAndTime &&
            classification == other.classification &&
            credit == other.credit &&
            department == other.department &&
            instructor == other.instructor &&
            lectureNumber == other.lectureNumber &&
            quota == other.quota &&
            freshmanQuota == other.freshmanQuota &&
            remark == other.remark &&
            semester == other.semester &&
            year == other.year &&
            courseNumber == other.courseNumber &&
            courseTitle == other.courseTitle
}
