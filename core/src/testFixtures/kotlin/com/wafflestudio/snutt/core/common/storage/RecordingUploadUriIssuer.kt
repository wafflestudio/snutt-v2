package com.wafflestudio.snutt.core.common.storage

import org.springframework.stereotype.Service
import java.util.UUID

@Service
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
