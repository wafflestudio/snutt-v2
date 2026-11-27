package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.sugangsnu.LectureBuildingSync
import com.wafflestudio.snutt.batch.sugangsnu.RegistrationPeriodExtractor
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureEnricher
import com.wafflestudio.snutt.batch.vacancy.SugangSnuRegistrationStatusCrawler
import com.wafflestudio.snutt.core.test.AbstractIntegrationTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * batch 모듈 통합테스트 베이스. 컨텍스트가 한 번만 뜨도록 이 모듈의 모든 @MockitoBean은 여기에만 둔다.
 * 스텁하지 않은 mock은 null/기본값을 돌려주고, 매 테스트 뒤 Mockito가 reset한다.
 */
@SpringBootTest
abstract class AbstractBatchIntegrationTest : AbstractIntegrationTest() {
    @MockitoBean
    lateinit var sugangSnuLectureApi: SugangSnuLectureApi

    @MockitoBean
    lateinit var sugangSnuLectureEnricher: SugangSnuLectureEnricher

    @MockitoBean
    lateinit var lectureBuildingSync: LectureBuildingSync

    @MockitoBean
    lateinit var registrationPeriodExtractor: RegistrationPeriodExtractor

    @MockitoBean
    lateinit var registrationStatusCrawler: SugangSnuRegistrationStatusCrawler
}
