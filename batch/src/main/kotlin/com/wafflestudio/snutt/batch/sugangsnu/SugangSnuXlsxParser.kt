package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

data class SugangLectureRow(
    val classification: String,
    val category: String,
    val department: String,
    val academicYear: String,
    val courseNumber: String,
    val lectureNumber: String,
    val courseTitle: String,
    val credit: Int,
    val instructor: String,
    val remark: String?,
    val quota: Int,
    val freshmanQuota: Int?,
    val registrationCount: Int,
    val classPlaceAndTimes: List<ClassPlaceAndTime>,
    val categoryPre2025: String? = null,
    val courseTitleEn: String? = null,
    val instructorEn: String? = null,
    val departmentEn: String? = null,
    val academicYearEn: String? = null,
    val categoryEn: String? = null,
    val classificationEn: String? = null,
    val remarkEn: String? = null,
)

@Component
class SugangSnuXlsxParser {
    private val log = LoggerFactory.getLogger(javaClass)
    private val quotaRegex = """(?<quota>\d+)(\s*\((?<quotaForCurrentStudent>\d+)\))?""".toRegex()
    private val classTimeRegex =
        """^(?<day>[월화수목금토일])\((?<startHour>\d{2}):(?<startMinute>\d{2})~(?<endHour>\d{2}):(?<endMinute>\d{2})\)$""".toRegex()

    data class SugangLectureRowEnglish(
        val courseNumber: String,
        val lectureNumber: String,
        val courseTitleEn: String?,
        val instructorEn: String?,
        val departmentEn: String?,
        val academicYearEn: String?,
        val classificationEn: String?,
        val remarkEn: String?,
    )

    fun parseEnglish(englishXlsx: Resource): Map<Pair<String, String>, SugangLectureRowEnglish> {
        val sheet = WorkbookFactory.create(englishXlsx.inputStream).getSheetAt(0)
        val headerIndex =
            sheet
                .getRow(2)
                .let { row -> (0 until row.lastCellNum).associate { row.getCell(it)?.stringCellValue.orEmpty() to it } }
        return (3..sheet.lastRowNum)
            .mapNotNull { rowNum ->
                val row = sheet.getRow(rowNum) ?: return@mapNotNull null

                fun get(key: String): String = headerIndex[key]?.let { row.getCell(it)?.stringCellValue }?.trim().orEmpty()
                val courseNumber = get("Course Number")
                val lectureNumber = get("Lecture Number")
                val courseTitle = get("Course Title")
                val subtitle = get("Course Subtitle")
                val college = get("College")
                val department = get("Department")
                val academicCourse = get("Degree Program")
                val academicYear = get("Academic Year")
                if (courseTitle.isEmpty()) return@mapNotNull null
                (courseNumber to lectureNumber) to
                    SugangLectureRowEnglish(
                        courseNumber = courseNumber,
                        lectureNumber = lectureNumber,
                        courseTitleEn = if (subtitle.isEmpty()) courseTitle else "$courseTitle ($subtitle)",
                        instructorEn = get("Instructor").ifEmpty { null },
                        departmentEn = department.replace("null", "").ifEmpty { college }.ifEmpty { null },
                        academicYearEn = academicCourse.takeIf { it != "Bachelor" } ?: academicYear.ifEmpty { null },
                        classificationEn = get("Course Classification").ifEmpty { null },
                        remarkEn = get("Remark").ifEmpty { null },
                    )
            }.toMap()
    }

    fun parse(koreanXlsx: Resource): List<SugangLectureRow> {
        val sheet = WorkbookFactory.create(koreanXlsx.inputStream).getSheetAt(0)
        val headerIndex =
            sheet
                .getRow(2)
                .let { row -> (0 until row.lastCellNum).associate { row.getCell(it)?.stringCellValue.orEmpty() to it } }
        val rows =
            (3..sheet.lastRowNum)
                .mapNotNull { rowNum -> convertRow(sheet.getRow(rowNum), headerIndex) }
        log.info("xlsx에서 {}개 강의 행 파싱", rows.size)
        return rows
    }

