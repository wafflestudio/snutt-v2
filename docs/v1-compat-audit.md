# v1 엔드포인트 호환성 감사

v1 서버 두 개(SNUTT 시간표, SNUTT-EV 강의평)의 HTTP 엔드포인트를 v2 `v1compat` 모듈과 대조한 기준 문서다. 이후 parity 비교 테스트와 구클라 호환 시나리오 테스트가 이 문서를 출발점으로 삼는다. 코드 읽기만으로 판단했고, 실제 요청은 보내지 않았다.

## 기준과 범위

| 대상 | 기준 커밋 | 근거 |
|---|---|---|
| SNUTT v1 (`wafflestudio/snutt`) | `origin/develop` 6aac982 (2026-11-09) | 운영 배포는 `main` push로 나간다(`.github/workflows/deploy-api-prod.yml`). `main`(47bab6e)과 `develop`의 차이는 일기장 푸시 딥링크 인코딩 1줄과 CI 러너뿐이라 엔드포인트 계약은 같다. |
| SNUTT-EV v1 (`wafflestudio/snutt-ev`) | `origin/develop` 4792b9c (2026-09-12) | 운영은 `main`(b727a4f)이다. `develop`에만 있는 aaefbf0(#147, 강의평 목록 `sort`·`year`·`semester` 파라미터와 `evaluation_count` 필드)은 아래에서 **[dev]**로 따로 표시했다. |
| v2 (`snutt-v2`) | 54435ff | `v1compat` 모듈 + `api` 모듈 전역 설정 |
| Android | `snutt-android` `origin/develop` | |
| iOS | `snutt-ios` `origin/master`(기본 브랜치) | Tuist 앱 하나, swift-openapi-generator 클라이언트(`OpenAPI/openapi-prod.json`) |
| 어드민 웹 | `snutt-internal` `origin/main` 5f10a12 | `apps/snutt-admin-web`, `packages/backend` |
| 강의평 웹(snutt-ev-web), 테마 마켓 웹, 웹 클라이언트(snutt-frontend) | 로컬에 없음, 생략 | 앱 웹뷰가 띄운다. 호출 경로는 커밋 6e55abc 메시지(`NEXT_PUBLIC_EV_API_URL=https://snutt-api-dev.wafflestudio.com/ev-service` + `/v1/evaluations/...`)로만 추정했다. |

표기: 파일:줄은 v1은 `snutt/` 또는 `snutt-ev/` 레포 루트 기준, v1compat은 `v1compat/src/main/kotlin/com/wafflestudio/snutt/v1compat/` 기준(`V:`), v2 core는 `core/src/main/kotlin/com/wafflestudio/snutt/core/` 기준(`C:`)이다.

## 요약

| 항목 | 수 |
|---|---|
| v1 엔드포인트 (메서드+경로, `/v1` 없는 alias는 같은 행으로 묶음) | **162** (SNUTT 139, SNUTT-EV 23) |
| v1compat에 대응 엔드포인트 있음 | 142 |
| └ 코드상 형태 차이 없음 | 40 |
| └ 형태·에러·동작 차이 있음 | 102 |
| 대응 없음 | 20 |
| └ 의도적 제거로 보임(근거 있음) | 19 (v1 admin 쓰기 15: 커밋 ffee457, EV 서버 내부용 4: E5~E8) |
| └ 누락 (근거 없음) | 1 (EV `GET /health-check`, 영향 낮음) |
| 표 비고에 "확인"(parity 테스트로 확인 필요)이 붙은 행 | 19 (차이 상세·마이그레이션 관찰의 **확인 필요** 항목은 별도) |
| 구버전 앱(Android 또는 iOS)이 실제로 호출하는 엔드포인트 | 93 |

가장 위험한 차이 (자세한 내용은 [차이 상세](#차이-상세)):

1. **어드민 웹이 아직 `/v1/admin` 쓰기 API를 호출한다.** v2는 이를 의도적으로 지우고 `/v2/admin`으로 옮겼다(커밋 ffee457). 어드민 웹을 먼저 바꾸지 않으면 전환 직후 공지·설정·팝업·일기장 질문 관리가 404다.
2. **EV 에러코드 24001/24002가 뒤바뀌어 있다.** `V:error/V1CompatExceptionHandler.kt:188-190`가 v2 `LECTURE_NOT_FOUND`(분반)를 24001, `COURSE_NOT_FOUND`(과목)를 24002로 보내는데, EV에서 24001은 과목(LECTURE), 24002는 분반(SEMESTER_LECTURE)이다(`snutt-ev/core/.../ErrorType.kt:23-24`).
3. **푸시 설정을 저장한 적 없는 사용자의 `GET /v1/push/preferences`가 빈 목록이다.** v1은 기본값 3개(`LECTURE_UPDATE`, `VACANCY_NOTIFICATION`, `DIARY`, 모두 켜짐)를 돌려줬다. Android·iOS 모두 설정 화면에서 호출한다.
4. **`isEmailVerified`가 없던(null) 계정이 강의평 전체에서 0x3011로 막힌다.** v1 프록시는 `== false`만 막았고(`snutt/core/.../EvService.kt:46`), 이관은 null을 false로 넣고(`migration/.../UserStep.kt:67`), v1compat은 `!isEmailVerified`면 막는다(`V:auth/V1CompatAuth.kt:51`).
5. **EV 응답의 `user_id → user` 치환이 재현되지 않는다.** v1 프록시는 응답 어디에 있든 `user_id` 키를 `user:{id,email,local_id}`로 바꿨다(`snutt/core/.../EvService.kt:154-169`). v1compat은 `user_id`를 그대로 내보낸다. 강의평 웹이 `user`를 읽는지 확인해야 한다.
6. **v1 경로에서 `SnuttException`이 아닌 예외가 나면 v2 ProblemDetail 형식이 나간다.** `V1CompatExceptionHandler`에 `Exception` 핸들러가 없어 api의 `SnuttExceptionHandler.handleUnexpectedException`이 받는다. 구 Android는 `errcode`가 없는 바디를 파싱하지 못해 Unknown 에러가 되고, iOS는 5xx면 바디를 보지 않으므로 영향이 적다.
7. **EV 커서와 친구 초대 링크 토큰은 전환 시점에 전부 무효가 된다.** 형식이 다르다(EV: AES 암호화 커서 → v2: 평문 JSON Base64, 친구 링크: Redis 11자 토큰 → ES256 JWT).

## 공통 사항

### 경로 prefix

- v1 SNUTT는 모든 컨트롤러를 `/v1/x`와 `/x` 두 곳에 매핑했다(예: `snutt/api/.../controller/AuthController.kt:27-31`). v1compat은 `/v1/**`만 매핑한다. 예외는 EV(`/v1/ev-service/v1`, `/v1/ev/v1`, `/ev-service/v1`, `/ev/v1` 네 곳, `V:ev/EvPaths.kt`)와 정적 페이지(루트)다.
- Android(`SNUTTRestApi.kt`)와 iOS(openapi spec) 모두 절대경로 `/v1/...`만 쓴다. 강의평 웹은 `/ev-service/v1/...`를 쓰며(커밋 6e55abc) v1compat이 받는다. 어드민 웹은 `/v1/admin`만 쓴다(커밋 9635ace).
- 따라서 `/v1` 없는 SNUTT alias는 알려진 클라이언트가 쓰지 않는다. 제거를 의도적이라고 보고 표에는 별도 행으로 넣지 않았다. 알려지지 않은 호출자가 있는지는 운영 액세스 로그로 확인할 일이다.
- v1 `GET /v1/ev-service/{*path}` 등은 EV의 **아무 경로**나 프록시했다. v1compat은 EV 엔드포인트를 하나씩 구현했으므로 목록에 없는 경로는 404다.

### 인증

| | v1 SNUTT | v1compat |
|---|---|---|
| API key | `x-access-apikey` JWT(HS256, `string`∈{ios,web,android,test}, `key_version`="0"), 실패 시 403 0x2000 (`snutt/api/.../filter/ApiKeyWebFilter.kt`) | 같은 검증(`V:auth/V1CompatAuth.kt:63-95`, `V:auth/LegacyApiKeyVerifier.kt`). 비밀키는 `snutt.auth.legacy-secret-key` |
| 사용자 토큰 | `x-access-token` = `user.credentialHash`. 없으면 401 0x2001, 틀리면 403 0x2002 | `legacy_access_token`에서 SHA-256 해시로 조회. 없으면 401 0x2001, 틀리면 403 0x2002 (`V:auth/LegacyTokenService.kt`) |
| 무인증 | `@SnuttNoAuthApiFilterTarget`(API key만) | `@V1Public`(API key만) |
| 어드민 | `@SnuttAdminApiFilterTarget` → 403 0x2003 | `@V1AdminOnly` → 403 0x2003 |
| 클라이언트 헤더 | 잘못된 `x-os-type`은 40006, 잘못된 `x-app-type`은 40007 (`ClientInfoWebFilter.kt`) | 검증하지 않는다. 모르는 OS는 `UNKNOWN`으로 처리 |
| 강의평 | 프록시에서 `isEmailVerified == false`면 403 0x3011 | `@V1EmailVerifiedRequired`: `!isEmailVerified`면 403 0x3011 (null 처리 차이, 위 요약 4) |

토큰 수명:
- v1 토큰은 자격증명(비밀번호·소셜 연동)마다 하나였고 모든 기기가 공유했다. 비밀번호 변경·재설정, 소셜 연동·해제처럼 자격증명이 바뀌면 바뀌었다.
- v1compat은 로그인할 때마다 무작위 64자 hex 토큰을 새로 발급한다(`V:auth/LegacyTokenService.kt:23-30`). `UserCredentialChangedEvent`가 오면 사용자의 레거시 토큰을 모두 지운다.
- 두 쪽 모두 만료가 없다. 로그아웃해도 토큰은 남는다. v1compat 토큰 행은 정리되지 않아 테이블이 계속 커진다.

### JSON 직렬화

| | v1 SNUTT | v1 EV | v2 |
|---|---|---|---|
| 라이브러리 | Jackson 3, WebFlux | Jackson 3, MVC | Jackson 3, MVC |
| null | `non_null`(생략) | `non_null`(생략) | `non_null`(생략) |
| 이름 규칙 | 없음(DTO별 `@JsonProperty`) | `SNAKE_CASE` 전역, 일부 DTO만 LowerCamel | 없음(Legacy DTO별 `@JsonProperty`) |
| 시간대 | 미설정 | 미설정 | `time-zone: UTC` |
| 역직렬화 | `fail-on-null-for-primitives: false` | `fail-on-unknown-properties: false`, `fail-on-null-for-primitives: true` | 기본값 |

- 날짜: v1은 Mongo `LocalDateTime`을 `ZonedDateTime.of(this, UTC)`로 감싸 내보냈다(`snutt/core/.../common/extension/LocalDateTimeExtensions.kt`). Spring Data Mongo는 `Date`를 JVM 기본 시간대로 `LocalDateTime`으로 읽으므로, v1 컨테이너 JVM이 UTC가 아니면 v1 응답 값 자체가 어긋나 있었다. v2는 `Instant`로 `...Z`를 낸다. 소수부 자릿수도 다를 수 있다(v1은 Mongo 밀리초, v2는 MySQL `DATETIME(6)`). **parity 테스트로 확인 필요.** Android는 `Instant.parse`로 엄격하게 파싱하고(알림 `created_at`, 일기장 `date`), iOS는 ISO8601 ± 소수부를 받는다.
- id:
  - v1 SNUTT id는 Mongo ObjectId 24자 hex 문자열이다. v1compat은 Long PK를 문자열(`"123"`)로 낸다.
  - 대부분의 path id를 `Long`으로 받는다. 그래서 hex id가 오면 not-found 대신 400 40001이 난다. `GET /v1/tables/{id}`만 예외로 `toLongOrNull` 실패 시 40400을 준다(커밋 b1f6f57).

### 에러 응답

| | v1 SNUTT | v1compat |
|---|---|---|
| 바디 | `{errcode, title, message, displayMessage}` (`snutt/api/.../filter/ErrorWebFilter.kt:97-102`) | 같은 키 (`V:error/V1CompatExceptionHandler.kt:22-27`) |
| `message` | `ErrorType.errorMessage`(개발자용 문구, 예: "lecture가 없습니다") | `displayMessage`와 같은 값(사용자용 문구, `x-language`에 따라 ko/en) |
| `title` | v1 `ErrorType.title`, 기본값 "요청 실패" | v2 `ErrorType.title`(예: 시간 겹침은 "시간대 겹침") |
| 바디 파싱 실패, 필수 파라미터 누락, 타입 오류 | WebFlux `ServerWebInputException` → 400 **errcode 0** | 바디 문제는 40002, 파라미터 문제는 40001 |
| 처리되지 않은 예외 | 500 errcode 0 | **v2 ProblemDetail** 500 (`application/problem+json`, `errcode` 없음) |
| 매핑 없는 경로 | 404 errcode 0 | v2 ProblemDetail 404 |
| EV 프록시 에러 | EV 상태코드 + `{errcode: EV code, title:"", message: EV ErrorType.name, displayMessage: EV ErrorType.name}` | EV 상태코드 + `{errcode: EV code, title:"", message/displayMessage: 지역화된 문구}` |
| EV 검증 실패(@Valid, 바인딩) | EV가 빈 바디로 400 → 프록시가 파싱에 실패 → **500 errcode 0** | 400 + 40001/40002/40029/40030 |

errcode 매핑 차이(같은 상황인데 코드가 다른 것):

| 상황 | v1 | v1compat | 근거 |
|---|---|---|---|
| EV 과목(lecture) 없음 | 24001 | **24002** | `V:error/V1CompatExceptionHandler.kt:188-190` |
| EV 분반(semester lecture) 없음 | 24002 | **24001** | 같은 곳 |
| 태그 목록 없음 | 40408 | 40420 | `V:snutt/V1CompatMiscControllers.kt:68` |
| 시간표 강의 없음(수정·초기화) | 0x4003 | 0x4005 | `C:domain/timetable/service/TimetableLectureService.kt:286` |
| 일기장 대상 대표 시간표 없음 | 40400 | 40401 | `C:domain/diary/service/DiaryService.kt:101` |
| 일기장 제출 강의 없음 | 0x4003 | 40413 | `C:domain/diary/service/DiaryService.kt:129` |
| 테마 다운로드: 게시 안 된 테마 | 40406 (404) | 40022 (400) | `C:domain/theme/service/TimetableThemeService.kt:186-193` |
| EV 메인 태그 없음 | 24004 (404) | 40001 (400) | `V:ev/V1CompatEvController.kt:288` |
| 소셜 전용 사용자의 비밀번호 변경 | 0x2005 | 0x3000 | `C:domain/auth/service/AuthService.kt:226` |
| 비밀번호 찾기·아이디 찾기: 계정 없음 | 0x4004 | 200 `{"message":"ok"}` | `C:domain/user/service/PasswordResetService.kt:48-51,87-91` |
| 잘못된 `x-os-type`/`x-app-type` | 40006/40007 | 검증 안 함 | `V:auth/V1CompatAuth.kt:85` |
| 친구 목록의 잘못된 `state` | 500 errcode 0 | 40001 | `V:snutt/V1CompatDomainControllers.kt:212-214` |

v1에만 있던 코드: 40000 MISSING_PARAMETER(실제로 던지는 곳 없음), 40006, 40007, 40011, 40013, 40021 UPDATE_APP_VERSION(비밀번호 재설정 메일 마스킹 검사), 40025, 40402, 40408, 50001~50003, 40300.
v2에서 새로 생긴 코드: 40029, 40030, 40031, 40034~40037, 40102, 40103, 40416, 40420, 40910~40915, 50200, 50201.

## 엔드포인트별 표

클라이언트 열:
- **A**: Android가 호출
- **I**: iOS가 호출
- **EW**: 강의평 웹(snutt-ev-web, 추정)
- **TM**: 테마 마켓 웹(추정)
- **AD**: 어드민 웹(snutt-internal)
- **S2S**: v1 서버끼리 호출하던 내부용
- **-**: 알려진 클라이언트에 호출이 없음

상태 열:
- **일치**: 코드상 형태 차이 없음
- **차이**: 형태·에러·동작 중 하나 이상 다름
- **누락**: 대응 없음, 근거 없음
- **제거**: 의도적 제거로 보임
- **확인**이 붙은 행은 parity 테스트로 확인할 항목이 있다.

### SNUTT v1 (139)

| # | 메서드 | 경로 (v1, `/v1` 생략 alias 포함) | v1 위치 | v1compat 위치 | 클라 | 상태 | 비고 |
|---|---|---|---|---|---|---|---|
| 1 | POST | /v1/auth/register_local | AuthController.kt:35 | V:snutt/V1CompatAuthController.kt:93 | A,I | 차이 | email 필수(커밋 5d49581), 중복 검사 순서, 토큰 발급 방식 |
| 2 | POST | /v1/auth/login_local | AuthController.kt:40 | V1CompatAuthController.kt:99 | A,I | 차이 | 로그인마다 새 토큰 |
| 3 | POST | /v1/auth/login_fb | AuthController.kt:45 | V1CompatAuthController.kt:105 | - | 차이 | `fb_token` 외 `token`도 받음(상위집합), 토큰 방식 |
| 4 | POST | /v1/auth/login/facebook | AuthController.kt:50 | V1CompatAuthController.kt:105 | A,I | 차이 | 토큰 방식, 중복 이메일 displayMessage |
| 5 | POST | /v1/auth/login/google | AuthController.kt:55 | V1CompatAuthController.kt:111 | A,I | 차이 | 이메일 없는 소셜 가입: v1 500 → v2 가입 성공 |
| 6 | POST | /v1/auth/login/kakao | AuthController.kt:60 | V1CompatAuthController.kt:117 | A,I | 차이 | 위와 같음 |
| 7 | POST | /v1/auth/login_apple | AuthController.kt:65 | V1CompatAuthController.kt:123 | - | 차이 | 위와 같음 |
| 8 | POST | /v1/auth/login/apple | AuthController.kt:70 | V1CompatAuthController.kt:123 | I | 차이 | 위와 같음 |
| 9 | POST | /v1/auth/id/find | AuthController.kt:75 | V1CompatAuthController.kt:143 | A,I | 차이 | 계정이 없으면 v1 0x4004 → v2 ok, 발송 빈도 제한 추가 |
| 10 | POST | /v1/auth/password/reset/email/send | AuthController.kt:83 | V1CompatAuthController.kt:152 | A,I | 차이 | 계정 없음 0x4004 → ok, 40021 검사 없음, 코드 TTL 3분 → 15분 |
| 11 | POST | /v1/auth/password/reset/verification/code | AuthController.kt:91 | V1CompatAuthController.kt:170 | A,I | 차이 | `user_id` 누락 시 500 → 40001, 시도 횟수 제한 추가 |
| 12 | POST | /v1/auth/password/reset/email/check | AuthController.kt:100 | V1CompatAuthController.kt:161 | A,I | 일치 | |
| 13 | POST | /v1/auth/password/reset | AuthController.kt:105 | V1CompatAuthController.kt:180 | A,I | 일치 | 토큰 무효화도 같음 |
| 14 | POST | /v1/auth/logout | AuthController.kt:114 | V1CompatAuthController.kt:188 | A,I | 차이 | v1은 바디·`registration_id` 필수, v2는 선택 |
| 15 | GET | /v1/user/info | UserController.kt:41 | V:snutt/V1CompatUserController.kt:137 | - | 일치 | 날짜 형식 확인 |
| 16 | DELETE | /v1/user/account | UserController.kt:54 | V1CompatUserController.kt:142 | A,I | 차이 | v2는 기기·친구·소셜 연동도 정리 |
| 17 | POST | /v1/user/email/verification | UserController.kt:62 | V1CompatUserController.kt:150 | A,I | 차이 | `@SNU.AC.KR` 대문자 거부(v2 정규식이 대소문자 구분), 빈도 제한 |
| 18 | GET | /v1/user/email/verification | UserController.kt:71 | V1CompatUserController.kt:159 | - | 일치 | |
| 19 | DELETE | /v1/user/email/verification | UserController.kt:76 | V1CompatUserController.kt:164 | - | 일치 | |
| 20 | POST | /v1/user/email/verification/code | UserController.kt:84 | V1CompatUserController.kt:172 | A | 차이 | 시도 횟수 제한 추가 |
| 21 | POST | /v1/user/password | UserController.kt:93 | V1CompatUserController.kt:181 | A,I | 일치 | |
| 22 | PUT | /v1/user/password | UserController.kt:99 | V1CompatUserController.kt:190 | A,I | 차이 | 소셜 전용 사용자 0x2005 → 0x3000 |
| 23 | POST | /v1/user/facebook | UserController.kt:105 | V1CompatUserController.kt:199 | A,I | 차이 | 바디 `fb_token` alias 없음(`token`만) |
| 24 | POST | /v1/user/google | UserController.kt:111 | V1CompatUserController.kt:199 | A,I | 일치 | |
| 25 | POST | /v1/user/kakao | UserController.kt:117 | V1CompatUserController.kt:199 | A,I | 일치 | |
| 26 | POST | /v1/user/apple | UserController.kt:123 | V1CompatUserController.kt:199 | I | 차이 | `apple_token` alias 없음 |
| 27 | DELETE | /v1/user/facebook | UserController.kt:129 | V1CompatUserController.kt:209 | A,I | 일치 | |
| 28 | DELETE | /v1/user/google | UserController.kt:134 | V1CompatUserController.kt:209 | A,I | 일치 | |
| 29 | DELETE | /v1/user/kakao | UserController.kt:139 | V1CompatUserController.kt:209 | A,I | 일치 | |
| 30 | DELETE | /v1/user/apple | UserController.kt:144 | V1CompatUserController.kt:209 | I | 일치 | |
| 31 | GET | /v1/users/me | UserController.kt:161 | V1CompatUserController.kt:98 | A,I | 일치 | 날짜 형식 확인 |
| 32 | PATCH | /v1/users/me | UserController.kt:166 | V1CompatUserController.kt:103 | A,I | 차이 | 빈 닉네임: v1 40008 → v2 200(no-op) |
| 33 | GET | /v1/users/me/social_providers, /me/auth-providers | UserController.kt:175 | V1CompatUserController.kt:113 | A,I | 차이 | facebook 판정 기준(fbName ↔ 연동 행) |
| 34 | POST | /v1/user/device/{id} | DeviceController.kt:28 | V:snutt/V1CompatMiscControllers.kt:213 | A,I | 차이 | 다른 사용자 소유 기기 이전, 빈 id 검사 없음 |
| 35 | DELETE | /v1/user/device/{id} | DeviceController.kt:39 | V1CompatMiscControllers.kt:223 | - | 차이 | 행이 없으면 구독 해제 안 함 |
| 36 | GET | /v1/configs | ConfigController.kt:25 | V:snutt/V1CompatDomainControllers.kt:537 | A,I | 차이 | 같은 이름의 설정이 여러 개면 v1은 첫 번째, v2는 최신을 씀. 버전 헤더 없을 때 500 → `{}` |
| 37 | GET | /v1/course_books | CoursebookController.kt:28 | V1CompatMiscControllers.kt:133 | A,I | 일치 | |
| 38 | GET | /v1/course_books/recent | CoursebookController.kt:31 | V1CompatMiscControllers.kt:136 | I | 일치 | 없을 때 500 → 40420 |
| 39 | GET | /v1/course_books/official | CoursebookController.kt:37 | V1CompatMiscControllers.kt:139 | A,I | 일치 | |
| 40 | GET | /v1/buildings | BuildingController.kt:24 | V1CompatMiscControllers.kt:185 | A,I | 일치 | |
| 41 | GET | /v1/tables | TimetableController.kt:36 | V:snutt/V1CompatTimetableController.kt:73 | A,I | 일치 | 정렬 확인(양쪽 모두 ORDER BY 없음) |
| 42 | GET | /v1/tables/recent | TimetableController.kt:41 | V1CompatTimetableController.kt:91 | A,I | 일치 | |
| 43 | GET | /v1/tables/{year}/{semester} | TimetableController.kt:49 | V1CompatTimetableController.kt:100 | - | 차이 | 잘못된 학기 400 errcode 0 → 40001, 정렬 확인 |
| 44 | POST | /v1/tables (`?source=`) | TimetableController.kt:63 | V1CompatTimetableController.kt:111 | A,I | 차이 | `source`는 본인 시간표만(v1은 아무 시간표나 복사됨). 앱은 `source`를 보내지 않음. 제목 100자 제한 |
| 45 | GET | /v1/tables/{timetableId} | TimetableController.kt:78 | V1CompatTimetableController.kt:126 | A,I | 일치 | lecture_list 정렬 확인 |
| 46 | PUT | /v1/tables/{timetableId} | TimetableController.kt:87 | V1CompatTimetableController.kt:136 | A,I | 차이 | 같은 제목으로 저장: v1 0x3003 → v2 200 |
| 47 | DELETE | /v1/tables/{timetableId} | TimetableController.kt:98 | V1CompatTimetableController.kt:146 | A,I | 일치 | |
| 48 | POST | /v1/tables/{timetableId}/copy | TimetableController.kt:108 | V1CompatTimetableController.kt:155 | A,I | 차이 | 본인 시간표만, 복사 번호 규칙, 강의 `_id` 새로 발급 |
| 49 | PUT | /v1/tables/{timetableId}/theme | TimetableController.kt:118 | V1CompatTimetableController.kt:164 | A,I | 차이 | `theme`/`themeId`를 둘 다 주거나 둘 다 안 주면 500 → 40001 |
| 50 | POST | /v1/tables/{timetableId}/primary | TimetableController.kt:128 | V1CompatTimetableController.kt:178 | A,I | 차이 | 소유자 확인 추가(v1은 남의 시간표도 됨) |
| 51 | DELETE | /v1/tables/{timetableId}/primary | TimetableController.kt:134 | V1CompatTimetableController.kt:186 | A,I | 차이 | 위와 같음 |
| 52 | POST | /v1/tables/{timetableId}/lecture | TimetableLectureController.kt:34 | V1CompatTimetableController.kt:194 | A,I | 차이 | 시간 검증 규칙, 공백 제목 거부, 색 선택 규칙, `color:{}` 생략 |
| 53 | POST | /v1/tables/{timetableId}/lecture/{lectureId} | TimetableLectureController.kt:49 | V1CompatTimetableController.kt:222 | A,I | 일치 | 검사 순서와 0x300C 문구도 같음. hex id는 40001 |
| 54 | PUT | /v1/tables/{timetableId}/lecture/{timetableLectureId}/reset | TimetableLectureController.kt:65 | V1CompatTimetableController.kt:244 | A,I | 차이 | 0x4003 → 0x4005 |
| 55 | PUT | /v1/tables/{timetableId}/lecture/{timetableLectureId} | TimetableLectureController.kt:81 | V1CompatTimetableController.kt:263 | A,I | 차이 | 0x4003 → 0x4005, 시간을 바꿀 때만 겹침 검사, 색 규칙 |
| 56 | DELETE | /v1/tables/{timetableId}/lecture/{timetableLectureId} | TimetableLectureController.kt:98 | V1CompatTimetableController.kt:318 | A,I | 차이 | 없는 강의: v1 200 → 404 0x4005 |
| 57 | GET | /v1/tables/{timetableId}/lecture/reminders | TimetableLectureReminderController.kt:33 | V:snutt/V1CompatThemeDiaryControllers.kt:541 | A,I | 차이 | 소유자 확인 추가, 정렬 확인 |
| 58 | GET | /v1/tables/{timetableId}/lecture/{timetableLectureId}/reminder | TimetableLectureReminderController.kt:25 | V1CompatThemeDiaryControllers.kt:550 | A,I | 차이 | 소유자 확인 추가 |
| 59 | PUT | /v1/tables/{timetableId}/lecture/{timetableLectureId}/reminder | TimetableLectureReminderController.kt:40 | V1CompatThemeDiaryControllers.kt:560 | A,I | 차이 | 소유자 확인, 잘못된 option 400 errcode 0 → 40002 |
| 60 | GET | /v1/themes | TimetableThemeController.kt:37 | V1CompatThemeDiaryControllers.kt:180 | A,I | 차이 | 사용자 테마 정렬(updatedAt desc → id asc), publishInfo 채워짐, DOWNLOADED의 originId 의미 |
| 61 | GET | /v1/themes/best | TimetableThemeController.kt:42 | V1CompatThemeDiaryControllers.kt:185 | TM | 차이 | `id`가 게시본 id, 동률 정렬, page≤0 40001 |
| 62 | GET | /v1/themes/friends | TimetableThemeController.kt:51 | V1CompatThemeDiaryControllers.kt:192 | TM | 차이 | 게시를 내린 원본 제외 |
| 63 | GET | /v1/themes/{themeId} | TimetableThemeController.kt:61 | V1CompatThemeDiaryControllers.kt:209 | TM | 차이 | isDefault 실제값, 기본 테마 id 조회 허용 |
| 64 | POST | /v1/themes | TimetableThemeController.kt:67 | V1CompatThemeDiaryControllers.kt:215 | A,I | 차이 | 색 hex 검증(40002), 추가해도 기본 테마로 바뀌지 않음 |
| 65 | PATCH | /v1/themes/{themeId} | TimetableThemeController.kt:73 | V1CompatThemeDiaryControllers.kt:227 | A,I | 차이 | 다운로드한 테마 수정 40905, 기본 테마 의미 |
| 66 | POST | /v1/themes/{themeId}/publish | TimetableThemeController.kt:80 | V1CompatThemeDiaryControllers.kt:248 | TM | 차이 | 다운로드한 테마 게시 40905 |
| 67 | POST | /v1/themes/{themeId}/download | TimetableThemeController.kt:90 | V1CompatThemeDiaryControllers.kt:271 | TM | 차이 | 40406 → 40022 |
| 68 | POST | /v1/themes/search | TimetableThemeController.kt:97 | V1CompatThemeDiaryControllers.kt:203 | TM | 차이 | 대소문자 무시, `%`·`_` 미이스케이프, 정렬, 게시 내린 테마 제외 |
| 69 | DELETE | /v1/themes/{themeId} | TimetableThemeController.kt:106 | V1CompatThemeDiaryControllers.kt:238 | A,I | 일치 | |
| 70 | DELETE | /v1/themes/{themeId}/publish | TimetableThemeController.kt:112 | V1CompatThemeDiaryControllers.kt:261 | TM | 일치 | |
| 71 | POST | /v1/themes/{themeId}/copy | TimetableThemeController.kt:118 | V1CompatThemeDiaryControllers.kt:278 | A,I | 차이 | 기본 테마 복사 허용, 복사 번호 규칙 확인 |
| 72 | POST | /v1/themes/{themeId}/default | TimetableThemeController.kt:124 | V1CompatThemeDiaryControllers.kt:284 | - | 차이 | 기본 테마 id 허용 |
| 73 | POST | /v1/themes/basic/{n}/default | TimetableThemeController.kt:130 | V1CompatThemeDiaryControllers.kt:296 | - | 일치 | |
| 74 | DELETE | /v1/themes/{themeId}/default | TimetableThemeController.kt:141 | V1CompatThemeDiaryControllers.kt:290 | - | 차이 | v1은 저장하지 않음(no-op), v2는 SNUTT로 저장 |
| 75 | DELETE | /v1/themes/basic/{n}/default | TimetableThemeController.kt:147 | V1CompatThemeDiaryControllers.kt:305 | - | 차이 | 반환하는 테마 |
| 76 | POST | /v1/search_query | LectureSearchController.kt:29 | V:snutt/V1CompatLectureSearchController.kt:57 | A,I | 차이 | 기본 정렬(자연순 → id asc), 평점 출처, KO/EN 필터, limit 1..200 확인 |
| 77 | GET | /v1/tags/{year}/{semester} | TagController.kt:26 | V1CompatMiscControllers.kt:62 | A,I | 차이 | 40408 → 40420, `credit` 문자열 생성 방식, 목록 순서 확인 |
| 78 | GET | /v1/tags/{year}/{semester}/update_time | TagController.kt:36 | V1CompatMiscControllers.kt:96 | - | 차이 | 40408 → 40420 |
| 79 | GET | /v1/semesters/status | SemesterController.kt:22 | V1CompatMiscControllers.kt:246 | A,I | 일치 | |
| 80 | GET | /v1/friends | FriendController.kt:37 | V1CompatDomainControllers.kt:207 | A,I | 차이 | 정렬(created_at desc), `nickname.tag` 생략 가능, state 대소문자 |
| 81 | POST | /v1/friends | FriendController.kt:63 | V1CompatDomainControllers.kt:222 | A,I | 일치 | 빈 바디 |
| 82 | POST | /v1/friends/{friendId}/accept | FriendController.kt:69 | V1CompatDomainControllers.kt:233 | A,I | 일치 | |
| 83 | POST | /v1/friends/{friendId}/decline | FriendController.kt:75 | V1CompatDomainControllers.kt:241 | A,I | 일치 | |
| 84 | PATCH | /v1/friends/{friendId}/display-name | FriendController.kt:81 | V1CompatDomainControllers.kt:249 | A,I | 일치 | |
| 85 | DELETE | /v1/friends/{friendId} | FriendController.kt:88 | V1CompatDomainControllers.kt:258 | A,I | 일치 | |
| 86 | GET | /v1/friends/generate-link | FriendController.kt:94 | V1CompatDomainControllers.kt:266 | A,I | 차이 | 토큰 형식: 11자 → JWT |
| 87 | POST | /v1/friends/accept-link/{requestToken} | FriendController.kt:102 | V1CompatDomainControllers.kt:271 | A,I | 차이 | 전환 전에 발급된 링크는 40409 |
| 88 | GET | /v1/friends/{friendId}/primary-table | FriendTableController.kt:32 | V1CompatDomainControllers.kt:280 | A,I | 차이 | 친구가 그 학기에 시간표가 없음: 40400 → 40401 |
| 89 | GET | /v1/friends/{friendId}/coursebooks | FriendTableController.kt:52 | V1CompatDomainControllers.kt:294 | A,I | 일치 | |
| 90 | GET | /v1/friends/{friendId}/registered-course-books | FriendTableController.kt:67 | V1CompatDomainControllers.kt:294 | - | 일치 | |
| 91 | GET | /v1/bookmarks | BookmarkController.kt:33 | V1CompatDomainControllers.kt:387 | A,I | 차이 | 정렬(추가 순 → id 순), `categoryPre2025` 추가 |
| 92 | GET | /v1/bookmarks/lectures/{lectureId}/state | BookmarkController.kt:52 | V1CompatDomainControllers.kt:412 | I | 일치 | |
| 93 | POST | /v1/bookmarks/lecture | BookmarkController.kt:58 | V1CompatDomainControllers.kt:418 | A,I | 차이 | `lecture_id` String → Long 변환 확인 |
| 94 | DELETE | /v1/bookmarks/lecture | BookmarkController.kt:66 | V1CompatDomainControllers.kt:426 | A,I | 차이 | 바디가 있는 DELETE. 북마크 문서가 없을 때의 v1 동작 확인 |
| 95 | GET | /v1/notification | NotificationController.kt:26 | V1CompatDomainControllers.kt:330 | A,I | 차이 | limit 1..200 검증, 동시각 정렬 보조키, 제목·본문 지역화 |
| 96 | GET | /v1/notification/count | NotificationController.kt:40 | V1CompatDomainControllers.kt:364 | A,I | 일치 | |
| 97 | GET | /v1/popups | PopupController.kt:26 | V1CompatDomainControllers.kt:512 | A,I | 일치 | 이미지 URI 형식 확인(서명 URL ↔ 공개 URL) |
| 98 | GET | /v1/push/preferences | PushPreferenceController.kt:25 | V1CompatDomainControllers.kt:551 | A,I | **차이** | 저장 이력 없으면 빈 목록(v1 기본값 3개). 요약 3 |
| 99 | POST | /v1/push/preferences | PushPreferenceController.kt:30 | V1CompatDomainControllers.kt:556 | A,I | 차이 | v1은 4종 모두 저장, v2는 요청한 것만 저장하고 나머지 삭제 |
| 100 | GET | /v1/vacancy-notifications/lectures | VacancyNotificationController.kt:31 | V1CompatDomainControllers.kt:446 | A,I | 차이 | 정렬 확인, registrationCount 출처 |
| 101 | GET | /v1/vacancy-notifications/lectures/{lectureId}/state | VacancyNotificationController.kt:40 | V1CompatDomainControllers.kt:469 | - | 차이 | 없는 강의: `{exists:false}` → 0x4003 |
| 102 | POST | /v1/vacancy-notifications/lectures/{lectureId} | VacancyNotificationController.kt:46 | V1CompatDomainControllers.kt:475 | A,I | 일치 | |
| 103 | DELETE | /v1/vacancy-notifications/lectures/{lectureId} | VacancyNotificationController.kt:54 | V1CompatDomainControllers.kt:483 | A,I | 차이 | 없는 강의: 200 → 0x4003 |
| 104 | POST | /v1/feedback | FeedbackController.kt:26 | V1CompatDomainControllers.kt:571 | A,I | 차이 | email 선택, GitHub 장애 500 → 502 50201 |
| 105 | GET | /member | StaticPageController.kt:30 | V:snutt/V1CompatStaticPageController.kt:11 | A | 차이 | 200 HTML → 301 `/v2/static/member`. 내용도 갱신됨 |
| 106 | GET | /privacy_policy | StaticPageController.kt:36 | V1CompatStaticPageController.kt:14 | A | 차이 | 301 |
| 107 | GET | /terms_of_service | StaticPageController.kt:42 | V1CompatStaticPageController.kt:17 | A | 차이 | 301 |
| 108 | GET | /v1/ev/lectures/{lectureId}/summary | EvController.kt:21 | V1CompatThemeDiaryControllers.kt:589 | A | 차이 | 없는 강의 0x4003, Course 없으면 200 `evaluationCount:0`, `evLectureId`=course id |
| 109 | POST | /v1/diary/questionnaire | DiaryController.kt:43 | V1CompatThemeDiaryControllers.kt:406 | A,I | 차이 | 40400 → 40401 |
| 110 | GET | /v1/diary/target | DiaryController.kt:51 | V1CompatThemeDiaryControllers.kt:436 | A,I | 차이 | 40400 → 40401, courseTitle 출처 확인 |
| 111 | GET | /v1/diary/my | DiaryController.kt:62 | V1CompatThemeDiaryControllers.kt:458 | A,I | 차이 | 학기 그룹 정렬 없음, 날짜 소수부, Android ≤3.12.4 형식 분기는 유지 |
| 112 | GET | /v1/diary/dailyClassTypes | DiaryController.kt:90 | V1CompatThemeDiaryControllers.kt:452 | A,I | 차이 | 정렬: 자연순 → name asc |
| 113 | POST | /v1/diary | DiaryController.kt:96 | V1CompatThemeDiaryControllers.kt:499 | A,I | 차이 | 강의 없음 0x4003 → 40413, answerIndex 범위·중복 검증 추가 |
| 114 | DELETE | /v1/diary/{id} | DiaryController.kt:105 | V1CompatThemeDiaryControllers.kt:516 | A,I | 일치 | |
| 115 | GET | /v1/ev-service/v1/users/me/lectures/latest | EvServiceController.kt:45 | V:ev/V1CompatTakenLectureController.kt:70 | EW | 차이 | 같은 과목을 두 학기에 들은 경우 행 수, `filter` 의미, `limit` 무시, 필드 추가 |
| 116 | GET | /v1/ev-service/{*path} (프록시) | EvServiceController.kt:52 | 아래 EV 행으로 개별 구현 | EW | 차이 | 목록에 없는 경로는 404. 이메일 인증 null 처리, `user_id→user` 치환 없음 |
| 117 | POST | /v1/ev-service/{*path} (프록시) | EvServiceController.kt:60 | 개별 구현 | EW | 차이 | 위와 같음 |
| 118 | DELETE | /v1/ev-service/{*path} (프록시) | EvServiceController.kt:68 | 개별 구현 | EW | 차이 | 위와 같음 |
| 119 | PATCH | /v1/ev-service/{*path} (프록시) | EvServiceController.kt:76 | 개별 구현 | EW | 차이 | 위와 같음 |
| 120 | POST | /v1/admin/insert_noti | AdminController.kt:56 | 없음 → `/v2/admin/notifications` | AD | 제거 | 커밋 ffee457 "v1 admin 쓰기 제거, /v2/admin으로 일원화". 어드민 웹이 아직 호출 |
| 121 | POST | /v1/admin/configs/{name} | AdminController.kt:64 | 없음 → `/v2/admin/configs/{name}` | AD | 제거 | 위와 같음 |
| 122 | GET | /v1/admin/configs/{name} | AdminController.kt:73 | 없음 → `/v2/admin/configs/{name}` | AD | 제거 | 위와 같음 |
| 123 | DELETE | /v1/admin/configs/{name}/{id} | AdminController.kt:78 | 없음 → v2 | AD | 제거 | 위와 같음 |
| 124 | PATCH | /v1/admin/configs/{name}/{id} | AdminController.kt:86 | 없음 → v2 | AD | 제거 | 위와 같음 |
| 125 | POST | /v1/admin/images/{source}/upload-uris | AdminController.kt:96 | 없음 → v2 | AD | 제거 | 위와 같음 |
| 126 | POST | /v1/admin/popups | AdminController.kt:106 | 없음 → v2 | AD | 제거 | 위와 같음 |
| 127 | DELETE | /v1/admin/popups/{id} | AdminController.kt:115 | 없음 → v2 | AD | 제거 | 위와 같음 |
| 128 | GET | /v1/admin/diary/dailyClassTypes | AdminController.kt:122 | V:snutt/V1AdminController.kt:121 | AD | 차이 | v2 엔티티를 그대로 직렬화: id가 숫자, createdAt/updatedAt 추가 |
| 129 | GET | /v1/admin/diary/questions | AdminController.kt:125 | V1AdminController.kt:124 | AD | 차이 | `answers`→`answerList`, `shortAnswers`→`shortAnswerList`, `targetDailyClassTypeIds` 없음 |
| 130 | POST | /v1/admin/diary/dailyClassTypes | AdminController.kt:128 | 없음 → v2 | AD | 제거 | ffee457 |
| 131 | DELETE | /v1/admin/diary/dailyClassTypes | AdminController.kt:136 | 없음 → v2 | AD | 제거 | ffee457 |
| 132 | POST | /v1/admin/diary/questions | AdminController.kt:144 | 없음 → v2 | AD | 제거 | ffee457 |
| 133 | DELETE | /v1/admin/diary/questions/{id} | AdminController.kt:152 | 없음 → v2 | AD | 제거 | ffee457 |
| 134 | POST | /v1/admin/diary/notifier/trigger | AdminController.kt:160 | 없음 → v2 | - | 제거 | ffee457 |
| 135 | GET | /v1/admin/registrationPeriods | AdminController.kt:166 | V1AdminController.kt:65 | - | 차이 | 엔티티 직렬화: `registrationPeriods`→`registrationPeriodList`, id 숫자 |
| 136 | GET | /v1/admin/registrationPeriods/{year}/{semester} | AdminController.kt:169 | V1AdminController.kt:68 | - | 차이 | 위와 같음 |
| 137 | PATCH | /v1/admin/registrationPeriods/{year}/{semester} | AdminController.kt:175 | 없음 → v2 | - | 제거 | ffee457 |
| 138 | DELETE | /v1/admin/registrationPeriods/{year}/{semester} | AdminController.kt:185 | 없음 → v2 | - | 제거 | ffee457 |
| 139 | GET | /v1/admin/users/search | AdminController.kt:194 | V1AdminController.kt:74 | AD | 차이 | 정확 일치 → 부분 일치(대소문자 무시), 비활성 사용자 제외, authProviders 순서 |

v1 위치의 파일은 모두 `snutt/api/src/main/kotlin/controller/`에 있다. v1compat 위치의 파일명만 적힌 행은 바로 위 행과 같은 디렉터리다.

### SNUTT-EV v1 (23)

구 클라이언트는 EV를 직접 부르지 않고 SNUTT 프록시(`/v1/ev-service/...`, `/ev-service/...`)를 거친다. 표의 경로는 EV 서버 경로다. v1compat은 이 경로를 `/v1/ev-service`, `/v1/ev`, `/ev-service`, `/ev` 네 prefix 아래에 둔다. v1 위치는 `snutt-ev/api/src/main/kotlin/com/wafflestudio/snuttev/` 기준이다.

| # | 메서드 | EV 경로 | v1 위치 | v1compat 위치 | 클라 | 상태 | 비고 |
|---|---|---|---|---|---|---|---|
| E1 | GET | /v1/lectures | controller/LectureController.kt:40 | V:ev/V1CompatTakenLectureController.kt:215 | EW | 차이 | 정렬(DB 순 → evalCount desc, id asc), 학기 태그가 있을 때 중복 행, **[dev]** `evaluation_count` 항상 포함 |
| E2 | GET | /v1/lectures/{id}/semester-lectures | LectureController.kt:45 | V1CompatTakenLectureController.kt:234 | EW | 차이 | `semester_lectures[].id`가 v2 lecture id(EV id 보존 안 됨), 없음 24001 → 24002 |
| E3 | GET | /v1/lectures/id?course_number&instructor | LectureController.kt:78 | V1CompatTakenLectureController.kt:191 | EW | 일치 | trim 차이만 |
| E4 | GET | /v1/lectures/id?semesterLectureSnuttId | LectureController.kt:105 | V1CompatTakenLectureController.kt:202 | EW | 차이 | 구 ObjectId는 찾지 못함(24001) |
| E5 | GET | /v1/lectures/ids | LectureController.kt:131 | 없음 | S2S | 제거 | SNUTT↔EV 내부용(SnuttUserFilter 제외 목록). 병합으로 불필요 |
| E6 | GET | /v1/lectures/snutt-summary | LectureController.kt:160 | 없음 | S2S | 제거 | 위와 같음 |
| E7 | GET | /v1/users/me/lectures/latest | LectureController.kt:169 | 115번 행이 대체 | - | 제거 | EV 주석 "삭제 예정". 프록시 GET은 SNUTT 특수 핸들러로 가므로 원래 닿지 않음 |
| E8 | POST | /v1/users/me/lectures/latest | LectureController.kt:192 | 없음 | S2S | 제거 | SNUTT가 시간표 강의 목록을 담아 호출하던 내부용 |
| E9 | POST | /v1/semester-lectures/{id}/evaluations | controller/EvaluationController.kt:47 | V:ev/V1CompatEvController.kt:139 | EW | 차이 | content 20자 미만 20004, rating 범위 위반 500 → 40029, 분반 없음 24002 → 24001, `user` 없음 |
| E10 | GET | /v1/lectures/{id}/evaluation-summary | EvaluationController.kt:66 | V1CompatEvController.kt:168 | EW | 차이 | **[dev]** `evaluation_count`, 평균값 출처(실시간 AVG ↔ 집계 캐시) 확인, 24001 → 24002 |
| E11 | GET | /v1/lectures/{id}/evaluations | EvaluationController.kt:115 | V1CompatEvController.kt:112 | EW | 차이 | 커서 비호환, **[dev]** sort/year/semester, `user` 없음, `is_mine` 추가, 없는 과목 200 빈 목록 → 24002 |
| E12 | GET | /v1/lectures/{id}/evaluations/users/me | EvaluationController.kt:133 | V1CompatEvController.kt:159 | EW | 차이 | 없는 과목 200 → 24002, `user` 없음 |
| E13 | GET | /v1/evaluations/users/me | EvaluationController.kt:139 | V1CompatEvController.kt:196 | EW | 차이 | 커서 비호환, `user` 없음 |
| E14 | GET | /v1/tags/main/{id}/evaluations | EvaluationController.kt:145 | V1CompatEvController.kt:281 | EW | 차이 | 없는 태그 24004 → 40001, 메인이 아닌 태그 id 거부, 교양 판정 규칙 |
| E15 | GET | /v1/evaluations/{id} | EvaluationController.kt:152 | V1CompatEvController.kt:207 | EW | 차이 | `user` 없음, `is_mine` 추가 |
| E16 | PATCH | /v1/evaluations/{id} | EvaluationController.kt:158 | V1CompatEvController.kt:216 | EW | 차이 | content 20자 규칙, 에러 순서(이동 대상 조회가 소유자 확인보다 먼저) |
| E17 | DELETE | /v1/evaluations/{id} | EvaluationController.kt:166 | V1CompatEvController.kt:240 | EW | 일치 | |
| E18 | POST | /v1/evaluations/{id}/report | EvaluationController.kt:182 | V1CompatEvController.kt:248 | EW | 차이 | 빈 content 500 → 40030, `user` 없음 |
| E19 | POST | /v1/evaluations/{id}/likes | EvaluationController.kt:190 | V1CompatEvController.kt:264 | EW | 차이 | 빈 바디 → `{like_count,is_liked}`(커밋 5b9efc2) |
| E20 | DELETE | /v1/evaluations/{id}/likes | EvaluationController.kt:196 | V1CompatEvController.kt:270 | EW | 차이 | 위와 같음 |
| E21 | GET | /v1/tags/main | controller/TagController.kt:13 | V1CompatEvController.kt:276 | EW | 차이 | `key` 추가(커밋 a2e5724), 교양(317) 삽입으로 ordering 밀림 |
| E22 | GET | /v1/tags/search | TagController.kt:16 | V1CompatTakenLectureController.kt:188 | EW | 차이 | 태그 그룹 `id`가 PK 대신 ordering |
| E23 | GET | /health-check | common/HealthCheckController.kt:10 | 없음 | - | 누락 | 프록시 경유 `/v1/ev-service/health-check`. 영향 낮음 |

### v1에 없고 v1compat에만 있는 것

| 메서드 | 경로 | 위치 | 비고 |
|---|---|---|---|
| POST | /v1/auth/token/exchange | V:snutt/V1CompatAuthController.kt:129 | 레거시 토큰을 v2 access/refresh 토큰으로 바꿔 준다. 신버전 앱 전환용. 교환 후에도 레거시 토큰은 살아 있다 |
| GET | /v1/admin/mailLogs | V:snutt/V1AdminController.kt:101 | 메일 발송 로그 조회(커밋 9af9229) |
| * | /v1/ev/v1/**, /ev/v1/** | V:ev/EvPaths.kt | EV 경로 alias. v1에는 `/v1/ev`, `/ev` 아래에 요약 1개만 있었다 |

## 차이 상세

엔드포인트 단위의 세부 사항이다. 표의 비고가 짧은 행은 여기에 근거를 적었다.

### 인증·계정

- **register_local email 필수** (#1): v1 `LocalRegisterRequest.email: String?`(`snutt/core/.../users/dto/LocalRegisterRequest.kt`) ↔ v1compat `email: String`(`V:snutt/V1CompatAuthController.kt:24-28`). email을 빼면 40002다. 커밋 5d49581로 의도된 변경이다. Android·iOS 모두 email을 보낸다.
- **중복 검사 순서** (#1): v1은 email 중복(40901)을 localId 중복(0x3002)보다 먼저 본다(`snutt/core/.../UserService.kt:191-200`). v2는 반대다(`C:domain/auth/service/AuthService.kt:63-70`).
- **로그인 토큰** (#1~#8, #21~#30): 공통 사항의 토큰 수명 참조. 응답 `{user_id, token, message:"ok"}`의 모양은 같다.
- **아이디 찾기·비밀번호 재설정 메일** (#9, #10): 계정이 없을 때 v1은 0x4004, v2는 ok를 준다(계정 존재 노출 방지 목적으로 보인다). 발송 빈도 제한은 1분에 1회, 1시간에 5회이고 넘으면 40017이다(`C:common/util/CodeChallengeStore.kt:27-38`). v1에서 마스킹 결과가 입력과 같을 때 던지던 40021은 사라졌다.
- **소셜 연동 바디 alias** (#23, #26): v1 `SocialLoginRequest`는 `fb_token`·`apple_token`도 받았다. v1compat `LegacySocialTokenRequest`는 `token`만 받는다(`V:snutt/V1CompatUserController.kt:71-73`). Android·iOS 모두 `token`을 보내므로 앱 영향은 없다.
- **이메일 인증 정규식** (#17): v1은 `endsWith("@snu.ac.kr", ignoreCase = true)`, v2는 `^[a-zA-Z0-9._%+-]+@snu\.ac\.kr$`(대소문자 구분, `C:domain/user/service/EmailVerificationService.kt:27`)이다.
- **빈 닉네임** (#32): v1은 40008, v1compat은 무시하고 200이다(`V:snutt/V1CompatUserController.kt:108-109`).
- **탈퇴** (#16): v2는 기기·친구·소셜 연동 정리와 세션 폐기를 함께 한다(커밋 17aac7e). 응답은 같다.

### 시간표·강의

- **lecture 데이터 출처**: v1 시간표 강의와 북마크 강의는 추가 시점에 복사한 스냅샷이다(`snutt/core/.../timetables/data/TimetableLecture.kt`). v2는 수강편람 lecture와 사용자 override를 조회 시점에 조인한다(`C:domain/timetable/dto/TimetableDisplay.kt`). 수강편람 동기화 뒤에는 quota·remark 같은 값이 v1과 달라질 수 있다. parity 비교에서는 동기화 이전 스냅샷끼리 비교해야 한다.
- **`snuttEvLecture`**: v1은 EV 서버를 호출해 붙였고 EV가 장애면 빠졌다. v1compat은 `courseId`가 있으면 항상 붙인다(`V:snutt/dto/LegacyTimetableDtos.kt:126`, `LegacyLectureDtos.kt:92`). `evLectureId`는 EV lecture id 그대로다(이관이 id 보존, 아래 마이그레이션 관찰).
- **직접 만든 강의 색** (#52, #55): v1은 받은 `color`·`colorIndex`를 그대로 저장했고, 색이 없으면 `color:{}`, `colorIndex:0`이었다. v1compat은 `colorIndex > 0`이면 팔레트 색을 쓰고, 색이 없으면 가장 적게 쓰인 팔레트 색 중 하나를 무작위로 고르고, `bg`/`fg` 중 하나만 오면 40002다(`V:snutt/V1CompatTimetableController.kt:297-316`). 기본 테마에서 직접 지정한 색이 없으면 `color`를 생략한다(`LegacyTimetableDtos.kt:19-24`). 무작위 선택이 있으므로 parity 비교는 색 필드를 마스킹하거나 시드를 고정해야 한다.
- **시간 검증** (#52, #55): v1은 23:55를 넘는 종료와 5분 미만 수업을 거부했다(`snutt/core/.../ClassPlaceAndTimeLegacyRequestDto.kt:13-21`). v2는 종료 1440분 이하, 1분 수업 허용, start ≥ end면 0x100C다(`C:domain/timetable/service/TimetableLectureService.kt:304-311`). `startMinute`/`endMinute` 누락은 40002다.
- **소유자 확인 추가** (#44, #48, #50, #51, #57~#59): v1은 `findById`로 다른 사용자의 시간표를 복사하거나 대표로 지정하거나 리마인더를 읽고 바꿀 수 있었다(`snutt/core/.../TimetableService.kt:199,311,338`, `TimetableLectureReminderService.kt:39,52`). v1compat은 막는다. 앱은 `source` 파라미터를 보내지 않고 자기 시간표에만 이 API를 쓰므로 회귀가 아니라 보안 수정이다.
- **강의 검색** (#76): 기본 정렬이 v1은 Mongo 자연순, v2는 `id asc`다. 평점 정렬은 v1이 lecture에 비정규화된 `evInfo`, v2는 course 집계를 쓴다(`C:domain/lecture/repository/LectureRepository.kt:317-324`). KO 요청에서도 분류·학과 필터가 EN 값까지 매칭한다. limit이 1..200을 벗어나면 40001이다(v1은 제한 없음, `limit:0`은 무제한).
- **태그** (#77): `credit`을 v1은 수집 결과를 그대로 썼고, v1compat은 `"N학점"`/`"N credits"`를 생성한다(`V:snutt/V1CompatMiscControllers.kt:74-81`). 목록 순서는 v2에서 강의 DISTINCT 결과다.

### 테마

- **기본 테마 의미** (#60, #64, #65, #74): v1에서 기본 테마는 "updatedAt이 가장 최근인 사용자 테마, 없으면 SNUTT"였다(`snutt/core/.../TimetableThemeService.kt:348-352`). 그래서 테마를 추가하거나 수정하면 기본 테마가 바뀌었다. v2는 사용자 설정에 명시적으로 저장한다(`C:domain/theme/service/TimetableThemeService.kt:132-154`).
- **테마 마켓 id** (#61~#68): v1 마켓 목록의 `id`는 게시자 테마 id였다. v1compat은 게시본(published_theme) id다. 다운로드도 게시본 id를 받으므로 v1compat 안에서는 일관된다. 테마 마켓 웹이 id를 캐시하지 않는다면 영향이 없다.
- **DOWNLOADED `origin.originId`** (#60): v1은 원본 테마 id, v1compat은 게시본 id다(`V:snutt/V1CompatThemeDiaryControllers.kt:168-171`).

### 일기장

- **`/diary/my` 정렬** (#111): v1은 학기 그룹을 year desc, semester desc로 정렬했다(`snutt/api/.../DiaryController.kt:54`). v1compat은 작성 시각 desc 순서로 처음 나온 그룹 순이다(`V:snutt/V1CompatThemeDiaryControllers.kt:475-496`). 지난 학기 일기를 나중에 쓴 사용자에게서 순서가 달라진다.
- **Android ≤ 3.12.4 날짜 형식**: 두 쪽 모두 오프셋 없는 ISO_LOCAL_DATE_TIME으로 분기한다. 기존 계약 테스트 `V1CompatContractTest`에 케이스가 있다.

### 강의평(EV)

- **`user_id` → `user`**: 요약 5 참조. v1 `EvUserDto`는 `{id, email, local_id}`라 다른 리뷰어의 이메일까지 노출하는 v1 버그이기도 했다. 재현하지 않는 쪽이 맞을 수 있다. 다만 강의평 웹이 `user` 객체를 읽는다면 화면이 깨진다. **parity 테스트로 확인 필요**(웹 소스 확인 필요).
- **커서** (E11, E13, E14): EV 커서는 AES/ECB 암호화 + Base64url이다(`snutt-ev/core/.../PageUtils.kt:13-31`). 해독에 실패하면 예외 없이 첫 페이지를 줬다. v2는 평문 JSON Base64url이고 해독 실패 시 400 20001이다(`C:common/pagination/CursorPage.kt:45-58`). 빈 문자열 `cursor=`도 v2에서는 20001이다. **강의평 웹이 첫 페이지에 `cursor=`를 보내는지 확인 필요.**
- **에러 message**: v1 EV 에러의 `message`·`displayMessage`는 `ErrorType.name`(예: `EVALUATION_ALREADY_EXISTS`)이었다. v1compat은 지역화된 문구를 준다. 웹이 `message`로 분기한다면 깨진다.
- **[dev] 필드**: `evaluation_count`(E1, E10), `sort`/`year`/`semester`(E11)는 운영 EV(`main`)에 아직 없다. 운영 응답과 비교하면 v1compat 쪽에 필드가 하나 더 있다. 클라이언트가 모르는 필드는 무시하므로 영향은 낮다.
- **태그 id** (E14, E21, E22): 메인 태그는 v1compat이 고정 id(최신 1, 추천 2, 명강 3, 꿀강 4, 고진감래 5, 교양 317)를 쓴다(`V:ev/V1CompatEvMappers.kt:140-168`). EV seed(`snutt-ev/.../data/tag.csv`)의 1~5와 같다. 317과 운영 DB의 실제 id는 **확인 필요**. 검색 태그 그룹 id는 v1compat이 ordering 값을 쓴다(`V:ev/LegacySearchTag.kt`). EV는 PK(seed 기준 ordering+1)였다.

### 어드민

- 어드민 웹(`snutt-internal/packages/backend/src/index.ts:76-222`)은 `/v1/admin/insert_noti`, `configs` CRUD, `images/{source}/upload-uris`, `popups` 생성·삭제, `diary/questions`·`diary/dailyClassTypes` CRUD, `users/search`를 호출한다.
- v1compat에는 GET 4개(`diary/dailyClassTypes`, `diary/questions`, `registrationPeriods` 2개)와 `users/search`만 남아 있고, 그마저 v2 엔티티를 그대로 직렬화해 필드명이 다르다(#128, #129, #135, #136).
- 결론: 어드민 웹을 `/v2/admin`으로 옮기는 작업이 전환의 선행 조건이다. v1compat에 남은 어드민 GET은 쓰는 곳이 없거나(registrationPeriods), 모양이 달라 어드민 웹이 그대로 쓰기 어렵다.

## parity 테스트 시나리오 후보

구 앱이 실제로 쓰는 흐름 순서로 우선순위를 매겼다. P0 = 전환 당일 깨지면 앱 사용이 불가능한 흐름, P1 = 주요 기능, P2 = 부가 기능. 각 시나리오는 같은 데이터(이관 전 v1 데이터와 이관 후 v2 데이터)에 같은 요청을 보내 응답을 비교하고, 비교할 때 id·시각·무작위 색은 마스킹한다.

| 우선 | 시나리오 | 엔드포인트 | 확인할 것 |
|---|---|---|---|
| P0 | 이관된 레거시 토큰으로 앱 시작 | `GET /v1/users/me`, `GET /v1/tables/recent`, `GET /v1/tables`, `GET /v1/configs`, `GET /v1/popups`, `GET /v1/notification/count` | 이관 전 `credentialHash`가 그대로 통하는지. 날짜 문자열 형식·소수부, `_id` 문자열, 빠진 필수 필드(Android Moshi·iOS Codable 기준 필수 필드 목록은 [마이그레이션 관련 관찰](#마이그레이션-관련-관찰) 아래 참고) |
| P0 | 로그인·가입 | `POST /v1/auth/login_local`, `register_local`, `login/{provider}` | 응답 키, 틀린 비밀번호 0x2005, 없는 아이디 0x2004, 중복 아이디 0x3002, 토큰으로 다음 요청 성공 |
| P0 | 인증 실패 | 토큰 없음, 틀린 토큰, 틀린 API key | 401 0x2001, 403 0x2002, 403 0x2000. 앱이 이 코드로 강제 로그아웃한다(iOS `AuthenticationMiddleware.swift:50-55`, Android `ExceptionMapper.kt`) |
| P0 | 시간표 강의 추가·겹침·강제 추가 | `POST /v1/tables/{id}/lecture/{lectureId}`(+`isForced`), `POST /v1/tables/{id}/lecture` | 403 0x300C와 displayMessage 문구. 앱이 이 코드로 덮어쓰기 대화상자를 띄운다 |
| P0 | 시간표 CRUD | `POST/PUT/DELETE /v1/tables`, `/copy`, `/theme`, `/primary` | brief 목록 정렬, `total_credit`, 복사 제목 번호, 마지막 시간표 삭제 40010 |
| P0 | 처리되지 않은 예외의 응답 형식 | 500을 유발하는 요청 | `errcode`가 있는 레거시 바디인지(현재 ProblemDetail, 요약 6) |
| P1 | 강의 검색 | `POST /v1/search_query`, `GET /v1/tags/{y}/{s}` | 같은 쿼리의 결과 집합과 순서(기본 정렬, 평점순), `snuttEvLecture`, `registrationCount`/`wasFull`, 태그 목록 순서와 `credit` 문자열 |
| P1 | 직접 만든 강의 수정 | `PUT /v1/tables/{id}/lecture/{tlid}`, `/reset`, `DELETE` | 시간 검증 경계(23:55, 5분), 색 필드, 0x4003↔0x4005 |
| P1 | 친구 | `GET /v1/friends?state=`, `POST /v1/friends`, accept/decline, `primary-table`, `coursebooks`, `generate-link`/`accept-link` | 목록 정렬, `nickname.tag`, 친구 시간표 DTO(camelCase), 40400↔40401 |
| P1 | 푸시 설정 | `GET/POST /v1/push/preferences` | 저장 이력 없는 사용자의 기본값(요약 3) |
| P1 | 북마크·빈자리 알림 | `/v1/bookmarks*`, `/v1/vacancy-notifications/*` | 정렬, `lecture_id` 숫자 문자열 처리, DELETE 바디, 이전 학기 40005, 중복 40900 |
| P1 | 알림 목록 | `GET /v1/notification?offset&limit&explicit` | `created_at` 형식(Android `Instant.parse`), 정렬, `type` 정수, explicit 시 `notificationCheckedAt` 갱신 |
| P1 | 강의평 웹 흐름(프록시 경유) | `/ev-service/v1/lectures`, `/lectures/{id}/evaluation-summary`, `/lectures/{id}/evaluations`(첫 페이지·다음 페이지), `/semester-lectures/{id}/evaluations` POST, likes, report, `/users/me/lectures/latest`, `/tags/main`, `/tags/search` | `user` ↔ `user_id`, 커서 왕복, 빈 `cursor=`, 에러 코드(24001/24002 뒤바뀜), 이메일 미인증(null 포함) 0x3011 |
| P1 | 강의 상세의 강의평 요약 | `GET /v1/ev/lectures/{lectureId}/summary` | `evLectureId` 타입(Android는 String으로 받음), 없는 강의 에러 |
| P2 | 강의 일기장 | `/v1/diary/target`, `/questionnaire`, `POST /v1/diary`, `/my`, `/dailyClassTypes`, `DELETE` | 학기 그룹 정렬, 날짜 형식(Android ≤3.12.4 분기 포함), 40400↔40401 |
| P2 | 테마 | `GET /v1/themes`, 추가·수정·복사·삭제, 테마 마켓(best/friends/search/download/publish) | 목록 정렬, 기본 테마 의미, 게시본 id, 다운로드 에러 코드 |
| P2 | 계정 관리 | 이메일 인증, 비밀번호 변경·추가, 소셜 연동·해제, 탈퇴, 비밀번호 재설정 3단계 | 토큰 교체와 이전 토큰 무효화, 에러 코드, 빈도 제한 |
| P2 | 리마인더 | `/v1/tables/{id}/lecture/reminders`, `/reminder` GET·PUT | 정렬, 시간 없는 강의 40024 |
| P2 | 정적 페이지 | `/member`, `/privacy_policy`, `/terms_of_service` | Android 웹뷰가 301을 따라가는지 |
| P2 | 수강편람·건물·학기 상태 | `/v1/course_books*`, `/v1/buildings`, `/v1/semesters/status` | 필드, `campus` enum 값(Android는 3개 외의 값에서 실패) |

기존 커버리지: `api/src/test/kotlin/.../v1compat/V1CompatContractTest.kt`에 15개 테스트가 있다(레거시 apikey, 레거시 에러 형식, x-language, iOS 요청 필드명, 알림 `_id` 문자열, 일기장 id 문자열, 태그 404, Android ≤3.12.4 날짜). v1 서버와 직접 비교하는 테스트는 없다.

## 마이그레이션 관련 관찰

parity 결과에 영향을 주는 이관 규칙이다.

- **사용자·시간표·강의 id**: Mongo ObjectId가 v2 Long PK로 바뀐다. 앱이 로컬에 캐시한 id(마지막으로 본 시간표 id, 위젯, 딥링크의 강의·시간표 id)는 전환 뒤 맞지 않는다. 숫자가 아닌 id는 `GET /v1/tables/{id}`에서만 404이고 나머지는 400 40001이다. 앱이 이 400을 처리하는지 **확인 필요**.
- **EV id**: EV lecture id는 v2 course id로 보존된다(`migration/.../step/CourseStep.kt:91`). 단 trim 후 키가 겹치는 두 lecture는 첫 id로 합쳐지고 두 번째 id는 사라진다(`CourseStep.kt:85-89`). evaluation id와 검색 태그 id도 보존된다. EV semester_lecture id는 보존되지 않고 v2 lecture id로 바뀐다(`EvLectureStep.kt:175`).
- **레거시 토큰**: 이관은 활성 사용자이면서 자격증명이 하나 이상 있는 사용자의 `credentialHash`를 SHA-256 해시로 `legacy_access_token`에 넣는다(`migration/.../step/UserStep.kt:198-203`, `FinalSteps.kt:76-96`). 여러 사용자가 같은 `credentialHash`를 가지면 넣지 않고 건너뛰며 `LEGACY_TOKEN_AMBIGUOUS`로 센다. 이 사용자들은 전환 직후 강제 로그아웃된다. 건수 **확인 필요**.
- **API key**: v1 앱 API key는 비밀키로 서명된 JWT다. v2 `snutt.auth.legacy-secret-key`가 v1 `snutt.secret-key`와 같아야 한다(값은 운영 설정이라 레포에서 확인 불가).
- **isEmailVerified**: v1 문서에 필드가 없으면 이관이 false로 넣는다(`UserStep.kt:67`). v1에서 null이던 사용자는 강의평에 접근할 수 있었지만 v2에서는 막힌다(요약 4). 건수 **확인 필요**.
- **푸시 설정**: v1에서 저장 이력이 없는 사용자는 v2에서도 행이 없고, v1compat은 빈 목록을 준다(요약 3). 이관 단계에서 기본 행을 채울지, 조회에서 기본값을 채울지 결정해야 한다.
- **친구 초대 링크**: v1 Redis 토큰은 이관하지 않는다. 전환 전에 공유된 링크는 모두 40409다.
- **EV 커서**: 형식이 바뀐다. 전환 순간 강의평 웹에서 무한 스크롤 중이던 사용자는 20001을 받는다.
- **날짜**: v1 `LocalDateTime`은 JVM 시간대로 읽혔으므로, v1 JVM이 UTC가 아니었다면 이관 값과 v1 응답 값이 시간대만큼 다르다. 이관이 Mongo `Date`(UTC 기준 epoch)를 그대로 옮긴다면 v2 값이 맞고, v1 응답이 틀렸던 것이 된다. 운영 v1 JVM 시간대 **확인 필요**.
- **강의 스냅샷**: v1 시간표 강의는 스냅샷이고 v2는 수강편람 조인이다. 이관 직후에는 같지만 수강편람이 동기화되면 갈라진다. parity 비교는 이관 직후 동기화 전에 해야 한다.

### 클라이언트가 엄격하게 요구하는 필드 (응답에서 빠지면 앱이 실패)

- Android(Moshi codegen, 기본값 없는 non-null): `TableDto{_id, year, semester, title, updated_at, theme:Int}`, `SimpleTableDto{_id, year, semester, title, updated_at}`, `LectureDto{_id, course_title}`, `NotificationDto{message, created_at, type:Int}`, `FriendDto{id, userId, nickname, createdAt}`, `LectureBuildingDto{buildingNumber, locationInDMS, locationInDecimal, campus}`, 소셜 연동 상태 Boolean 5개, `GetTagListResults` 목록 8개, `SemesterStatusDto.next`, 그리고 sign-up·소셜 로그인·logout·id/find·FCM 등록·탈퇴·일기장 제출 응답의 `message`. 에러 바디는 `errcode`가 non-null Int여야 하고, `ext`가 있으면 `Map<String,String>`이어야 한다.
- iOS(openapi-prod.json의 required): `TimetableBriefDto{_id, isPrimary, semester, title, total_credit, updated_at, year}`, `TimetableLegacyDto{isPrimary, lecture_list, semester(1-4), theme(0-5), title, updated_at, user_id, year}`, `LectureDto{class_time_json, course_number, course_title, credit, lecture_number, registrationCount, semester, wasFull, year}`, `TimetableThemeDto{isCustom, isDefault, name, status, theme, userId}`, `PopupResponse{id, imageUri, image_url, key}`, `UserDto{id, isAdmin, nickname{nickname, tag}, notificationCheckedAt, regDate}`, `FriendResponse{createdAt, id, nickname{nickname, tag}, userId}`, `NotificationResponse{created_at, message, title, type(0-7)}`, `DiarySubmissionSummaryDto{comment, courseTitle, date, id, lectureId, shortQuestionReplies}` 등. 생성된 enum은 닫혀 있어서 모르는 값이 오면 디코딩이 실패한다. 4xx 바디는 강제 언래핑하므로 빈 바디가 오면 크래시한다(`ErrorDecodingMiddleware.swift:26`).
- 위험 후보:
  - `FriendResponse.nickname.tag`: iOS required인데 v1compat은 null이면 생략한다(`V:snutt/V1CompatDomainControllers.kt:85`). 이관된 닉네임에 태그가 없는 경우가 있는지 **확인 필요**.
  - `NotificationResponse.title`: iOS required인데 v1에서도 null이면 생략됐다. 지역화 뒤 빈 값이 되는 경우가 있는지 확인이 필요하다.
