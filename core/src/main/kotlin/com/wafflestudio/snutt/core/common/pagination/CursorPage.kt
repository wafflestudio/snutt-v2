package com.wafflestudio.snutt.core.common.pagination

import tools.jackson.databind.json.JsonMapper

// keyset 커서 페이지네이션 공통 응답. cursor는 클라이언트에 opaque 문자열이다
data class CursorPage<T>(
    val content: List<T>,
    val cursor: String?,
    val size: Int,
    val last: Boolean,
    val totalCount: Long? = null,
) {
    companion object {
        fun <T> of(
            content: List<T>,
            nextCursor: String?,
            pageSize: Int,
            totalCount: Long? = null,
        ) = CursorPage(
            content = content,
            cursor = nextCursor,
            size = pageSize,
            last = nextCursor == null,
            totalCount = totalCount,
        )
    }
}

// base64(JSON) 커서 코덱. 구 AES 커서는 이식하지 않는다 (opaque 문자열 계약 유지)
object CursorCodec {
    @PublishedApi
    internal val jsonMapper = JsonMapper.builder().findAndAddModules().build()

    inline fun <reified T> encode(value: T): String =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(jsonMapper.writeValueAsBytes(value))

    inline fun <reified T> decode(cursor: String?): T? =
        cursor?.let {
            jsonMapper.readValue(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(it),
                T::class.java,
            )
        }
}
