package com.wafflestudio.snutt.batch.sugang

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.core.io.ByteArrayResource
import java.io.ByteArrayOutputStream

/**
 * 실제 수강편람 사이트에서 받은 엑셀 fixture(`fixtures/sugang/lectures-{ko,en}.xls`)를 읽고, live 테스트가 갱신할 때 잘라 낸다.
 * 실제 파일은 0~1행이 안내 문구, 2행이 헤더, 3행부터 강의다(`SugangSnuXlsxParser`와 같은 가정).
 */
object SugangXlsxFixtureFiles {
    const val HEADER_ROW = 2
    private const val FIRST_DATA_ROW = 3
    private const val LEADING_ROWS = 30
    private const val SAMPLES_PER_SHAPE = 3

    fun koXlsx(): ByteArrayResource = ByteArrayResource(read("lectures-ko.xls"))

    fun enXlsx(): ByteArrayResource = ByteArrayResource(read("lectures-en.xls"))

    private fun read(name: String): ByteArray =
        requireNotNull(
            javaClass.getResourceAsStream("/fixtures/sugang/$name"),
        ) { "fixture 없음: $name (./gradlew liveTest -PrefreshFixtures)" }
            .use { it.readBytes() }

    fun headers(xlsx: ByteArray): List<String> =
        WorkbookFactory.create(xlsx.inputStream()).use { workbook ->
            val row = workbook.getSheetAt(0).getRow(HEADER_ROW)
            (0 until row.lastCellNum).map { row.getCell(it)?.stringCellValue.orEmpty() }
        }

    /** 강의 행 수. */
    fun dataRowCount(xlsx: ByteArray): Int =
        WorkbookFactory.create(xlsx.inputStream()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            (FIRST_DATA_ROW..sheet.lastRowNum).count { sheet.getRow(it) != null }
        }

    /**
     * 한국어 엑셀에서 앞쪽 [LEADING_ROWS]행과, 파서가 다르게 다루는 모양(재학생 정원 괄호, 부제, 시간 없음, 연건·평창 강의실, 여러 시간)의
     * 표본을 몇 개씩 남긴다. 남긴 (교과목번호, 강좌번호)를 돌려준다.
     */
    fun trimKorean(xlsx: ByteArray): Pair<ByteArray, Set<Pair<String, String>>> {
        val shapes: List<(Map<String, String>) -> Boolean> =
            listOf(
                { it["정원"].orEmpty().contains("(") },
                { it["부제명"].orEmpty().isNotBlank() },
                { it["수업교시"].orEmpty().isBlank() },
                { it["강의실(동-호)(#연건, *평창)"].orEmpty().let { place -> place.contains("#") || place.contains("*") } },
                { it["수업교시"].orEmpty().contains("/") },
            )
        val sampled = IntArray(shapes.size)
        val kept = mutableSetOf<Pair<String, String>>()
        val bytes =
            trim(xlsx) { index, values ->
                val matched = shapes.indices.filter { shapes[it](values) && sampled[it] < SAMPLES_PER_SHAPE }
                val keep = index < LEADING_ROWS || matched.isNotEmpty()
                if (keep) {
                    matched.forEach { sampled[it]++ }
                    kept += values["교과목번호"].orEmpty() to values["강좌번호"].orEmpty()
                }
                keep
            }
        return bytes to kept
    }

    /** 영어 엑셀에서 한국어 fixture에 남긴 강의만 남긴다. */
    fun trimEnglish(
        xlsx: ByteArray,
        keys: Set<Pair<String, String>>,
    ): ByteArray = trim(xlsx) { _, values -> (values["Course Number"].orEmpty() to values["Lecture Number"].orEmpty()) in keys }

    private fun trim(
        xlsx: ByteArray,
        keep: (index: Int, values: Map<String, String>) -> Boolean,
    ): ByteArray =
        WorkbookFactory.create(xlsx.inputStream()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter()
            val header = sheet.getRow(HEADER_ROW)
            val columns = (0 until header.lastCellNum).associateBy { header.getCell(it)?.stringCellValue.orEmpty() }
            val rows = (FIRST_DATA_ROW..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
            val keptRows =
                rows.filterIndexed { index, row ->
                    keep(index, columns.mapValues { (_, col) -> formatter.formatCellValue(row.getCell(col)).trim() })
                }
            (rows - keptRows.toSet()).forEach { sheet.removeRow(it) }
            compact(sheet, keptRows)
            ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
        }

    private fun compact(
        sheet: Sheet,
        keptRows: List<Row>,
    ) {
        keptRows.forEachIndexed { i, row ->
            val target = FIRST_DATA_ROW + i
            if (row.rowNum != target) sheet.shiftRows(row.rowNum, row.rowNum, target - row.rowNum)
        }
        // HSSF(.xls)의 shiftRows는 원래 자리에 빈 행을 남기므로 마지막 강의 행 뒤를 모두 지운다.
        removeRowsAfter(sheet, FIRST_DATA_ROW + keptRows.size - 1)
    }

    fun removeRowsAfter(
        sheet: Sheet,
        lastRow: Int,
    ) {
        (sheet.lastRowNum downTo lastRow + 1).forEach { index -> sheet.getRow(index)?.let(sheet::removeRow) }
    }
}
