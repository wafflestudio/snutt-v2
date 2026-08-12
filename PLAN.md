# snutt v2: snutt + snutt-ev 통합 계획

## Context

snutt(시간표, Kotlin/Spring Boot 4 WebFlux + MongoDB)와 snutt-ev(강의평, Kotlin/Spring Boot 4 MVC + MySQL/JPA)의 분리가 불필요한 동기화 기계를 유발하고 있다:

- **SYNC_JOB / NEXT_SEMESTER_SYNC_JOB**: Mongo `lectures` → ev MySQL `lecture`/`semester_lecture` 복제 (`course_number`+`instructor` 매칭 기반)
- **RATING_SYNC_JOB + ev api의 MongoService**: 평점을 Mongo `lectures.evInfo{evId,avgRating,count}`에 실시간+배치 역방향 writeback
- **SnuttLectureIdMap**: 두 저장소의 강의 id 매핑 테이블
- **`/v1/ev-service/**` HTTP 프록시**: `Snutt-User-Id` 헤더 신뢰 계약 + 응답 JSON 재귀 순회로 `user_id`→`user` 객체 치환 (snutt `EvService.kt`)

`.`(현재 빈 git repo)에 두 서비스를 단일 코드베이스 + 단일 MySQL로 통합해 이 동기화 전부를 FK 조인과 트랜잭션 내 집계로 대체하고 스키마를 재구성한다.

## 확정된 결정사항

| 항목 | 결정 |
|---|---|
| 스택 | Kotlin 2.3 / Java 25 / Spring Boot 4 MVC + virtual threads / JPA(Hibernate)+QueryDSL / Flyway. snutt-ev의 빌드가 템플릿 (`../snutt-ev/build.gradle.kts`) |
| 내부 라이브러리 | waffle starters(oci-vault, truffle) 전부 미사용. 시크릿은 환경변수 주입, 에러 보고는 일반 로깅(logback JSON)까지만 |
| DB | MySQL 단일. Mongo와 구 ev MySQL 모두 이관 |
| 기능 범위 | 두 서비스 전 기능 이관 |
| 마이그레이션 | 일회성 big-bang (점검 시간 확보, truncate+load) |
| v1 호환 | proxy 방식 어댑터: 기존 경로 유지, v2 서비스로 변환, Deprecation 헤더 |
| 인증 | v2는 정식 토큰 체계(단기 JWT access + 회전 refresh, 기기별 세션)로 재구현. 기존 `credentialHash`는 legacy 토큰으로 v1 경로에서만 수용, 재로그인 없음 |
| 북마크 | 스냅샷 복사 폐기, lecture FK 참조로 전환 (배치의 북마크 전파 로직 삭제) |
| 네이밍 | 테이블·컬럼명은 의미가 복수가 아닌 한 단수. 강의 스키마는 lecture가 메인, course는 평가 도메인 전용 (별도 course_offering 없음) |
| 시간표 강의 | timetable_lecture는 lecture 참조, 사용자 수정분은 별도 `timetable_lecture_customization` 테이블 |
| v2 네이밍 | **camelCase** (v1 호환 layer만 snake_case) |
| v2 순수성 | v2 엔드포인트에는 legacy 호환 요소(이중 매핑, snake_case, `_id`, Legacy 필드) 금지. 호환은 전부 v1compat 어댑터에서 조립 |

## 1. 모듈 · 패키지 구조

```
snutt-v2/  (rootProject "snutt", base package com.wafflestudio.snutt)
  core/       엔티티, 리포지토리, 서비스, Flyway, 외부 클라이언트 (FCM, OCI Email/Storage, GitHub, DynamicLink)
  api/        웹 계층: v2 컨트롤러, v1 호환 컨트롤러, 인터셉터
  batch/      Spring Batch 잡, k8s CronJob에서 JOB_NAME으로 선택 실행
  migration/  일회성 로더 (Mongo + 구 MySQL 읽기 → 신 MySQL 쓰기). cutover 후 삭제
```

