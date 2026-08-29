package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service

@Entity
@Table(name = "legacy_search_tag")
class LegacySearchTag(
    @Id
    val id: Long,
    @Column(name = "group_name", nullable = false)
    val groupName: String,
    @Column(name = "group_ordering", nullable = false)
    val groupOrdering: Int,
    @Column(name = "group_color")
    val groupColor: String? = null,
    @Column(nullable = false)
    val name: String,
    @Column(nullable = false)
    val ordering: Int,
    @Column(name = "int_value")
    val intValue: Int? = null,
    @Column(name = "string_value")
    val stringValue: String? = null,
)

interface LegacySearchTagRepository : JpaRepository<LegacySearchTag, Long> {
    fun findAllByOrderByGroupOrderingAscOrderingAsc(): List<LegacySearchTag>

    fun findAllByIdIn(ids: Collection<Long>): List<LegacySearchTag>
}

@Service
class LegacySearchTagService(
    private val repository: LegacySearchTagRepository,
) {
    fun searchTagGroups(): List<LegacyEvTagGroupDto> =
        repository
            .findAllByOrderByGroupOrderingAscOrderingAsc()
            .groupBy { Triple(it.groupName, it.groupOrdering, it.groupColor) }
            .map { (group, tags) ->
                val (name, ordering, color) = group
                LegacyEvTagGroupDto(
                    id = ordering,
                    name = name,
                    ordering = ordering,
                    color = color,
                    tags =
                        tags.map {
                            LegacyEvTagDto(
                                id = it.id,
                                name = it.name,
                                description = null,
                                ordering = it.ordering,
                            )
                        },
                )
            }

    fun toCriteria(
        query: String,
        page: Int,
        tagIds: List<Long>,
    ): CourseSearchCriteria {
        if (tagIds.isEmpty()) return CourseSearchCriteria(query = query, page = page)
        val byGroup = repository.findAllByIdIn(tagIds).groupBy { it.groupName }

        fun strings(group: String) = byGroup[group].orEmpty().mapNotNull { it.stringValue ?: it.name }
        return CourseSearchCriteria(
            query = query,
            page = page,
            academicYear = strings(GROUP_ACADEMIC_YEAR),
            classification = strings(GROUP_CLASSIFICATION),
            department = strings(GROUP_DEPARTMENT),
            category = strings(GROUP_CATEGORY),
            credit = byGroup[GROUP_CREDIT].orEmpty().mapNotNull { it.intValue },
            yearSemesters = byGroup[GROUP_SEMESTER].orEmpty().mapNotNull { it.stringValue?.toYearAndSemester() },
        )
    }

    private fun String.toYearAndSemester(): YearAndSemester? {
        val (year, semester) = split(",").takeIf { it.size == 2 } ?: return null
        val semesterValue = semester.trim().toIntOrNull()?.let(Semester::getOfValue) ?: return null
        return YearAndSemester(year.trim().toIntOrNull() ?: return null, semesterValue)
    }

    companion object {
        private const val GROUP_ACADEMIC_YEAR = "학년"
        private const val GROUP_CLASSIFICATION = "구분"
        private const val GROUP_CREDIT = "학점"
        private const val GROUP_DEPARTMENT = "학과"
        private const val GROUP_CATEGORY = "교양분류"
        private const val GROUP_SEMESTER = "학기"
    }
}
