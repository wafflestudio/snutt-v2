package com.wafflestudio.snutt.core.test.external

import com.wafflestudio.snutt.core.domain.auth.oidc.OidcJwtVerifier

/** [OidcJwtVerifier]의 테스트 전용 훅. 프로덕션 API로 열지 않으려고 internal 함수를 같은 모듈인 testFixtures에서 감싼다. */
object OidcTestSupport {
    /** 앞 테스트가 받아 둔 JWKS 캐시를 버린다. 남아 있으면 JWKS 장애·키 교체 테스트가 순서에 따라 달라진다. */
    fun clearJwksCache(verifier: OidcJwtVerifier) = verifier.clearCache()
}