- core: `domain/<도메인>/{model,repository,service,dto}` — user, auth, device, notification, pushpreference, lecture, evaluation, tag, timetable, reminder, bookmark, theme, friend, vacancy, diary, coursebook, semester, registrationperiod, building, popup, clientconfig, feedback, admin
- api: `v2/<도메인>/` 컨트롤러(`/v2/...`), `v1compat/snutt/`(레거시 snutt 경로 + Legacy DTO), `v1compat/ev/`(ev-service 경로 + ev 에러 봉투)
- v1compat은 core 서비스에만 의존 (리포지토리 직접 접근 금지). ArchUnit 규칙으로 강제, 한 커밋에 삭제 가능하게 격리
- snutt의 flat 소스 디렉토리 관례는 버리고 표준 패키지 디렉토리 사용

## 2. MySQL 스키마

### 네이밍
테이블·컬럼명은 의미가 복수가 아닌 한 **단수** (`user`, `lecture`, `timetable_lecture`, `evaluation`, ...).

### ID 전략 (단일 규칙, legacy id 보존 없음)
- 모든 테이블: `id BIGINT AUTO_INCREMENT PK`. 구 시스템의 id 값은 어디에도 보존하지 않고 전부 새로 채번
- 클라이언트가 24-hex `_id`를 저장·파싱하는 Mongo 유래 리소스(user, timetable, lecture, theme, notification 등)만 `external_id CHAR(24) NOT NULL UNIQUE` 추가 — 이관 행은 원본 ObjectId hex, 신규 행은 `@PrePersist`에서 생성. v1 응답의 `_id`와 v2 공개 id로 사용
- ev 유래 리소스(course, evaluation, tag 등)는 BIGINT id만. v1 ev 응답의 id는 숫자 타입 유지, **값은 재채번으로 변경** — ev id는 클라이언트에 영속 저장되지 않으므로(매 세션 조회) 수용

### 강의 모델: lecture 중심 (동기화 기계의 대체물)

`lecture`가 메인 엔티티다. 검색, 시간표, 빈자리 알림, 북마크가 전부 이 테이블을 가리킨다. `course`는 강의평이 분반·학기를 넘어 같은 강의를 묶기 위한 평가 도메인 전용 앵커. 별도의 course_offering 테이블은 두지 않는다 — 학기 정보는 lecture와 evaluation이 각자 `(year, semester)` 컬럼으로 가진다.

```sql
lecture          -- 메인. 구 Mongo lectures: 분반 단위, 학기별 행
  id, external_id(구 Mongo _id), course_id FK NULL,  -- 평가 도메인 연결 (SnuttLectureIdMap의 대체)
  year, semester, course_number, lecture_number, course_title, instructor,
  department, academic_year, category, category_pre2025, classification, credit,
  quota, freshman_quota, remark, registration_count, was_full,
  class_place_and_time JSON,       -- 표시용 사본
  KEY(year, semester), UNIQUE(year, semester, course_number, lecture_number)

lecture_class_time   -- 시간 필터 검색 전용 정규화 사본
  lecture_id FK CASCADE, day, place, start_minute, end_minute,
  KEY(day, start_minute, end_minute)

course           -- 평가 도메인 전용. course_number+instructor 단위, 학기 불변. 강의평 집계 대상
  id, course_number, instructor, title, department, credit,
  academic_year, category, classification,
  eval_count, avg_rating,          -- 구 evInfo의 대체: 강의평 트랜잭션에서 갱신
  UNIQUE(course_number, instructor)
```

- 강의평은 `evaluation(course_id FK, year, semester, ...)`로 course × 학기에 직접 달림. 구 semester_lecture가 갖던 학기별 메타(credit/category 등)는 같은 학기의 lecture 행에서 조회, extra_info(스누티티 강의 JSON 사본)는 폐기
- v1 ev API의 semester_lecture id는 v1compat이 해당 (course, year, semester)의 대표 lecture id로 발급·해석 (서버가 발급한 값을 클라이언트가 되돌려주는 opaque 값이므로 성립)
- 평점: `EvaluationService`가 강의평 생성/수정/삭제/숨김 트랜잭션 안에서 `course.avg_rating/eval_count` 갱신. 검색 평점순 정렬은 `lecture ⋈ course` 조인
- `lecture.course_id`는 수강스누 sync가 같은 트랜잭션에서 course upsert 후 설정. 이관 시에는 구 `snutt_lecture_id_map`(semester_lecture 경유 → 구 ev lecture)으로 해석
- 코드 배치: course 엔티티는 `domain/evaluation/` 소속. lecture 도메인은 이를 모름 (검색의 평점 정렬만 evaluation 서비스가 제공하는 조인 뷰를 사용)

