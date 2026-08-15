package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 강의평 도메인 전용 앵커: course_number+instructor 단위, 학기 불변
@Entity
@Table(name = "course")
class Course(
    var courseNumber: String,
    var instructor: String,
    var title: String,
    var department: String? = null,
    var credit: Int? = null,
    var academicYear: String? = null,
    var category: String? = null,
    var classification: String? = null,
    // 강의평 생성/수정/삭제/숨김 트랜잭션 안에서 갱신되는 비정규화 집계 (구 Mongo evInfo의 대체)
    var evalCount: Long = 0,
    var avgRating: Double? = null,
) : BaseEntity()
