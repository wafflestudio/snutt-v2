package com.wafflestudio.snutt.api.testutil

import io.jsonwebtoken.Jwts
import javax.crypto.spec.SecretKeySpec

fun legacyApiKey(
    platform: String = "ios",
    keyVersion: String = "0",
): String =
    Jwts
        .builder()
        .claim("string", platform)
        .claim("key_version", keyVersion)
        .signWith(
            SecretKeySpec("test-legacy-secret-key-0123456789abcdef".toByteArray(), "HmacSHA256"),
            Jwts.SIG.HS256,
        ).compact()
