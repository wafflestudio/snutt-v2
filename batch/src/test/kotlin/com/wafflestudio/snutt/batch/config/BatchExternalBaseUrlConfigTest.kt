package com.wafflestudio.snutt.batch.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/** 통합테스트는 수강편람·SNU 지도를 WireMock으로 돌리므로, batch yml의 운영 base URL은 여기서 따로 고정한다. */
class BatchExternalBaseUrlConfigTest {
    private val properties =
        YamlPropertiesFactoryBean()
            .apply { setResources(ClassPathResource("application.yml")) }
            .getObject()!!

    @Test
    fun `수강편람과 SNU 지도 base URL은 운영값이다`() {
        assertEquals("https://sugang.snu.ac.kr", properties.getProperty("snutt.sugang.base-url"))
        assertEquals("https://map.snu.ac.kr", properties.getProperty("snutt.snumap.base-url"))
    }
}