    private fun convertRow(
        row: Row?,
        headerIndex: Map<String, Int>,
    ): SugangLectureRow? {
        if (row == null) return null

        fun get(key: String): String = headerIndex[key]?.let { row.getCell(it)?.stringCellValue }?.trim().orEmpty()

        val courseNumber = get("교과목번호")
        val lectureNumber = get("강좌번호")
        if (courseNumber.isEmpty() || lectureNumber.isEmpty()) return null

        val classification = get("교과구분")
        val college = get("개설대학")
        val department = get("개설학과").replace("null", "").ifEmpty { college }
        val academicCourse = get("이수과정")
        val academicYear = academicCourse.takeIf { it.isNotEmpty() && it != "학사" } ?: get("학년")
        val courseTitle = get("교과목명")
        val courseSubtitle = get("부제명")
        val fullTitle = if (courseSubtitle.isEmpty()) courseTitle else "$courseTitle ($courseSubtitle)"
        val credit = get("학점").toIntOrNull() ?: 0
        val classTimeTexts = get("수업교시").split("/")
        val locationTexts = get("강의실(동-호)(#연건, *평창)").split("/")
        val instructor = get("주담당교수")
        val quotaMatch = quotaRegex.find(get("정원"))
        val quota =
            quotaMatch
                ?.groups
                ?.get("quota")
                ?.value
                ?.toInt() ?: 0
        // "35 (30)": 재학생 수강신청 기간에는 신입생 예비 정원을 제외한 정원이 유효 정원이다
        val freshmanQuota =
            quotaMatch
                ?.groups
                ?.get("quotaForCurrentStudent")
                ?.value
                ?.toInt()
                ?.let { (quota - it).takeIf { diff -> diff > 0 } }
        val remark = get("비고").ifEmpty { null }
        val registrationCount = get("수강신청인원").toIntOrNull() ?: 0

        return SugangLectureRow(
            classification = classification,
            category = deriveCategory(classification),
            department = department,
            academicYear = academicYear,
            courseNumber = courseNumber,
            lectureNumber = lectureNumber,
            courseTitle = fullTitle,
            credit = credit,
            instructor = instructor,
            remark = remark,
            quota = quota,
            freshmanQuota = freshmanQuota,
            registrationCount = registrationCount,
            classPlaceAndTimes = convertClassTimes(classTimeTexts, locationTexts),
        )
    }

    private fun convertClassTimes(
        classTimesTexts: List<String>,
        locationsTexts: List<String>,
    ): List<ClassPlaceAndTime> =
        runCatching {
            val classTimes = classTimesTexts.filter { it.isNotBlank() }.mapNotNull { parseClassTime(it) }
            val locations =
                when (locationsTexts.size) {
                    classTimes.size -> locationsTexts
                    1 -> List(classTimes.size) { locationsTexts.first() }
                    0 -> List(classTimes.size) { "" }
                    else -> throw RuntimeException("locations does not match times")
                }
            classTimes
                .zip(locations)
                .groupBy({ it.first }, { it.second })
                .map { (time, locationTexts) ->
                    ClassPlaceAndTime(
                        day = DayOfWeek.getByKoreanText(time.dayOfWeek)!!,
                        place = locationTexts.joinToString("/"),
                        startMinute = time.startHour * 60 + time.startMinute,
                        endMinute = time.endHour * 60 + time.endMinute,
                    )
                }.sortedWith(compareBy({ it.day.value }, { it.startMinute }))
        }.getOrElse {
            log.error("classTime 변환 실패: {}", classTimesTexts, it)
            emptyList()
        }

    private fun deriveCategory(classification: String): String =
        when (classification) {
            "전필" -> "전공필수"
            "전선" -> "전공선택"
            "교필" -> "교양필수"
            "교선" -> "교양선택"
            "일선" -> "일반교양"
            else -> classification.ifEmpty { "일반교양" }
        }

    private fun parseClassTime(text: String): ParsedClassTime? {
        val match = classTimeRegex.find(text) ?: return null
        return ParsedClassTime(
            dayOfWeek = match.groups["day"]!!.value,
            startHour = match.groups["startHour"]!!.value.toInt(),
            startMinute = match.groups["startMinute"]!!.value.toInt(),
            endHour = match.groups["endHour"]!!.value.toInt(),
            endMinute = match.groups["endMinute"]!!.value.toInt(),
        )
    }

    private data class ParsedClassTime(
        val dayOfWeek: String,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
    )
}
