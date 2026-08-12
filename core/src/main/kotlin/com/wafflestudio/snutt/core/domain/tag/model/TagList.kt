package com.wafflestudio.snutt.core.domain.tag.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 수강스누 sync가 학기 시작 전 생성하는 검색 태그 모음 (통째 읽기만 하는 JSON)
data class TagCollection(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<String>,
    val instructor: List<String>,
    val category: List<String>,
    val categoryPre2025: List<String> = emptyList(),
)

@Entity
@Table(name = "tag_list")
class TagList(
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var tagCollection: TagCollection,
) : BaseEntity()
