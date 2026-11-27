# 외부 API fixture

각 제공자 공식 문서의 응답 예시 형태로 만든 것이다. 실제 계정·토큰 값은 없다.

- `google/`: Google OAuth2 v1 userinfo(`GET /oauth2/v1/userinfo`)와 401 오류 본문.
- `kakao/`: Kakao 사용자 정보 가져오기(`GET /v2/user/me`). `user-me-no-email.json`은 이메일 동의를 받지 않은 사용자.
- `facebook/`: Graph API `GET /me?fields=id,name,email`과 만료 토큰 오류(OAuthException code 190).
- `github/`: Issues API `POST /repos/{owner}/{repo}/issues`의 201 응답(필드 일부)과 401 오류.
- Apple·Facebook OIDC의 JWKS와 id_token은 파일로 두지 않고 `OidcTestIssuer`가 테스트용 RSA 키쌍으로 만든다.
