package com.wafflestudio.snutt.core.common.http

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/** 외부 API 호출용 RestClient. 타임아웃이 없으면 상대 장애 시 호출 스레드가 무기한 대기한다. */
object TimedRestClients {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 5_000

    fun restClient(): RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT_MS)
                    setReadTimeout(READ_TIMEOUT_MS)
                },
            ).build()
}
