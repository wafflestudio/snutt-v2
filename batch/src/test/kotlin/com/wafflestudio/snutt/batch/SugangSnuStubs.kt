package com.wafflestudio.snutt.batch

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.util.SugangSnuUrlUtils
import com.wafflestudio.snutt.core.test.external.ExternalApiMock
import com.wafflestudio.snutt.core.test.external.ExternalService
import org.jsoup.Jsoup
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * 수강편람 사이트(sugang.snu.ac.kr)와 SNU 지도(map.snu.ac.kr)를 WireMock으로 stub한다.
 * 응답 본문은 실제 사이트에서 받은 `fixtures/sugang/` 파일을 바탕으로 만든다. 출처는 `fixtures/sugang/README.md`.
 */
class SugangSnuStubs(
    private val wireMock: WireMockServer,
) {
    fun path(path: String): String = ExternalApiMock.path(ExternalService.SUGANG_SNU, path)

    // --- 수강편람 조건 (cc100ajax) ---

    /** 실제 응답(201 Created)에서 현재 학기 값만 바꾼다. */
    fun coursebookCondition(
        year: Int,
        semester: Semester,
    ) {
        val node = jsonMapper.readTree(SugangFixtures.text("coursebook-condition.json")) as ObjectNode
        node.put("currSchyy", year.toString())
        node.put("currShtmFg", SugangSnuUrlUtils.shtmFlag(semester))
        node.put("currDetaShtmFg", SugangSnuUrlUtils.detaShtmFlag(semester))
        coursebookConditionResponse(json(node.toString()).withStatus(201))
    }

    fun coursebookConditionResponse(response: ResponseDefinitionBuilder) {
        wireMock.stubFor(get(urlPathEqualTo(path(CONDITION))).willReturn(response))
    }

    // --- 강의 엑셀 (cc100InterfaceExcel) ---

    fun lectureXlsx(
        language: String,
        bytes: ByteArray,
    ) = lectureXlsxResponse(language, aResponse().withHeader("Content-Type", "application/vnd.ms-excel").withBody(bytes))

    fun lectureXlsxResponse(
        language: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(post(urlPathEqualTo(path(EXCEL))).withFormParam("srchLanguage", equalTo(language)).willReturn(response))
    }

    // --- 강좌 상세 (cc101ajax) ---

    /** 상세 API가 아무 정보도 주지 않는 강좌. enricher는 엑셀 값을 그대로 쓴다. */
    fun lectureInfoForAll(response: ResponseDefinitionBuilder = json(EMPTY_LECTURE_INFO)) {
        wireMock.stubFor(get(urlPathEqualTo(path(LECTURE_INFO))).atPriority(LOW_PRIORITY).willReturn(response))
    }

    fun lectureInfo(
        courseNumber: String,
        lectureNumber: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(
            get(urlPathEqualTo(path(LECTURE_INFO)))
                .withQueryParam("sbjtCd", equalTo(courseNumber))
                .withQueryParam("ltNo", equalTo(lectureNumber))
                .willReturn(response),
        )
    }

    // --- 메인 페이지 (수강신청 일정, co010) ---

    fun mainPage(html: String = SugangFixtures.text("main-page.html")) = mainPageResponse(html(html))

    fun mainPageResponse(response: ResponseDefinitionBuilder) {
        wireMock.stubFor(get(urlPathEqualTo(path(MAIN))).willReturn(response))
    }

    // --- 강좌 검색 페이지 (빈자리, cc100InterfaceSrch) ---

    /**
     * 검색 페이지 요청에 [responses]를 차례로 돌려준다. 마지막 응답은 이후에도 계속 돌려준다.
     * 크롤러는 청크 안의 페이지를 병렬로 받으므로 페이지가 1개(검색 건수 10건 이하)일 때만 순서가 결정적이다.
     */
    fun searchPageSequence(vararg responses: ResponseDefinitionBuilder) {
        responses.forEachIndexed { index, response ->
            wireMock.stubFor(
                get(urlPathEqualTo(path(SEARCH)))
                    .inScenario(SEARCH_SCENARIO)
                    .whenScenarioStateIs(if (index == 0) Scenario.STARTED else "step-$index")
                    .apply { if (index < responses.lastIndex) willSetStateTo("step-${index + 1}") }
                    .willReturn(response),
            )
        }
    }

    // --- SNU 지도 ---

    fun snuMapPath(path: String): String = ExternalApiMock.path(ExternalService.SNU_MAP, path)

    fun snuMapSearch(
        buildingNumber: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(
            get(urlPathEqualTo(snuMapPath(SNU_MAP_SEARCH)))
                .withQueryParam("search_word", equalTo(buildingNumber))
                .willReturn(response),
        )
    }

    fun snuMapForAll(
        response: ResponseDefinitionBuilder,
        priority: Int = LOW_PRIORITY,
    ) {
        wireMock.stubFor(get(urlPathMatching(snuMapPath("/.*"))).atPriority(priority).willReturn(response))
    }

    fun requestsTo(path: String): Int = wireMock.allServeEvents.count { it.request.url.substringBefore("?") == path(path) }

    companion object {
        const val CONDITION = "/sugang/cc/cc100ajax.action"
        const val EXCEL = "/sugang/cc/cc100InterfaceExcel.action"
        const val LECTURE_INFO = "/sugang/cc/cc101ajax.action"
        const val MAIN = "/sugang/co/co010.action"
        const val SEARCH = "/sugang/cc/cc100InterfaceSrch.action"
        const val SNU_MAP_SEARCH = "/api/search.action"
        private const val SEARCH_SCENARIO = "search-page"
        private const val LOW_PRIORITY = 10
        const val EMPTY_LECTURE_INFO = """{"ltTime":[],"ltRoom":[],"LISTTAB01":{}}"""
        private val jsonMapper = JsonMapper.builder().build()

        fun json(body: String): ResponseDefinitionBuilder =
            aResponse().withHeader("Content-Type", "application/json;charset=UTF-8").withBody(body)

        fun html(body: String): ResponseDefinitionBuilder = aResponse().withHeader("Content-Type", "text/html;charset=UTF-8").withBody(body)

        fun status(code: Int): ResponseDefinitionBuilder = aResponse().withStatus(code).withBody("error $code")

        /** read-timeout(테스트 1초)보다 오래 걸리는 응답. */
        fun slow(response: ResponseDefinitionBuilder): ResponseDefinitionBuilder = response.withFixedDelay(3_000)
    }
}