### timetable_lecture: 참조 + customization 분리

현재 Mongo는 시간표에 강의 전체 스냅샷을 복사하고 사용자가 그 사본을 직접 수정한다(색상, 시간/장소, 제목 등). v2는 참조와 사용자 수정을 분리:

```sql
timetable_lecture    -- 시간표 항목: 참조 + 표시 순서만
  id, external_id, timetable_id FK CASCADE, lecture_id FK NULL,  -- NULL = 완전 custom 강의
  color JSON, color_index,
  KEY(timetable_id), KEY(lecture_id)

timetable_lecture_customization  -- 사용자 수정분 override. 행 없음 = 원본 그대로
  id, timetable_lecture_id FK UNIQUE CASCADE,
  course_title, instructor, credit, remark,        -- NULL 컬럼 = override 없음
  class_place_and_time JSON NULL
```

- 표시 시 `lecture` 최신 데이터 위에 customization의 non-NULL 필드를 덮어씀. custom 강의(lecture_id NULL)는 customization 행이 전체 데이터를 가짐
- 배치의 timetable_lecture 스냅샷 전파 스텝이 통째로 사라짐 (강의 변경은 조인으로 즉시 반영, 변경 알림은 sugang-sync diff에서 기존대로 발송, 사용자 수정분은 override로 보존)
- 이관: Mongo 스냅샷을 현재 lecture 행과 필드 diff하여 다른 필드만 customization으로 기록. lectureId 미해석 스냅샷은 custom 강의로 전환

### 임베디드 문서 처리

| 원본 | 결정 |
|---|---|
| `TimetableLecture` 스냅샷 | **참조 + override 분리**: `timetable_lecture`(lecture FK) + `timetable_lecture_customization` (§timetable_lecture 절 참조). 스냅샷 복사 폐기 |
| `ClassPlaceAndTime` | 표시용은 JSON, `lecture`만 검색용 side table 추가 |
| `Credential` | `user`에 **컬럼 평탄화** (local_id/fb_id/apple_sub/google_sub/kakao_sub 각각 인덱스 필요한 로그인 핫패스) |
| `ColorSet` | JSON (`theme.color_list`, `timetable_lecture.color`) |
| `pushPreferences` | **정규화** `push_preference(user_id, type, is_enabled, UNIQUE(user_id,type))` — 발송 대상 필터가 쿼리 |
| reminder `schedules` | JSON(`schedule_list`) + 발화 시각 비정규화 컬럼 `(next_day, next_minute, recent_notified_at)` 인덱스 |
| `BookmarkLecture` | **FK 참조로 전환**: `bookmark_lecture(bookmark_id FK, lecture_id FK, UNIQUE)`. 배치의 북마크 전파 경로 전체 삭제 |
| registrationPeriods, taglists.tags, diary answers | JSON (통째 읽기, 요소 필터 없음) |

### 나머지 테이블 (✦ = external_id 보유, 전부 단수명)

user✦(credential_hash UNIQUE 보존, credential 평탄화), user_session✦(신규, 인증 재설계 참조), user_device✦, notification✦(user_id NULL=broadcast, KEY(user_id, created_at DESC)), timetable✦(KEY(user_id, year, semester)), timetable_lecture✦, timetable_lecture_reminder✦, theme✦(마켓 정보 평탄화, KEY(status, downloads DESC)), bookmark✦(UNIQUE(user_id,year,semester))+bookmark_lecture, coursebook✦, tag_list, tag_group/tag, vacancy_notification✦(UNIQUE(user_id,lecture_id)), friend✦(UNIQUE(from,to)), popup✦, client_config✦, lecture_building✦, semester_registration_period✦, diary_question✦/diary_daily_class_type✦/diary_submission✦, evaluation(**user_id를 BIGINT FK로 변환**, course_id FK + year + semester, KEY(course_id, year, semester, id DESC), KEY(user_id, id DESC)), evaluation_like/evaluation_report.

