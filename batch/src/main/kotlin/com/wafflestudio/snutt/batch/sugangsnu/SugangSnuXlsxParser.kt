package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

// 수강스누 공식 xlsx(한글)를 강의 행으로 변환 (v1 SugangSnuFetchService 이식).
// 엑셀 컬럼(2023/01/26 기준): 교과구분, 개설대학, 개설학과, 이수과정, 학년, 교과목번호, 강좌번호,
// 교과목명, 부제명, 학점, 강의, 실습, 수업교시, 수업형태, 강의실(동-호)(#연건, *평창), 주담당교수,
// 장바구니신청, ..., 정원, 수강신청인원, 비고, 강의언어, 개설상태
data class SugangLectureRow(
    val classification: String,
    // 교과구분에서 유도한 검색용 카테고리 (v1은 수강스누 상세 API로 보강 — 상세 API 이관 전까지 유도값 사용)
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
    val registrationCount: Int,
    val classPlaceAndTimes: List<ClassPlaceAndTime>,
)

@Component
class SugangSnuXlsxParser {
    private val log = LoggerFactory.getLogger(javaClass)
    private val quotaRegex = """(?<quota>\d+)(\s*\((?<quotaForCurrentStudent>\d+)\))?""".toRegex()
    private val classTimeRegex =
        """^(?<day>[월화수목금토일])\((?<startHour>\d{2}):(?<startMinute>\d{2})~(?<endHour>\d{2}):(?<endMinute>\d{2})\)$""".toRegex()

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
        val quota =
            quotaRegex
                .find(get("정원"))
                ?.groups
                ?.get("quota")
                ?.value
                ?.toInt() ?: 0
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
