package com.wafflestudio.snutt.core.common.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 저장소 원본 URI(s3://bucket/key)를 클라이언트가 받을 수 있는 URL로 바꾼다.
 * 현재 쓰는 버킷(snutt-asset)은 공개 읽기라 서명 없이 오브젝트 URL로 충분하다.
 */
@Service
class StorageUriResolver(
    @param:Value("\${snutt.storage.endpoint:https://objectstorage.ap-chuncheon-1.oraclecloud.com}")
    private val endpoint: String,
    @param:Value("\${snutt.storage.namespace:}")
    private val namespace: String,
) {
    fun resolve(originUri: String): String {
        if (!originUri.startsWith(SCHEME)) return originUri
        val withoutScheme = originUri.removePrefix(SCHEME)
        val bucket = withoutScheme.substringBefore("/")
        val key = withoutScheme.substringAfter("/", missingDelimiterValue = "")
        if (namespace.isBlank() || bucket.isEmpty() || key.isEmpty()) return originUri
        return "$endpoint/n/$namespace/b/$bucket/o/$key"
    }

    companion object {
        private const val SCHEME = "s3://"
    }
}
