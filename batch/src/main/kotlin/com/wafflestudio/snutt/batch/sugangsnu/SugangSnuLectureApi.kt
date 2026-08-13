package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient

// 수강스누 공식 xlsx 다운로드 (v1 SugangSnuRepository 이식).
// 검색 페이지의 HD102 폼을 그대로 POST로 제출한다. referer는 snu.ac.kr 계열이면 된다.
@Component
class SugangSnuLectureApi(
    @param:Value("\${snutt.sugang.base-url:https://sugang.snu.ac.kr}") baseUrl: String,
) {
    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Referer", REFERER)
            .build()

    fun downloadLectureXlsx(
        year: Int,
        semester: Semester,
    ): ByteArrayResource {
        val form = excelForm(year, semester)
        val bytes =
            restClient
                .post()
                .uri("/sugang/cc/cc100InterfaceExcel.action")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(ByteArray::class.java)
                ?: throw IllegalStateException("수강스누 xlsx 다운로드 실패")
        return ByteArrayResource(bytes)
    }

    // 검색 페이지 HD102 폼 필드. 빈 값이 기본이고 workType=EX가 엑셀 저장 모드다
    private fun excelForm(
        year: Int,
        semester: Semester,
    ): MultiValueMap<String, String> {
        val form: MultiValueMap<String, String> = LinkedMultiValueMap()
        listOf(
            "srchSbjtNm",
            "srchSbjtCd",
            "srchCptnCorsFg",
            "srchOpenShyr",
            "srchOpenUpSbjtFldCd",
            "srchOpenSbjtFldCd",
            "srchOpenUpDeptCd",
            "srchOpenDeptCd",
            "srchOpenMjCd",
            "srchOpenSubmattCorsFg",
            "srchOpenSubmattFgCd1",
            "srchOpenSubmattFgCd2",
            "srchOpenSubmattFgCd3",
            "srchOpenSubmattFgCd4",
            "srchOpenSubmattFgCd5",
            "srchOpenSubmattFgCd6",
            "srchOpenSubmattFgCd7",
            "srchOpenSubmattFgCd8",
            "srchOpenSubmattFgCd9",
            "srchExcept",
            "srchOpenPntMin",
            "srchOpenPntMax",
            "srchCamp",
            "srchBdNo",
            "srchProfNm",
            "srchOpenSbjtTmNm",
            "srchOpenSbjtDayNm",
            "srchOpenSbjtTm",
            "srchTlsnAplyCapaCntMin",
            "srchTlsnAplyCapaCntMax",
            "srchLsnProgType",
            "srchTlsnRcntMin",
            "srchTlsnRcntMax",
            "srchMrksGvMthd",
        ).forEach { form.add(it, "") }
        form.add("seeMore", "더보기")
        form.add("srchCurrPage", "1")
        form.add("srchPageSize", "9999")
        form.add("workType", "EX")
        form.add("srchLanguage", "ko")
        form.add("srchOpenSchyy", year.toString())
        form.add("srchOpenShtm", convertSemesterToSugangSnuSearchString(semester))
        return form
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val REFERER = "https://sugang.snu.ac.kr/sugang/cc/cc100InterfaceSrch.action"

        private fun convertSemesterToSugangSnuSearchString(semester: Semester): String =
            when (semester) {
                Semester.SPRING -> "U000200001U000300001"
                Semester.SUMMER -> "U000200001U000300002"
                Semester.AUTUMN -> "U000200002U000300001"
                Semester.WINTER -> "U000200002U000300002"
            }
    }
}
