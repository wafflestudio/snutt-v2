# AGENTS.md

SNUTT(서울대학교 시간표) 백엔드 서버. Kotlin, Spring Boot 4.1, JDK 25, MySQL, Redis(Valkey), Gradle 멀티 모듈.

## 모듈

| 모듈 | 역할 | 실행 산출물 |
|---|---|---|
| `core` | 도메인 모델, repository, service, 공통 설정, Flyway 스키마 | 라이브러리 |
| `api` | v2 HTTP API(`/v2/...`), 인증 인터셉터, 에러 핸들러, 스케줄러, 정적 페이지 | `snutt-api.jar` |
| `v1compat` | 기존 v1 클라이언트용 호환 API(SNUTT v1, snutt-ev 경로). `api`에 포함되어 함께 뜬다 | 라이브러리 |
| `batch` | 수강편람 동기화, 빈자리 알림, 대표 시간표 자동 지정 | `snutt-batch.jar` |
| `migration` | v1 MongoDB와 기존 강의평 DB에서 v2 MySQL로 일괄 이관 | `snutt-migration.jar` |

의존 방향: `api` → `core`, `v1compat` / `v1compat` → `core` / `batch` → `core` / `migration` → `core`. `core`는 다른 모듈을 참조하지 않는다.

## 계층

```
api controller (v2 DTO)      v1compat controller (v1 DTO)      batch job
          \                          |                           /
           +--------------> core service <----------------------+
                                     |
                         core repository / entity
```

- controller는 요청을 파싱하고 core service를 호출한 뒤 결과를 응답 DTO로 변환한다. 비즈니스 로직은 core service에 둔다.
- core service는 entity 또는 조회용 DTO(`TimetableDisplay`, `TimetableLectureDisplay`, `ThemePublicationDisplay` 등)를 반환한다. API 응답 형태는 core에 두지 않는다.
- 요청/응답 DTO와 변환 함수는 controller 파일에 둔다(`api/.../api/v2/<도메인>/*Controller.kt`).

## core

### 패키지

- `core/.../core/domain/<도메인>/{model,repository,service,dto}`: entity, Spring Data repository, service, 조회용 DTO.
- `core/.../core/common`: 에러(`ErrorType`, `SnuttException`, `UpstreamException`), 외부 연동(`PushClient`, `MailClient`, `UploadUriIssuer`), 페이지네이션(`CursorPage`, `CursorCodec`), 요청 클라이언트 정보(`ClientInfo`, `@CurrentClient`), 유틸.
- `core/.../core/config`: JPA, RestClient, PasswordEncoder 설정.

### Entity

- 모든 entity는 `BaseEntity`(`id`, `createdAt`, `updatedAt`)를 상속한다.
- 다른 entity는 `userId`, `lectureId`, `themeId` 같은 `Long` 필드로 참조한다. JPA 연관관계(`@ManyToOne`)는 `RefreshToken`, `PushPreference`, `UserDevice`의 `user`에만 있다.
- 참조 무결성은 Flyway 스키마의 foreign key(`ON DELETE CASCADE` / `SET NULL` / `RESTRICT`)로 보장한다.
- 구조가 있는 값은 `@JdbcTypeCode(SqlTypes.JSON)` 컬럼으로 저장한다(예: `TimetableLecture.overrides`, `TimetableTheme.colors`).
- `TimetableLecture`는 `lectureId`(직접 만든 강의는 `null`)와 `overrides`(사용자가 바꾼 필드)를 가진다. 화면에 보이는 값은 `TimetableLectureDisplay`가 `Lecture`와 `overrides`를 합쳐 만든다.
- `Lecture.courseId`는 강의평 단위인 `Course`를 가리킨다. `Course`의 `evalCount`, `avg*`는 `CourseAggregateUpdater`가 갱신하는 집계 컬럼이다.

### Repository

- 단순 조회는 Spring Data 메서드 이름 쿼리로 작성한다.
- 복잡한 쿼리는 `*Repository` 인터페이스와 kotlin-jdsl `KotlinJdslJpqlExecutor`를 쓰는 `*RepositoryImpl` 클래스로 작성한다(예: `LectureSearchRepositoryImpl`, `EvaluationRepositoryImpl`).
- 행 잠금이 필요한 조회는 `@Lock(PESSIMISTIC_WRITE)`를 붙인 `findForUpdate*` 메서드로 둔다.