완전 삭제: `snutt_lecture_id_map`, Mongo `lectures.evInfo`.

주의: Mongo의 partial unique 이메일 인덱스(active+verified, case-insensitive)는 MySQL에 등가물이 없음 → 서비스 계층에서 강제 + 이관 dry-run에서 중복 사전 검출.

## 3. 애플리케이션 아키텍처

### 인증 재설계 (v2 정식 구현)

현재 snutt의 토큰은 credential 블롭의 HMAC(`credentialHash`)을 무만료 불투명 토큰으로 쓰는 구조로, 만료·회전·기기별 폐기가 불가능하고 코드에도 "보안 취약"으로 명시되어 있다. v2는 다음으로 대체:

- **Access token**: 단기(수 시간) JWT, RS256/ES256 서명(키는 환경변수 주입, kid 기반 회전 가능), claims `sub`(user external_id), `sid`(세션 id), `exp/iat/iss`. 검증은 서명+만료만으로 DB 조회 없이 수행
- **Refresh token**: 불투명 랜덤 값, `user_session` 테이블에 SHA-256 해시로 저장, 사용 시 회전(rotate-on-use), 재사용 감지 시 세션 폐기. 기기별 1세션(`user_device` 연계), 비밀번호 변경·로그아웃·계정 삭제 시 해당/전체 세션 폐기
- **테이블 추가**: `user_session(id, external_id✦, user_id FK, refresh_token_hash UNIQUE, user_device_id FK NULL, expires_at, revoked_at, last_used_at)`
- **v2 엔드포인트**: `POST /v2/auth/login`(및 소셜) → `{accessToken, refreshToken}` 쌍 발급, `POST /v2/auth/refresh`, `POST /v2/auth/logout`(세션 폐기 + FCM 해제), `POST /v2/auth/token/exchange` — 유효한 legacy credentialHash를 제출하면 신규 토큰 쌍 발급 (구 클라이언트의 무중단 업그레이드 경로)
- **Legacy 수용**: `users.credential_hash`는 보존하되 **v1compat 경로의 인증에만 사용**. v1 로그인 엔드포인트는 지금처럼 credentialHash를 토큰으로 반환하므로 기존 클라이언트는 그대로 동작. v2 경로는 JWT만 수용. credentialHash 생성/검증 로직(`AuthService.kt`)은 v1compat 전용으로 격리, sunset과 함께 삭제
- **API key**: `x-access-apikey`의 정적 HS256 JWT(약한 키, 하드코딩 4종 클라이언트) 시맨틱은 v1compat에서만 유지. v2는 플랫폼 식별을 `x-client-platform` 헤더 + 환경변수 주입 키 목록 검증으로 단순화 (요청 위조 방지 수단이 아니라 클라이언트 식별 수단임을 인정하는 설계)

### 요청 파이프라인 (WebFilter → MVC HandlerInterceptor)
snutt의 필터는 핸들러 어노테이션 해석용 `HandlerAnnotationResolver`를 별도로 두는데, MVC 인터셉터는 `HandlerMethod`를 직접 받으므로 자연스럽게 이식:

1. `ApiKeyInterceptor` — v1compat: `x-access-apikey` 기존 시맨틱 / v2: 플랫폼 식별 헤더
2. `ClientInfoInterceptor` — `x-os-type` 등 → `ClientInfo` attribute
3. `UserAuthInterceptor` — v2 경로: `Authorization: Bearer <JWT>` 서명 검증 / v1compat 경로: `x-access-token` → `findByCredentialHash` (virtual thread 위 blocking JPA, 해시값 그대로 비교)
4. `AdminInterceptor` — `is_admin` 게이트

`@CurrentUser` ArgumentResolver로 컨트롤러 주입. 에러는 `@RestControllerAdvice`: 기본은 snutt 봉투 `{errcode,title,message,displayMessage}` (ErrorType 카탈로그 통합), `v1compat.ev` 패키지 스코프 advice만 `{error:{code,message}}` (구 프록시가 ev 에러를 ev 포맷 그대로 노출했으므로 동작 일치).

