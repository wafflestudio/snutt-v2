package com.wafflestudio.snutt.batch.vacancy

import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi
import com.wafflestudio.snutt.core.common.enums.Semester
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

data class RegistrationStatus(
    val courseNumber: String,
    val lectureNumber: String,
    val registrationCount: Int,
    // 검색 결과의 '빈자리 알림' 상태 마커: 만석을 겪은 강의에만 붙는다
    val wasFull: Boolean,
)

// 수강스누 검색 페이지에서 실시간 재안인원을 읽는다 (v1 VacancyNotifierService 크롤링 이식)
@Component
class SugangSnuRegistrationStatusCrawler(
    private val sugangSnuLectureApi: SugangSnuLectureApi,
) {
    companion object {
        private const val COUNT_PER_PAGE = 10
        private val courseNumberRegex = """(?<courseNumber>.*)\((?<lectureNumber>.+)\)""".toRegex()
    }

    fun getPageCount(
        year: Int,
        semester: Semester,
    ): Int {
        val firstPage = parse(sugangSnuLectureApi.getSearchPageHtml(year, semester, 1))
        val totalCount = firstPage.select("div.content > div.search-result-con > small > em").text().toInt()
        return (totalCount + COUNT_PER_PAGE - 1) / COUNT_PER_PAGE
    }

    fun getRegistrationStatus(
        year: Int,
        semester: Semester,
        pages: List<Int>,
    ): List<RegistrationStatus> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            pages
                .map { page ->
                    executor.submit<List<RegistrationStatus>> {
                        parse(sugangSnuLectureApi.getSearchPageHtml(year, semester, page)).extractRegistrationStatus()
                    }
                }.flatMap { it.get() }
        }

    private fun parse(html: String): Element =
        Jsoup
            .parse(html)
            .select("html > body > form#CC100 > div#wrapper > div#skip-con > div.content")
            .first() ?: throw IllegalStateException("수강스누 검색 페이지 구조가 예상과 다르다")

    private fun Element.extractRegistrationStatus(): List<RegistrationStatus> =
        select("div.content > div.course-list-wrap.pd-r > div.course-info-list > div.course-info-item")
            .map { course ->
                val info = course.select("div.course-info-item ul.course-info").first()!!
                val (courseNumber, lectureNumber) =
                    info
                        .select("li:nth-of-type(1) > span:nth-of-type(3)")
                        .text()
                        .let { requireNotNull(courseNumberRegex.find(it)) { "과목번호 형식이 예상과 다르다: $it" }.groups }
                        .let { it["courseNumber"]!!.value to it["lectureNumber"]!!.value }
                val registrationCount =
                    info
                        .select("ul.course-info > li:nth-of-type(2) > span:nth-of-type(1) > em")
                        .text()
                        .split("/")
                        .first()
                        .toInt()
                val wasFull = info.select("li.state > span[data-dialog-target='remaining-place-dialog']").isNotEmpty()
                RegistrationStatus(
                    courseNumber = courseNumber,
                    lectureNumber = lectureNumber,
                    registrationCount = registrationCount,
                    wasFull = wasFull,
                )
            }
}