### 트랜잭션과 부수 효과

- `spring.jpa.open-in-view: false`이다. 지연 로딩은 트랜잭션 안에서만 동작한다.
- 쓰기 메서드에 `@Transactional`을 붙인다. 읽기 전용 메서드는 대부분 트랜잭션 없이 repository를 호출한다.
- 동시 수정이 겹치는 경로는 `findForUpdate*`로 먼저 행을 잠근다.
- 푸시 발송, FCM topic 구독처럼 커밋 후 실행해야 하는 외부 호출은 `afterCommit { ... }`(`core/.../common/transaction/AfterCommit.kt`)으로 등록한다.
- 도메인 간 후속 처리는 Spring `ApplicationEvent`로 연결한다. 리스너는 동기 `@EventListener`라서 발행한 트랜잭션 안에서 실행된다.
  - `UserRegisteredEvent` → `DefaultTimetableInitializer`가 기본 시간표를 만든다.
  - `UserCredentialChangedEvent` → v1compat `LegacyTokenService`가 v1 토큰을 지운다.

### Redis

Spring Data Redis repository는 끄고(`spring.data.redis.repositories.enabled: false`) `StringRedisTemplate`만 쓴다.

| 용도 | 클래스 |
|---|---|
| 여러 api 인스턴스에서 `@Scheduled` 작업을 한 번만 실행하는 락 | `SchedulerLock` |
| 최신 수강편람 `updatedAt`을 key에 넣은 조회 캐시 | `CoursebookVersionedCache` |
| 이메일 인증, 비밀번호 재설정 코드와 발송 제한 | `CodeChallengeStore` |

### 에러

- 도메인 오류는 `SnuttException(ErrorType.X)`로 던진다. 새 오류는 `ErrorType`에 추가한다.
- 외부 서비스 실패는 5xx `ErrorType`과 함께 `UpstreamException`으로 던진다.
- v2 응답: `api/.../error/SnuttExceptionHandler.kt`가 `ProblemDetail`(`application/problem+json`)로 변환한다. `type`은 `/problems/{ErrorType 이름 kebab-case}`이다.
- v1 응답: `v1compat/.../error/V1CompatExceptionHandler.kt`가 변환한다(아래 v1compat 참고).

### 페이지네이션

- v2 목록 API는 cursor 방식이다. `CursorCodec`이 cursor 객체를 base64url JSON으로 인코딩하고 `CursorPage`를 만든다. 페이지 크기 상한은 `MAX_PAGE_SIZE`(100)이다.
- offset 조회(`LectureService.searchByOffset`, `NotificationService.getNotificationsByOffset`)는 v1 호환용으로 core에 남아 있다.

### 외부 연동

`PushClient`(FCM), `MailClient`(OCI Email), `UploadUriIssuer`(OCI Object Storage)는 구현체에 `@Profile("!test")`가 붙어 있다. 테스트에서는 `core/src/testFixtures`의 `RecordingPushClient`, `RecordingMailClient`, `RecordingUploadUriIssuer`가 대신 등록된다.

## api

- `/v2/**` 요청은 인터셉터 두 개를 순서대로 거친다(`api/.../config/WebConfig.kt`).
  1. `PlatformKeyInterceptor`: `x-os-type`, `x-client-key` 헤더를 `snutt.auth.platform-keys`와 비교하고 `ClientInfo`를 request attribute에 넣는다.
  2. `UserAuthInterceptor`: `Authorization: Bearer <JWT>`를 검증하고, 사용자의 `tokenVersion`이 토큰과 같은지 확인한다.
