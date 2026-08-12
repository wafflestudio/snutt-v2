package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// v1 x-access-token = HMAC-SHA256(secret, json(Credential)) hex. 필드 순서가 해시에 영향을 주므로
// snutt v1의 Credential 선언 순서를 유지한다 (../snutt users/data/Credential.kt)
private data class LegacyCredential(
    val localId: String? = null,
    val localPw: String? = null,
    val fbId: String? = null,
    val fbName: String? = null,
    val appleSub: String? = null,
    val appleEmail: String? = null,
    val appleTransferSub: String? = null,
    val googleSub: String? = null,
    val googleEmail: String? = null,
    val kakaoSub: String? = null,
    val kakaoEmail: String? = null,
    val tempDate: String? = null,
    val tempSeed: String? = null,
)

@Component
class LegacyCredentialHasher(
    private val objectMapper: ObjectMapper,
    @param:Value("\${snutt.auth.legacy-secret-key}") private val secretKey: String,
) {
    fun hash(user: User): String {
        val credential =
            LegacyCredential(
                localId = user.localId,
                localPw = user.localPw,
                fbId = user.facebookSub,
                fbName = user.facebookName,
                appleSub = user.appleSub,
                appleEmail = user.appleEmail,
                appleTransferSub = user.appleTransferSub,
                googleSub = user.googleSub,
                googleEmail = user.googleEmail,
                kakaoSub = user.kakaoSub,
                kakaoEmail = user.kakaoEmail,
            )
        return hmacSha256Hex(objectMapper.writeValueAsString(credential))
    }

    private fun hmacSha256Hex(data: String): String {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secretKey.toByteArray(), algorithm))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
