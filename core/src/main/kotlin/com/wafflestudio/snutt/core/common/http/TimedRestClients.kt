package com.wafflestudio.snutt.core.common.http

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

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