### v2 API 관례
- 경로 `/v2/<복수형 리소스>`, 응답 **camelCase** (v1compat만 snake_case — Legacy DTO에 `@JsonNaming`/`@JsonProperty` 명시)
- 공개 id는 external_id hex를 `id` 필드로, ev 유래 리소스는 숫자 id
- 페이지네이션: keyset + base64(JSON) 커서 공통 `CursorPage<T>`. 구 AES 커서 코드는 이식하지 않음 — 커서는 클라이언트에 opaque 문자열이므로 v1compat 엔드포인트도 새 커서를 발급하면 그대로 동작 (cutover 시점의 in-flight 커서만 무효, 수용)
- 이메일 인증 필수였던 ev 엔드포인트는 `@EmailVerifiedRequired` 어노테이션으로 이식

### 소멸하는 경계와 대체물

| 소멸 | 대체 |
|---|---|
| `/v1/ev-service/**` 프록시, `SnuttEvWebClient`, `Snutt-User-Id` | in-process 컨트롤러 + 일반 인증 인터셉터 |
| `user_id`→`user` JSON 재작성 | DTO에 `user: EvUserDto?` 필드, users FK 조인 |
| evInfo writeback (RATING_SYNC_JOB + MongoService) | `course.avg_rating/eval_count` 트랜잭션 내 갱신 |
| SYNC_JOB (Mongo→MySQL 강의 복제) | 수강스누 sync가 3계층을 한 트랜잭션에 upsert |
| SnuttLectureIdMap | `lecture.course_id` FK |
| `getMyLatestLectures` 왕복 (snutt가 수강 강의를 모아 ev에 POST) | timetable ⋈ timetable_lecture ⋈ lecture ⋈ course 단일 쿼리 |
| 배치의 timetable_lecture 스냅샷 전파 | customization override 위에 lecture 조인으로 즉시 반영 |
| ev의 embedded H2 배치 메타데이터 / snutt의 Resourceless | 단일 MySQL의 정식 Spring Batch 메타 테이블 |

## 4. 배치 · 스케줄링

부모들이 이미 운영하는 hybrid 유지:

- **batch 모듈 (k8s CronJob, JOB_NAME 선택)**: `sugangSnuMigrationJob`(xlsx diff → course/lecture upsert → tag_list → 변경 알림 FCM. 스냅샷·북마크 전파 스텝 삭제), `vacancyNotificationJob`(등록기간 폴링 → registration_count/was_full → vacancy_notification ⋈ push_preference 대상 FCM), `primaryTimetableAutoSetJob`
- **api in-process `@Scheduled` + Redis 락**: 강의 리마인더(매분, next_day/next_minute 인덱스 keyset), diary 알림(월수금 19시)
- ev의 SNUEV_MIGRATION_JOB은 이관하지 않음 (일회성, 완료됨)
- v2에서 수정할 기존 버그: `TimetableLectureReminderController`의 시간표 소유권 검증 누락

## 5. 마이그레이션 (big-bang)

### 도구
`migration` 모듈: CommandLineRunner 단계별 CLI (일회성 truncate+load이므로 Spring Batch 불필요). 데이터소스 3개: Mongo(읽기), 구 ev MySQL(읽기), 신 MySQL(쓰기). ObjectId→BIGINT 매핑은 `SELECT id, external_id` 인메모리 HashMap (timetable_lecture만 스트리밍).

### 순서
1. users (닉네임/이메일 충돌 사전 정규화)
2. course, tag_group, tag (구 ev에서 읽되 id는 재채번, 구 lecture/semester_lecture id → 신 course id 매핑을 인메모리 보관)
3. coursebook, lecture_building, semester_registration_period, tag_list
4. lecture + lecture_class_time (course_id는 구 snutt_lecture_id_map → semester_lecture → ev lecture 경로로 해석, 미매칭 NULL)
5. timetable → timetable_lecture(스트리밍, 스냅샷을 lecture 현재값과 diff하여 차이만 customization으로) → timetable_lecture_reminder(next_* 재계산)
6. bookmark → bookmark_lecture (스냅샷의 `_id` → lecture 매핑, 소멸 강의는 드랍하고 건수 로깅)
7. theme (themeId/origin 해석) → timetable.theme_id 보정
8. friend, vacancy_notification, notification, user_device, push_preference, popup, client_config, diary
9. evaluation/evaluation_like/evaluation_report (id 재채번, semester_lecture_id → course id + year/semester 매핑, user_id 문자열 → FK. 고아 user_id는 dry-run 건수 보고 tombstone/드랍 결정)
10. `course.eval_count/avg_rating` 재계산, ANALYZE TABLE, FK 검증

