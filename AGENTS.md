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

의존 방향: `api` → `core`, `v1compat` / `v1compat` → `core` / `batch` → `core` / `migration` → `core`.

### 패키지 구조

- `core/.../core/domain/<도메인>/{model,repository,service,dto}`: 엔티티, Spring Data repository(복잡한 쿼리는 kotlin-jdsl `*RepositoryImpl`), 트랜잭션을 가진 service.
- `core/.../core/common`: 에러(`ErrorType`, `SnuttException`), 외부 연동(`PushClient`, `MailClient`, `UploadUriIssuer`), 페이지네이션, 유틸.
- `api/.../api/v2/<도메인>`: controller와 요청/응답 DTO, 매핑 함수. 비즈니스 로직은 `core` service에 둔다.
- `api/.../api/auth`: `@Public`, `@AdminOnly`, `@CurrentUserId`, 인증 인터셉터.

## 개발 환경

`flake.nix`의 devShell이 JDK 25, MySQL 8.4, Valkey, mongodb-tools를 제공한다. `.envrc`(`use flake`)로 direnv를 쓰거나 다음으로 들어간다.

```sh
nix develop
```

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

CI(`.github/workflows/ci.yml`)는 `develop`, `prod` 대상 push/PR에서 `ktlintCheck`와 `test`를 실행한다. `develop`, `prod` push에서는 `api`, `batch` 이미지를 빌드해 OCIR에 올리고 GitOps 저장소의 이미지 태그를 갱신한다.

## 테스트

- 테스트는 `test` profile로 실행된다(`build.gradle.kts`의 `spring.profiles.active=test`).
- 통합 테스트는 Testcontainers로 MySQL과 Valkey 컨테이너를 띄운다. 기반 클래스는 `api/src/test/.../AbstractMysqlIntegrationTest.kt`, `batch/src/test/.../AbstractBatchIntegrationTest.kt`이다.
- `test` profile에서는 `FcmPushClient`, `MailClient`, `OciUploadUriIssuer`가 비활성화된다(`@Profile("!test")`). 대신 `core/src/testFixtures`의 `RecordingPushClient`, `RecordingMailClient`, `RecordingUploadUriIssuer`를 쓴다.
- 테스트용 JWT 키, platform key는 `api/src/test/resources/application-test.yml`, `batch/src/test/resources/application-test.yml`에 있다.

## 로컬 실행

기본 profile은 `local`이다(`core/src/main/resources/application-core.yml`). `jdbc:mysql://localhost:3306/snutt`(root, 비밀번호 없음)와 기본 포트의 Redis를 사용한다.

기본값이 없어 실행 시 지정해야 하는 설정:

- `snutt.auth.jwt.private-key`, `snutt.auth.jwt.public-key`
- `snutt.auth.platform-keys` (형식: `ios:<key>,android:<key>,web:<key>`)
- `snutt.fcm.service-account`, `snutt.fcm.ios-bundle-id`

### batch

`job.name`으로 잡을 고른다. `--year`, `--semester`는 함께 지정하거나 함께 생략한다.

| `job.name` | 클래스 |
|---|---|
| `sugangSnuSync` | `batch/.../sugangsnu/SugangSnuSyncJob.kt` |
| `vacancyNotification` | `batch/.../vacancy/VacancyNotificationJob.kt` |
| `primaryTimetableAutoSet` | `batch/.../timetables/AutoPrimaryJob.kt` |

```sh
java -jar batch/build/libs/snutt-batch.jar --job.name=sugangSnuSync --year=2026 --semester=1
```

### migration

`MigrationRunner.ORDER` 순서로 전 단계를 실행한다. 대상 테이블이 비어 있지 않으면 실패하고, `--truncate`를 주면 대상 테이블을 비운 뒤 실행한다. 접속 정보는 `migration.mongo.uri`, `migration.mongo.database`, `migration.old-ev.{url,username,password}`, `migration.target.{url,username,password}`로 지정한다.

## 규칙

### 코드

- 포맷은 ktlint를 따른다. 변경 후 `./gradlew ktlintFormat`을 실행한다.
- 간단한 구조를 선호한다. 필요하지 않은 추상화, 계층, 간접 호출을 만들지 않는다.
- 주석은 정말 필요한 경우에만 쓴다.
- 요청/응답 DTO 변환은 가능하면 controller에서 한다.
- 시간은 UTC `Instant`로 다룬다. Jackson(`spring.jackson.time-zone`)과 Hibernate(`hibernate.jdbc.time_zone`) 모두 UTC로 설정되어 있다.
- 도메인 오류는 `SnuttException(ErrorType.X)`로 던진다. 새 오류는 `ErrorType`에 추가한다.
  - v2 응답: `api/.../error/SnuttExceptionHandler.kt`가 `ProblemDetail`(`application/problem+json`)로 변환한다. `type`은 `/problems/{ErrorType 이름 kebab-case}`이다.
  - v1 응답: `v1compat/.../error/V1CompatExceptionHandler.kt`가 `errcode`, `title`, `message`, `displayMessage` 형식으로 변환한다. `errcode`는 `ErrorType.v1ErrorCode`이고 일부는 `V1_ERROR_CODE_MAP`으로 덮어쓴다. status는 `v1ErrorCode / 100`이다.
- 인증이 필요 없는 v2 endpoint는 `@Public`, 관리자 전용은 `@AdminOnly`를 붙인다. 현재 사용자 ID는 `@CurrentUserId userId: Long`으로 받는다.
- v1compat 응답 형식과 status는 기존 v1 서버와 같게 유지한다. v1 계약 테스트는 `api/src/test/.../v1compat/V1CompatContractTest.kt`에 있다.

### 스키마

- Flyway 마이그레이션은 `core/src/main/resources/db/migration`에 있다. JPA는 `ddl-auto: validate`이므로 엔티티 변경 시 스키마도 함께 바꾼다.
- 스키마 변경은 기존 마이그레이션 파일을 수정하지 않고, 버전을 올린 새 파일(`V2__<설명>.sql`, `V3__<설명>.sql`, ...)로 추가한다.

### Git

- 기본 브랜치는 `develop`, 배포 브랜치는 `prod`이다. PR은 `develop`으로 올린다.
- 커밋과 PR 제목 형식: `<type>: <한국어 요약>`. type은 `feat`, `fix`, `perf`, `refactor`, `chore`, `docs`, `test`를 쓴다.
