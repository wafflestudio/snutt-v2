package com.wafflestudio.snutt.core.domain.auth.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** snutt.auth.jwt 키페어. 액세스 토큰과 친구 초대 링크 토큰이 같은 키를 공유하고 typ 클레임으로 용도를 구분한다. */
@Component
class Es256Keys(
    @Value("\${snutt.auth.jwt.private-key}") privateKeyBase64: String,
    @Value("\${snutt.auth.jwt.public-key}") publicKeyBase64: String,
) {
    val privateKey: PrivateKey =
        KeyFactory
            .getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
    val publicKey: PublicKey =
        KeyFactory
            .getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
}