### 검증
- 테이블별 행 수 대조 (기대 델타 문서화), `SUM(CRC32(external_id))` 체크섬, 테이블별 무작위 1000행 필드 diff
- `course.avg_rating` vs 구 Mongo `lectures.evInfo.avgRating` epsilon 비교
- FK 고아/UNIQUE 위반 사전 스캔 (특히 lecture 4-컬럼 UNIQUE, nickname, 이메일 case-insensitive)
- 실제 credential_hash 샘플로 인증 smoke

### cutover runbook
1. T-7d/T-1d: 프로덕션 스냅샷 dry-run 2회, 소요 시간 측정 (1h 미만 목표)
2. T-0: ingress 점검 모드 → 구 스택 scale 0, CronJob/@Scheduled 중지
3. 최종 이관 실행 + 검증 suite 통과
4. v2 dark 배포, 녹화된 v1 트래픽으로 contract smoke
5. ingress/DNS 전환 (snutt 호스트 + ev 호스트 모두), 점검 해제
6. 구 스택 scale 0 유지, Mongo/구 MySQL 2주 read-only 보존. 롤백 = ingress 원복 (그 사이 v2 쓰기 유실은 big-bang 결정에 따른 수용 범위)

## 6. v1 호환 layer

- **이중 매핑**: `@RequestMapping("/v1/tables", "/tables")` 식 스캐폴딩을 snutt 컨트롤러 목록 그대로 이식. ev는 `/v1/ev/lectures/{id}/summary` + `/v1/ev-service/**` 뒤의 실제 라우트를 열거해 매핑 (프록시의 경로 prefix 제거 동작 재현)
- **미노출**: 서버간 전용이던 `/v1/lectures/ids`, `/v1/lectures/snutt-summary`, `/v1/users/me/lectures/latest`는 유일한 호출자가 snutt 자신이었으므로 재노출하지 않음
- **Legacy DTO**: `TimetableLegacyDto` 등 snake_case/`_id` 형태 그대로 `v1compat.snutt.dto`로 이식, v2 모델 → Legacy 매퍼. `x-app-version` 게이팅 유지
- **커서**: v1compat 엔드포인트도 v2의 base64 커서를 발급 (클라이언트에 opaque). 구 AES 커서·하드코딩 키는 이식하지 않음
- **헤더**: `Deprecation: true`, `Sunset: <협의 일자>`, `Link: rel="successor-version"` 인터셉터
- **검증**: 녹화된 프로덕션 v1 요청/응답 replay contract test, 정규화 JSON diff = 설명되지 않는 델타 0

## 7. 구현 로드맵

DoD 공통: ktlint + 단위 + Testcontainers-MySQL 통합 테스트 green (H2 MySQL-mode 폐기 — collation/JSON/REGEXP를 정직하게 검증).

