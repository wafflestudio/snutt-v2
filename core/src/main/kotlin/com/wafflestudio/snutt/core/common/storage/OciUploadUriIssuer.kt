package com.wafflestudio.snutt.core.common.storage

import com.oracle.bmc.Region
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.objectstorage.ObjectStorageClient
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

@Configuration
@Profile("!test")
class OciConfig(
    @param:Value("\${snutt.storage.oci.auth-type:auto}") private val authType: String,
    @param:Value("\${snutt.storage.oci.config-profile:DEFAULT}") private val configProfile: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun ociAuthProvider(): BasicAuthenticationDetailsProvider =
        when (authType.trim().lowercase()) {
            "instance" -> InstancePrincipalsAuthenticationDetailsProvider.builder().build()
            "config" -> ConfigFileAuthenticationDetailsProvider(configProfile)
            else ->
                runCatching { InstancePrincipalsAuthenticationDetailsProvider.builder().build() }
                    .getOrElse {
                        log.info("인스턴스 프린시펄을 쓸 수 없어 설정 파일 인증으로 넘어간다")
                        ConfigFileAuthenticationDetailsProvider(configProfile)
                    }
        }
}

@Service
@Profile("!test")
class OciUploadUriIssuer(
    authProvider: BasicAuthenticationDetailsProvider,
    private val storageUriResolver: StorageUriResolver,
    @param:Value("\${snutt.storage.endpoint:https://objectstorage.ap-chuncheon-1.oraclecloud.com}")
    private val endpoint: String,
    @Value("\${snutt.storage.region:ap-chuncheon-1}") region: String,
) : UploadUriIssuer {
    private val client: ObjectStorageClient =
        ObjectStorageClient.builder().region(Region.fromRegionId(region)).build(authProvider)

    private val namespace: String by lazy {
        client.getNamespace(GetNamespaceRequest.builder().build()).value
    }

    override fun issue(
        source: StorageSource,
        count: Int,
    ): List<FileUploadUri> {
        require(count in 1..MAX_FILE_COUNT) { "업로드 파일 개수는 1..$MAX_FILE_COUNT 이어야 한다" }
        return (1..count).map {
            val key = "${source.path}/${UUID.randomUUID()}.jpg"
            val details =
                CreatePreauthenticatedRequestDetails
                    .builder()
                    .name("upload-${UUID.randomUUID()}")
                    .objectName(key)
                    .accessType(CreatePreauthenticatedRequestDetails.AccessType.ObjectWrite)
                    .timeExpires(Date.from(Instant.now().plus(UPLOAD_TTL)))
                    .build()
            val accessUri =
                client
                    .createPreauthenticatedRequest(
                        CreatePreauthenticatedRequestRequest
                            .builder()
                            .namespaceName(namespace)
                            .bucketName(source.bucketName)
                            .createPreauthenticatedRequestDetails(details)
                            .build(),
                    ).preauthenticatedRequest
                    .accessUri
            val originUri = "s3://${source.bucketName}/$key"
            FileUploadUri(
                uploadUri = "$endpoint$accessUri",
                fileOriginUri = originUri,
                fileUri = storageUriResolver.resolve(originUri),
            )
        }
    }

    companion object {
        private const val MAX_FILE_COUNT = 10
        private val UPLOAD_TTL: Duration = Duration.ofMinutes(10)
    }
}
