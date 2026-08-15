package com.wafflestudio.snutt.core.common.storage

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.UUID

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

@Service
@Profile("test")
class RecordingUploadUriIssuer(
    private val storageUriResolver: StorageUriResolver,
) : UploadUriIssuer {
    override fun issue(
        source: StorageSource,
        count: Int,
    ): List<FileUploadUri> =
        (1..count).map {
            val key = "${source.path}/${UUID.randomUUID()}.jpg"
            val originUri = "s3://${source.bucketName}/$key"
            FileUploadUri(
                uploadUri = "https://upload.test/$key",
                fileOriginUri = originUri,
                fileUri = storageUriResolver.resolve(originUri),
            )
        }
}
