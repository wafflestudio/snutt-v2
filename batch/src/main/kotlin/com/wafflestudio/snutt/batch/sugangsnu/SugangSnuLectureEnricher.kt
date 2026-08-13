package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

// xlsx 행에 상세 API(강좌 팝업) 정보를 덮어쓴다 (v1 SugangSnuFetchService 이식).
// xlsx가 주지 않는 정확한 시간/강의실/교양분류/학과를 채운다.
@Component
class SugangSnuLectureEnricher(
    private val sugangSnuLectureApi: SugangSnuLectureApi,
    resourceLoader: ResourceLoader,
) {
    private val categoryPre2025Map: Map<String, String> by lazy {
        resourceLoader
            .getResource("classpath:categoryPre2025.txt")
            .inputStream
            .bufferedReader()
            .lineSequence()
            .filter { it.contains(":") }
            .associate { line ->
                val (courseNumber, category) = line.split(":", limit = 2)
                courseNumber to category
            }
    }

    fun enrich(
        year: Int,
        semester: Semester,
        row: SugangLectureRow,
    ): SugangLectureRow {
        val info = sugangSnuLectureApi.getLectureInfo(year, semester, row.courseNumber, row.lectureNumber)
        val sub = info.subInfo
        val courseTitle =
            sub.courseName?.let { name ->
                if (sub.courseSubName.isNullOrEmpty()) name else "$name (${sub.courseSubName})"
            } ?: row.courseTitle
        val department =
            sub.departmentKorNm?.let { dept ->
                sub.majorKorNm?.let { "$dept($it)" } ?: dept
            } ?: row.department
        val academicYear =
            sub.academicCourse
                ?.takeIf { it != "학사" }
                ?: sub.academicYear?.let { "${it}학년" }
                ?: row.academicYear
        return row.copy(
            courseTitle = courseTitle,
            instructor = sub.professorName?.substringBeforeLast(" (") ?: row.instructor,
            category = sub.category ?: row.category,
            department = department,
            academicYear = academicYear,
            quota = sub.quota ?: row.quota,
            remark = sub.remark ?: row.remark,
            classPlaceAndTimes =
                SugangSnuClassTimeUtils.convertTextToClassTimeObject(
                    info.ltTime,
                    info.ltRoom.map { it.replace("(무선랜제공)", "") },
                ),
            categoryPre2025 = categoryPre2025Map[row.courseNumber],
        )
    }
}