| 단계 | 내용 | DoD |
|---|---|---|
| M0 스캐폴딩 | 4-모듈 빌드(snutt-ev 템플릿에서 waffle starters·GitHub Packages 제거, 시크릿은 환경변수, 로깅은 logback JSON), Flyway V1 전체 스키마, BaseEntity/ExternalIdEntity, 에러 카탈로그 통합, Testcontainers 하네스 | 앱 부팅 + 스키마 clean apply |
| M1 인증/사용자 | user + user_session, JWT access/refresh 발급·회전·폐기, token exchange, 인터셉터 체인, `@CurrentUser`, 소셜 로그인(OIDC 검증기 이식), v1compat용 credential_hash 경로, user_device, push_preference, FCM | 이식 테스트 green + 실 credential_hash 샘플이 v1 경로 인증 및 exchange로 신규 토큰 발급 |
| M2 강의/검색 | lecture 중심 모델(+평가용 course), QueryDSL로 `LectureCustomRepository` 시맨틱 재현(한국어 fuzzy REGEXP, 시간 포함/제외, etc-tags), tag_list, coursebook, lecture_building | 동일 쿼리 corpus로 Mongo vs SQL side-by-side diff 일치 |
| M3 시간표 | timetable/timetable_lecture + customization override 병합 로직/theme/bookmark/reminder(소유권 버그 수정), custom 강의, 색상 | snutt 시간표 테스트 이식 green |
| M4 강의평 | evaluation/evaluation_like/evaluation_report/tag, 평점 비정규화 유지, 검색 DTO에 ev summary 조인 | ev 테스트 green + avg_rating 재계산 property test |
| M5 롱테일 | friend(+초대 링크), vacancy_notification, notification, diary, popup, client_config, feedback, 정적 페이지, admin | 도메인별 테스트 green |
| M6 v1compat | Legacy DTO, 이중 매핑, 두 에러 봉투, Deprecation 헤더 | v1 replay contract test 델타 0 (id 값·커서 문자열은 정규화 대상) |
| M7 배치 | sugang-sync 재작성, vacancy, auto-primary, 리마인더/diary 스케줄러 | 동일 xlsx로 구 배치와 diff+알림 대상 일치 |
| M8 마이그레이션 | migration 모듈, 검증 suite, 리허설 | 연속 2회 clean 리허설, 시간 예산 내 |
| M9 cutover | §5 runbook 실행 | 전환 완료, 검증 green |
| M10 해체 | 2주 soak 후 구 스택/Mongo/구 MySQL 폐기, v1compat sunset 일정 수립 | |

## 8. 리스크

- **검색 동등성 (최대)**: Mongo regex vs MySQL REGEXP 차이, 동점 시 정렬 안정성. M2 diff 하네스로 완화. `(year,semester)` 인덱스 앵커로 REGEXP 스캔 범위를 학기당 ~10⁴행으로 제한
- **timetable_lecture 볼륨**: 최대 테이블. dry-run에서 크기 측정, buffer pool 산정
- **시크릿 운영 전환**: 기존 배포는 OCI Vault 주입에 의존 — 환경변수 주입으로 바꾸면서 k8s Secret 구성과 시크릿 값 이전을 cutover 전에 준비 (truffle 에러 보고 제거로 알림 채널 공백이 생기는 점도 운영팀에 공유)
- **GraalVM native**: 두 부모 모두 native 빌드하나, v2는 JVM + virtual threads로 먼저 출시하고 native는 cutover 이후 별도 작업으로 분리 권고
- **강의평 고아 user_id / 미매칭 id_map**: 첫 dry-run에서 정량화 후 결정
- **v1 ev 응답의 id 값 변경**: 재채번으로 evaluation/lecture(ev)/semester_lecture id 값이 바뀜. 클라이언트가 ev id를 영속 저장하지 않는다는 가정을 클라이언트 팀에 확인 (타입은 숫자로 유지되므로 파싱은 안전)
- **snutt 첫 탐색 보고서의 "coupons" 컬렉션은 오류로 확인됨** (snutt core에 coupon 도메인 없음) — 이관 대상 아님

## 참조 파일 (구현 시 원본)

- `../snutt-ev/build.gradle.kts` — 빌드 템플릿 (Boot 4 MVC, virtual threads, JPA 플러그인. waffle starters/GitHub Packages 블록은 제외하고 인용)
- `../snutt/core/src/main/kotlin/lectures/repository/LectureCustomRepository.kt` — 검색 시맨틱의 원천
- `../snutt/core/src/main/kotlin/timetables/data/TimetableLecture.kt` — timetable_lecture 컬럼과 Legacy DTO 형태
- `../snutt-ev/core/src/main/kotlin/com/wafflestudio/snuttev/core/domain/lecture/model/SemesterLecture.kt` — course + evaluation(year, semester)로 재구성할 원본 구조
- `../snutt/core/src/main/kotlin/evaluation/service/EvService.kt` — v1compat.ev가 재현할 프록시 동작 (user_id 재작성, 이메일 인증 게이트, 에러 passthrough)
- `../snutt/core/src/main/kotlin/users/service/AuthService.kt` — credentialHash 생성/검증 (그대로 이식)
- `../snutt/api/src/main/kotlin/filter/` — 인터셉터로 이식할 WebFilter 체인
