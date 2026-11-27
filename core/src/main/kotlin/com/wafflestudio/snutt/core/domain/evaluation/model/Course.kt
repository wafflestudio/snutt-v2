package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity

@Entity
class Course(
    var courseNumber: String,
    var instructor: String,
    var title: String,
    var evalCount: Long = 0,
    var avgRating: Double? = null,
    var avgGradeSatisfaction: Double? = null,
    var avgTeachingSkill: Double? = null,
    var avgGains: Double? = null,
    var avgLifeBalance: Double? = null,
    @Column(insertable = false, updatable = false)
    val latestLectureId: Long? = null,
    @Column(insertable = false, updatable = false)
    val titleEn: String? = null,
    @Column(insertable = false, updatable = false)
    val instructorEn: String? = null,
    @Column(insertable = false, updatable = false)
    val department: String? = null,
    @Column(insertable = false, updatable = false)
    val departmentEn: String? = null,
    @Column(insertable = false, updatable = false)
    val academicYear: String? = null,
    @Column(insertable = false, updatable = false)
    val academicYearEn: String? = null,
    @Column(insertable = false, updatable = false)
    val category: String? = null,
    @Column(insertable = false, updatable = false)
    val categoryEn: String? = null,
    @Column(insertable = false, updatable = false)
    val categoryPre2025: String? = null,
    @Column(insertable = false, updatable = false)
    val classification: String? = null,
    @Column(insertable = false, updatable = false)
    val classificationEn: String? = null,
    @Column(insertable = false, updatable = false)
    val credit: Int? = null,
) : BaseEntity()
