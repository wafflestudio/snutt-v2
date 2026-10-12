package com.wafflestudio.snutt.core.common.storage

enum class StorageSource(
    val bucketName: String,
    val path: String,
) {
    POPUP("snutt-asset", "popup-images"),
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
