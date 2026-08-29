package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

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
        val courseTitleEn =
            sub.courseNameEng?.let { name ->
                if (sub.courseSubNameEng.isNullOrEmpty()) name else "$name (${sub.courseSubNameEng})"
            } ?: row.courseTitleEn
        val departmentEn =
            sub.departmentEngNm?.let { dept ->
                sub.majorEngNm?.let { "$dept($it)" } ?: dept
            } ?: row.departmentEn
        val academicYearEn =
            sub.academicCourseEng
                ?.takeIf { it != "Bachelor" }
                ?: sub.academicYear?.let { "Year $it" }
                ?: row.academicYearEn
        return row.copy(
            courseTitle = courseTitle,
            instructor = sub.professorName?.substringBeforeLast(" (") ?: row.instructor,
            category = sub.category ?: row.category,
            department = department,
            academicYear = academicYear,
            quota = sub.quota ?: row.quota,
            remark = sub.remark ?: row.remark,
            courseTitleEn = courseTitleEn,
            instructorEn = sub.professorNameEng?.substringBeforeLast(" (") ?: row.instructorEn,
            categoryEn = sub.categoryEng ?: row.categoryEn,
            departmentEn = departmentEn,
            academicYearEn = academicYearEn,
            remarkEn = sub.remarkEng ?: row.remarkEn,
            // API 변환 결과가 비었는데 xlsx에는 시간이 있으면 파싱 실패일 수 있으므로 기존 xlsx 값을 유지한다
            classPlaceAndTimes =
                SugangSnuClassTimeUtils
                    .convertTextToClassTimeObject(
                        info.ltTime,
                        info.ltRoom.map { it.replace("(무선랜제공)", "") },
                    ).ifEmpty { row.classPlaceAndTimes },
            categoryPre2025 = categoryPre2025Map[row.courseNumber],
        )
    }
}
