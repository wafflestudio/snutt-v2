package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// 수강스누 공식 xlsx 다운로드 (v1 SugangSnuRepository 이식)
@Component
class SugangSnuLectureApi(
    @param:Value("\${snutt.sugang.base-url:https://sugang.snu.ac.kr}") baseUrl: String,
) {
    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Referer", "https://sugang.snu.ac.kr/sugang/cc/cc100InterfaceExcel.action")
            .build()

    fun downloadLectureXlsx(
        year: Int,
        semester: Semester,
    ): ByteArrayResource {
        val bytes =
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/sugang/cc/cc100InterfaceExcel.action")
                        .queryParam("srchLanguage", "ko")
                        .queryParam("srchOpenSchyy", year)
                        .queryParam("srchOpenShtm", convertSemesterToSugangSnuSearchString(semester))
                        .build()
                }.accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(ByteArray::class.java)
                ?: throw IllegalStateException("수강스누 xlsx 다운로드 실패")
        return ByteArrayResource(bytes)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private fun convertSemesterToSugangSnuSearchString(semester: Semester): String =
            when (semester) {
                Semester.SPRING -> "U000200001U000300001"
                Semester.SUMMER -> "U000200001U000300002"
                Semester.AUTUMN -> "U000200002U000300001"
                Semester.WINTER -> "U000200002U000300002"
            }
    }
}
