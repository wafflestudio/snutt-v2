package com.wafflestudio.snutt.core.common.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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
