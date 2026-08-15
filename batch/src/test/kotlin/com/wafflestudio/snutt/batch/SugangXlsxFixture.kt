package com.wafflestudio.snutt.batch

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.core.io.ByteArrayResource

// 수강스누 xlsx 픽스처 빌더 (헤더는 3번째 행, 데이터는 4번째 행부터)
object SugangXlsxFixture {
    private val HEADERS =
        listOf(
            "교과구분",
            "개설대학",
            "개설학과",
            "이수과정",
            "학년",
            "교과목번호",
            "강좌번호",
            "교과목명",
            "부제명",
            "학점",
            "강의",
            "실습",
            "수업교시",
            "수업형태",
            "강의실(동-호)(#연건, *평창)",
            "주담당교수",
            "장바구니신청",
            "신입생장바구니신청",
            "재학생장바구니신청",
            "정원",
            "수강신청인원",
            "비고",
            "강의언어",
            "개설상태",
        )

    // 기본값은 실제 2026-2학기 개설 강좌(400.320-002 공학연구의 실습 1)의 값
    data class RowData(
        val classification: String = "전선",
        val department: String = "컴퓨터공학부",
        val academicYear: String = "3학년",
        val courseNumber: String,
        val lectureNumber: String,
        val courseTitle: String,
        val subtitle: String = "",
        val credit: Int = 1,
        val classTime: String = "금(19:00~20:50)",
        val place: String = "302-310-2",
        val instructor: String = "이제희",
        val quota: Int = 20,
        val registrationCount: Int = 10,
        val remark: String = "",
    )

    fun xlsx(rows: List<RowData>): ByteArrayResource {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("강좌")
        sheet.createRow(0).createCell(0).setCellValue("설명")
        sheet.createRow(1).createCell(0).setCellValue("설명2")
        val headerRow = sheet.createRow(2)
        HEADERS.forEachIndexed { index, header -> headerRow.createCell(index).setCellValue(header) }
        rows.forEachIndexed { i, row -> fillRow(sheet.createRow(3 + i), row) }
        val bytes =
            java.io
                .ByteArrayOutputStream()
                .also { workbook.write(it) }
                .toByteArray()
        workbook.close()
        return ByteArrayResource(bytes)
    }

    private fun fillRow(
        row: Row,
        data: RowData,
    ) {
        val values =
            listOf(
                data.classification,
                "공과대학",
                data.department,
                "",
                data.academicYear,
                data.courseNumber,
                data.lectureNumber,
                data.courseTitle,
                data.subtitle,
                data.credit.toString(),
                "3",
                "0",
                data.classTime,
                "온라인",
                data.place,
                data.instructor,
                "0",
                "0",
                "0",
                data.quota.toString(),
                data.registrationCount.toString(),
                data.remark,
                "한국어",
                "정상",
            )
        values.forEachIndexed { index, value -> row.createCell(index).setCellValue(value) }
    }
}
