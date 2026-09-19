package com.wafflestudio.snutt.batch.sugangsnu

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.batch.sugangsnu.data.SugangSnuLectureInfo
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.json.Json
import com.wafflestudio.snutt.core.common.util.SugangSnuUrlUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient

data class SugangSnuCoursebookCondition(
    @param:JsonProperty("currSchyy")
    val latestYear: Int,
    @param:JsonProperty("currShtmFg")
    val semesterFlagPrev: String,
    @param:JsonProperty("currDetaShtmFg")
    val semesterFlagNext: String,
) {
    val latestSemester: Semester
        get() = SugangSnuUrlUtils.semesterOf(semesterFlagPrev, semesterFlagNext)
}

@Component
class SugangSnuLectureApi(
    @Value("\${snutt.sugang.base-url:https://sugang.snu.ac.kr}") baseUrl: String,
) {
    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Referer", REFERER)
            .build()

    fun getLectureInfo(
        year: Int,
        semester: Semester,
        courseNumber: String,
        lectureNumber: String,
    ): SugangSnuLectureInfo {
        val body =
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/sugang/cc/cc101ajax.action")
                        .queryParam("t_profPersNo", "")
                        .queryParam("workType", "+")
                        .queryParam("sbjtSubhCd", "000")
                        .queryParam("openSchyy", year)
                        .queryParam("openShtmFg", SugangSnuUrlUtils.shtmFlag(semester))
                        .queryParam("openDetaShtmFg", SugangSnuUrlUtils.detaShtmFlag(semester))
                        .queryParam("sbjtCd", courseNumber)
                        .queryParam("ltNo", lectureNumber)
                        .build()
                }.accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("수강스누 강좌 상세 조회 실패: $courseNumber-$lectureNumber")
        return Json.mapper.readValue(body, SugangSnuLectureInfo::class.java)
    }

    fun getCoursebookCondition(): SugangSnuCoursebookCondition {
        val body =
            restClient
                .get()
                .uri { it.path("/sugang/cc/cc100ajax.action").query("openUpDeptCd=&openDeptCd=").build() }
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("수강스누 수강편람 조건 조회 실패")
        return Json.mapper.readValue(body, SugangSnuCoursebookCondition::class.java)
    }

    fun getMainPageHtml(): String =
        restClient
            .get()
            .uri("/sugang/co/co010.action")
            .accept(MediaType.TEXT_HTML)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("수강스누 메인 페이지 조회 실패")

    fun getSearchPageHtml(
        year: Int,
        semester: Semester,
        pageNo: Int,
    ): String =
        restClient
            .get()
            .uri { builder ->
                builder
                    .path("/sugang/cc/cc100InterfaceSrch.action")
                    .query("workType=S&sortKey=&sortOrder=")
                    .queryParam("srchOpenSchyy", year)
                    .queryParam("srchOpenShtm", SugangSnuUrlUtils.searchShtm(semester))
                    .queryParam("pageNo", pageNo)
                    .build()
            }.accept(MediaType.TEXT_HTML)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("수강스누 검색 페이지 조회 실패: page=$pageNo")

    fun downloadLectureXlsx(
        year: Int,
        semester: Semester,
        language: String = "ko",
    ): ByteArrayResource {
        val form = excelForm(year, semester, language)
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

    private fun excelForm(
        year: Int,
        semester: Semester,
        language: String,
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
        form.add("srchLanguage", language)
        form.add("srchOpenSchyy", year.toString())
        form.add("srchOpenShtm", SugangSnuUrlUtils.searchShtm(semester))
        return form
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val REFERER = "https://sugang.snu.ac.kr/sugang/cc/cc100InterfaceSrch.action"
    }
}