/** `fixtures/sugang/`의 실제 응답 파일과, 그것을 바탕으로 만든 변형. */
object SugangFixtures {
    fun text(name: String): String = bytes(name).decodeToString()

    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/sugang/$name")) { "fixture 없음: $name" }.use { it.readBytes() }

    /** 실제 메인 페이지의 수강신청 일정 표 행을 [rows](구분, 일자)로 바꾼다. */
    fun mainPageWithSchedule(rows: List<Pair<String, String>>): String {
        val document = Jsoup.parse(text("main-page.html"))
        val tbody = requireNotNull(document.selectFirst(".table-con table tbody"))
        val template = requireNotNull(tbody.selectFirst("tr")).clone()
        tbody.empty()
        rows.forEach { (type, dates) ->
            val row = template.clone()
            requireNotNull(row.selectFirst("th[data-th=구분] span")).text(type)
            requireNotNull(row.selectFirst("td[data-th=일자]")).text(dates)
            tbody.appendChild(row)
        }
        return document.outerHtml()
    }

    data class SearchItem(
        val courseNumber: String,
        val lectureNumber: String,
        val registrationCount: Int,
        val quota: Int,
        val wasFull: Boolean = false,
    )

    /** 실제 검색 페이지의 강좌 항목을 [items]로 바꾼다. 검색 건수는 [totalCount]로 둔다. */
    fun searchPage(
        items: List<SearchItem>,
        totalCount: Int = items.size,
    ): String {
        val document = Jsoup.parse(text("search-page.html"))
        requireNotNull(document.selectFirst("div.search-result-con > small > em")).text(totalCount.toString())
        val list = requireNotNull(document.selectFirst("div.course-info-list"))
        val withVacancyIcon = requireNotNull(list.select("div.course-info-item").firstOrNull { it.selectFirst(VACANCY_ICON) != null })
        val template = withVacancyIcon.clone()
        list.empty()
        items.forEach { item ->
            val node = template.clone()
            val info = requireNotNull(node.selectFirst("ul.course-info"))
            requireNotNull(info.selectFirst("li:nth-of-type(1) > span:nth-of-type(3)")).text("${item.courseNumber}(${item.lectureNumber})")
            requireNotNull(info.selectFirst("li:nth-of-type(2) > span:nth-of-type(1) > em"))
                .text("${item.registrationCount}/${item.quota} (${item.quota})")
            if (!item.wasFull) info.select(VACANCY_ICON).remove()
            list.appendChild(node)
        }
        return document.outerHtml()
    }

    private const val VACANCY_ICON = "li.state > span[data-dialog-target='remaining-place-dialog']"
}
