package com.wafflestudio.snutt.core.common.pagination

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

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

fun <T, R> List<T>.toCursorPage(
    pageSize: Int,
    totalCount: Long? = null,
    cursorOf: (T) -> Any,
    transform: (List<T>) -> List<R>,
): CursorPage<R> {
    val hasMore = size > pageSize
    val content = if (hasMore) take(pageSize) else this
    val nextCursor = if (hasMore) CursorCodec.encode(cursorOf(content.last())) else null
    return CursorPage.of(transform(content), nextCursor, pageSize, totalCount)
}

object CursorCodec {
    @PublishedApi
    internal val jsonMapper = JsonMapper.builder().findAndAddModules().build()

    inline fun <reified T> encode(value: T): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(jsonMapper.writeValueAsBytes(value))

    inline fun <reified T> decode(cursor: String?): T? =
        cursor?.let {
            try {
                jsonMapper.readValue(Base64.getUrlDecoder().decode(it), T::class.java)
            } catch (_: Exception) {
                throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        }
}
