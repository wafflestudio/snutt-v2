package com.wafflestudio.snutt.core.common.pagination

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

data class CursorPage<T>(
    val content: List<T>,
    val cursor: String?,
    val size: Int,
    val last: Boolean,
    val totalCount: Long? = null,
) {
    fun <R> map(transform: (T) -> R): CursorPage<R> =
        CursorPage(
            content = content.map(transform),
            cursor = cursor,
            size = size,
            last = last,
            totalCount = totalCount,
        )

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

@Component
class CursorCodec(
    val jsonMapper: JsonMapper,
) {
    fun encode(value: Any): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(jsonMapper.writeValueAsBytes(value))

    final inline fun <reified T : Any> decode(cursor: String?): T? =
        cursor?.let {
            try {
                jsonMapper.readValue(Base64.getUrlDecoder().decode(it), T::class.java)
            } catch (_: Exception) {
                throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        }

    fun <T, R> pageOf(
        items: List<T>,
        pageSize: Int,
        totalCount: Long? = null,
        cursorOf: (T) -> Any,
        transform: (List<T>) -> List<R>,
    ): CursorPage<R> {
        val hasMore = items.size > pageSize
        val content = if (hasMore) items.take(pageSize) else items
        val nextCursor = if (hasMore) encode(cursorOf(content.last())) else null
        return CursorPage.of(transform(content), nextCursor, pageSize, totalCount)
    }
}
