package com.wafflestudio.snutt.core.common.util

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.web.util.DefaultUriBuilderFactory

object SugangSnuUrlUtils {
    const val SUGANG_SNU_BASE_URL = "https://sugang.snu.ac.kr"

    private val semesterFlags =
        mapOf(
            Semester.SPRING to ("U000200001" to "U000300001"),
            Semester.SUMMER to ("U000200001" to "U000300002"),
            Semester.AUTUMN to ("U000200002" to "U000300001"),
            Semester.WINTER to ("U000200002" to "U000300002"),
        )

    fun shtmFlag(semester: Semester): String = semesterFlags.getValue(semester).first

    fun detaShtmFlag(semester: Semester): String = semesterFlags.getValue(semester).second

    fun searchShtm(semester: Semester): String = shtmFlag(semester) + detaShtmFlag(semester)

    fun semesterOf(
        shtmFlag: String,
        detaShtmFlag: String,
    ): Semester =
        semesterFlags.entries.firstOrNull { it.value == (shtmFlag to detaShtmFlag) }?.key
            ?: throw IllegalArgumentException("unknown semester flags: $shtmFlag$detaShtmFlag")

    fun parseSyllabusPath(
        year: Int,
        semester: Semester,
        courseNumber: String,
        lectureNumber: String,
    ): String =
        DefaultUriBuilderFactory()
            .builder()
            .path("/sugang/cc/cc103.action")
            .queryParam("openSchyy", year)
            .queryParam("openShtmFg", shtmFlag(semester))
            .queryParam("openDetaShtmFg", detaShtmFlag(semester))
            .queryParam("sbjtCd", courseNumber)
            .queryParam("ltNo", lectureNumber)
            .queryParam("sbjtSubhCd", "000")
            .build()
            .toString()
}
