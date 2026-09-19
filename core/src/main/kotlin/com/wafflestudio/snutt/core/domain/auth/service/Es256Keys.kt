package com.wafflestudio.snutt.core.domain.auth.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