- 인증이 필요 없는 endpoint는 `@Public`, 관리자 전용은 `@AdminOnly`, 이메일 인증이 필요한 endpoint는 `@EmailVerifiedRequired`를 붙인다.
- controller 인자: 현재 사용자 ID는 `@CurrentUserId userId: Long`, 클라이언트 정보(언어 포함)는 `@CurrentClient clientInfo: ClientInfo`로 받는다.
- 인스턴스 하나에서만 실행해야 하는 스케줄러(`ReminderScheduler`, `DiaryScheduler`)는 `api/.../scheduler/Schedulers.kt`에 있고 `SchedulerLock`으로 감싼다. 시간 기준은 KST(`Asia/Seoul`)이다.
- `ClientConfigService`는 인스턴스마다 `@Scheduled`로 설정을 다시 읽어 메모리에 둔다.

## v1compat

기존 v1 클라이언트가 v2 데이터 위에서 동작하도록 v1 경로와 응답 형식을 재현한다. 언제든 모듈째 제거할 수 있게 유지한다.

### 요청 처리

- 경로: `/v1/**`, `/admin/**`, `/ev-service/**`, `/ev/**`(`v1compat/.../config/V1CompatConfig.kt`).
- 인터셉터 순서: `V1DeprecationHeaderInterceptor`(Deprecation, Sunset, Link 헤더) → `V1ApiKeyInterceptor`(`x-access-apikey`) → `V1UserAuthInterceptor`(`x-access-token`, `LegacyTokenService`).
- 어노테이션: `@V1Public`, `@V1AdminOnly`, `@V1EmailVerifiedRequired`, `@V1CurrentUser user: User`.
- 응답 형식과 status는 기존 v1 서버와 같게 유지한다. 계약 테스트는 `api/src/test/.../v1compat/V1CompatContractTest.kt`에 있다.
- 에러 응답은 `errcode`, `title`, `message`, `displayMessage` 형식이다. `errcode`와 status는 `V1CompatExceptionHandler.kt`의 `ErrorType.v1ErrorCode`(모든 `ErrorType`을 다루는 exhaustive `when`)와 `V1_ERROR_CODE_MAP`에서 정한다. status는 `v1ErrorCode / 100`이다. 새 `ErrorType`을 추가하면 이 `when`에 v1 코드를 추가해야 컴파일된다.

### core와의 경계

v1compat 모듈을 지웠을 때 core에 쓰이지 않는 코드가 남지 않도록 한다.

- core에는 v1 전용 코드를 두지 않는다. v1 요청 형태(localId 기반 흐름, v1 enum, v1 errcode, v1 응답 필드 계산)는 v1compat에서 처리한다.
- v1compat은 core의 service와 repository를 조합해 구현한다. 예: localId로 `UserRepository`에서 사용자를 찾은 뒤 `PasswordResetService.confirmReset(userId, ...)`를 호출한다.
- 조합으로 구현할 수 없는 v1 전용 쿼리는 v1compat 안에 repository를 둔다(예: `v1compat/.../ev/LegacyCourseRepository.kt`).
- core에 기능을 추가해야 하면 v2에서도 의미가 있는 범용 연산(userId 기반 등)으로 추가한다. 예외는 성능 때문에 core에 둔 offset 조회뿐이다.
- v1 전용 entity(`LegacyAccessToken`, `LegacySearchTag`)는 v1compat에 있다. 테이블은 core Flyway(`V1__init.sql`)에 있으므로, v1compat을 제거할 때 core에 `legacy_access_token`, `legacy_search_tag`를 DROP하는 새 마이그레이션을 추가한다.
- core는 v1compat을 참조하지 않는다. core에서 v1compat으로 알려야 하는 일은 `UserCredentialChangedEvent`처럼 core 이벤트를 발행하고 v1compat이 구독한다.

## batch

- `BatchJob`(`name`, `run(YearSemesterArgs)`) 구현체를 `@Component`로 등록하면 `JobRunner`가 `--job.name`으로 하나를 골라 실행하고 프로세스를 종료한다.
- `--year`, `--semester`는 함께 지정하거나 함께 생략한다. 생략하면 `vacancyNotification`, `primaryTimetableAutoSet`은 최신 수강편람 학기를 쓰고, `sugangSnuSync`는 수강신청 사이트의 최신 학기와 비교해 현재 수강편람을 갱신하거나 다음 학기 수강편람을 만든다.

