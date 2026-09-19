package com.wafflestudio.snutt.core.common.storage

enum class StorageSource(
    val value: String,
    val bucketName: String,
    val path: String,
) {
    POPUP("popup", "snutt-asset", "popup-images"),
    ;

    companion object {
        fun from(value: String): StorageSource? = entries.find { it.value == value }
    }
}

data class FileUploadUri(
    val uploadUri: String,
    val fileOriginUri: String,
    val fileUri: String,
)

interface UploadUriIssuer {
    fun issue(
        source: StorageSource,
        count: Int,
    ): List<FileUploadUri>
}
