package com.wafflestudio.snutt.batch.live

import com.wafflestudio.snutt.batch.sugang.SugangXlsxFixtureFiles
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuXlsxParser
import com.wafflestudio.snutt.core.test.LiveTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 실제 수강편람 사이트에서 현재 학기 엑셀을 받아 파서가 통과하는지, 컬럼 구성이 fixture와 같은지 본다.
 * 요청은 3번(수강편람 조건, 한국어·영어 엑셀)이다. IP 차단을 피하려고 자주 돌리지 않는다.
 *
 * `./gradlew :batch:liveTest -PrefreshFixtures`로 돌리면 받은 엑셀을 잘라 `fixtures/sugang/lectures-{ko,en}.xls`를 갱신한다.
 */
@LiveTest
class SugangSnuLiveTest {
    private val api =
        SugangSnuLectureApi(
            "https://sugang.snu.ac.kr",
            RestClient.builder().requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofSeconds(60)) }),
            JsonMapper.builder().findAndAddModules().build(),
        )
    private val refresh = System.getProperty("snutt.live.refreshFixtures").toBoolean()
    private val fixturesDir: Path =
        Path.of(System.getProperty("snutt.live.fixturesDir") ?: "src/test/resources/fixtures").resolve("sugang")

    @Test
    fun `현재 학기 수강편람 엑셀을 파싱하고 컬럼 구성이 fixture와 같다`() {
        val condition = api.getCoursebookCondition()
        val ko = api.downloadLectureXlsx(condition.latestYear, condition.latestSemester, "ko").byteArray
        val en = api.downloadLectureXlsx(condition.latestYear, condition.latestSemester, "en").byteArray
        println("live: ${condition.latestYear} ${condition.latestSemester}, ko=${ko.size}B en=${en.size}B")

        val rows = SugangSnuXlsxParser().parse(ko.asResource(), en.asResource())
        println("live: ${rows.size}개 강의 파싱, 영어 매칭 ${rows.count { it.courseTitleEn != null }}개")
        assertEquals(SugangXlsxFixtureFiles.dataRowCount(ko), rows.size)
        assertTrue(rows.count { it.courseTitleEn != null } * 2 > rows.size, "영어 엑셀과 절반 이상 매칭되어야 한다")
        assertTrue(rows.any { it.classPlaceAndTimes.isNotEmpty() }, "수업 시간이 하나도 파싱되지 않았다")

        val liveKo = SugangXlsxFixtureFiles.headers(ko)
        val liveEn = SugangXlsxFixtureFiles.headers(en)
        val koFile = fixturesDir.resolve("lectures-ko.xls")
        val enFile = fixturesDir.resolve("lectures-en.xls")
        if (refresh) {
            if (Files.exists(koFile)) reportDiff("ko", SugangXlsxFixtureFiles.headers(Files.readAllBytes(koFile)), liveKo)
            if (Files.exists(enFile)) reportDiff("en", SugangXlsxFixtureFiles.headers(Files.readAllBytes(enFile)), liveEn)
            val (trimmedKo, keys) = SugangXlsxFixtureFiles.trimKorean(ko)
            Files.write(koFile, trimmedKo)
            Files.write(enFile, SugangXlsxFixtureFiles.trimEnglish(en, keys))
            println("live: fixture 갱신 (${keys.size}개 강의) -> $fixturesDir")
            return
        }
        assertEquals(SugangXlsxFixtureFiles.headers(Files.readAllBytes(koFile)), liveKo, "한국어 엑셀 컬럼이 fixture와 다르다")
        assertEquals(SugangXlsxFixtureFiles.headers(Files.readAllBytes(enFile)), liveEn, "영어 엑셀 컬럼이 fixture와 다르다")
    }

    private fun reportDiff(
        language: String,
        fixture: List<String>,
        live: List<String>,
    ) {
        if (fixture == live) return
        println("live: $language 컬럼 변경. 추가=${live - fixture.toSet()} 삭제=${fixture - live.toSet()}")
    }

    private fun ByteArray.asResource() =
        org.springframework.core.io
            .ByteArrayResource(this)
}