| `job.name` | 클래스 |
|---|---|
| `sugangSnuSync` | `batch/.../sugangsnu/SugangSnuSyncJob.kt` |
| `vacancyNotification` | `batch/.../vacancy/VacancyNotificationJob.kt` |
| `primaryTimetableAutoSet` | `batch/.../timetables/AutoPrimaryJob.kt` |

```sh
java -jar batch/build/libs/snutt-batch.jar --job.name=sugangSnuSync --year=2026 --semester=1
```

## migration

- 일회성 이관 도구다. JPA를 거치지 않고 `JdbcTemplate`과 `BatchWriter`로 대상 테이블에 직접 INSERT한다.
- 단계는 `MigrationStep`(`name`, `tables`, `run()`) 구현체이고 `MigrationRunner.ORDER` 순서로 실행된다. 단계 사이에 넘길 ID 매핑은 `MigrationContext`에 담는다.
- 원본 데이터가 v2 제약을 위반해 손본 항목은 `MigrationSupport.ResolutionReasons`의 사유로 `MigrationContext.resolved`에 기록하고, 실행이 끝나면 집계를 로그로 남긴다.
- 대상 테이블이 비어 있지 않으면 실패한다. `--truncate`를 주면 대상 테이블을 비운 뒤 실행한다.
- 접속 정보: `migration.mongo.uri`, `migration.mongo.database`, `migration.old-ev.{url,username,password}`, `migration.target.{url,username,password}`.

## 명령

```sh
./gradlew ktlintCheck              # CI lint
./gradlew ktlintFormat             # 자동 포맷
./gradlew test                     # CI test (Docker 필요)
./gradlew :api:test --tests 'com.wafflestudio.snutt.api.AuthIntegrationTest'
./gradlew :api:bootJar             # api/build/libs/snutt-api.jar
./gradlew :batch:bootJar           # batch/build/libs/snutt-batch.jar
./gradlew :migration:bootJar       # migration/build/libs/snutt-migration.jar
```

`flake.nix`의 devShell(`nix develop`)에서 JDK 25, MySQL 8.4, Valkey, mongodb-tools를 쓸 수 있다.

CI(`.github/workflows/ci.yml`)는 `develop`, `prod` 대상 push/PR에서 `ktlintCheck`와 `test`를 실행한다. `develop`, `prod` push에서는 `api`, `batch` 이미지를 빌드해 OCIR에 올리고 GitOps 저장소의 이미지 태그를 갱신한다.

## 테스트

- 테스트는 `test` profile로 실행된다(`build.gradle.kts`의 `spring.profiles.active=test`).
- 통합 테스트는 Testcontainers로 MySQL과 Valkey 컨테이너를 띄운다. 기반 클래스는 `api/src/test/.../AbstractMysqlIntegrationTest.kt`, `batch/src/test/.../AbstractBatchIntegrationTest.kt`이다.
- 테스트용 JWT 키, platform key는 `api/src/test/resources/application-test.yml`, `batch/src/test/resources/application-test.yml`에 있다.

## 규칙

### 코드

- 포맷은 ktlint를 따른다. 변경 후 `./gradlew ktlintFormat`을 실행한다.
- 간단한 구조를 선호한다. 필요하지 않은 추상화, 계층, 간접 호출을 만들지 않는다.
- 주석은 정말 필요한 경우에만 쓴다.
- 시간은 UTC `Instant`로 다룬다. Jackson(`spring.jackson.time-zone`)과 Hibernate(`hibernate.jdbc.time_zone`) 모두 UTC로 설정되어 있다.

### 스키마

- Flyway 마이그레이션은 `core/src/main/resources/db/migration`에 있다. JPA는 `ddl-auto: validate`이므로 entity 변경 시 스키마도 함께 바꾼다.
- 스키마 변경은 기존 마이그레이션 파일을 수정하지 않고, 버전을 올린 새 파일(`V2__<설명>.sql`, `V3__<설명>.sql`, ...)로 추가한다.

### Git

- 기본 브랜치는 `develop`, 배포 브랜치는 `prod`이다. PR은 `develop`으로 올린다.
- 커밋과 PR 제목 형식: `<type>: <한국어 요약>`. type은 `feat`, `fix`, `perf`, `refactor`, `chore`, `docs`, `test`를 쓴다.
