# 테스트 컨벤션

새 테스트를 쓰기 전에 이 문서를 읽고 따른다. 공용 도구는 `core/src/testFixtures/kotlin/com/wafflestudio/snutt/core/test/`에 있다.

## 원칙

- 의미 없는 unit test를 늘리지 않는다. 골든패스 통합테스트를 먼저 쓰고, 거기서 사이드케이스를 넓힌다.
- v2가 직접 만든 데이터가 도메인 규칙을 어긴 상황은 테스트하지 않는다. 마이그레이션된 레거시 데이터와 외부 입력(sugang 엑셀, 클라이언트 요청)은 규칙을 어길 수 있으므로 테스트한다.
- MySQL·Redis는 실제 컨테이너(`IntegrationContainers`)를 쓴다. H2나 임베디드 Redis는 쓰지 않는다.
- 결과가 매번 같아야 한다. 시각은 `MutableClock`으로 고정하고, 난수는 seed를 고정한다. `Thread.sleep`은 쓰지 않는다. 기다려야 하면 조건을 polling한다.
- 실행시간은 assert하지 않는다. 쿼리 수, 실행계획처럼 매번 같은 값을 assert한다.
- `@Disabled`, `test.skip`, TODO, 빈 테스트, assert 없는 테스트는 완료로 치지 않는다.

## 태그와 태스크

| 태그 | 어노테이션 | 실행 | 용도 |
|---|---|---|---|
| 없음 | - | `./gradlew test` (PR CI, 배포 게이트) | 기본 |
| `parity` | `@ParityTest` | `./gradlew parityTest` | v1 서버와 v1compat 응답 비교 ([parity.md](parity.md)) |
| `parity-known-failure` | `@ParityKnownFailure` | `./gradlew parityKnownFailureTest` | parity가 찾은 버그 재현. 고쳐지기 전까지 실패가 정상 |
| `parity-snapshot` | `@ParitySnapshot` | `./gradlew paritySnapshotTest -Dparity.source.…` | 외부 덤프(운영 스냅샷)로 도는 parity |
| `perf` | `@PerfTest` | `./gradlew perfTest` | 쿼리 수·실행계획 같은 결정적 성능 지표 |
| `stress` | `@StressTest` | `./gradlew stressTest` | 대량 데이터·동시성 부하 |

- 어노테이션은 `com.wafflestudio.snutt.core.test`(`TestTags.kt`)에 있고 클래스나 메서드에 붙인다.
- `./gradlew test`는 위 태그를 모두 제외한다. 태그 태스크는 모든 서브프로젝트에 있고, 해당 태그 테스트가 없어도 성공한다.
- 태그를 추가하려면 루트 `build.gradle.kts`의 `separatedTags`와 `TestTags`를 함께 고친다.
- 테스트 JVM 타임존은 운영과 같은 UTC로 고정되어 있다(`user.timezone=UTC`). 로컬 타임존에 기대지 않는다.

## 베이스 클래스 고르기

| 상황 | 베이스 |
|---|---|
| api/v1compat HTTP 또는 서비스 통합테스트 | `AbstractApiIntegrationTest` (api 모듈, RANDOM_PORT, `get`/`post`/`register` 헬퍼) |
| batch 잡 통합테스트 | `AbstractBatchIntegrationTest` (batch 모듈) |
| Spring 없이 되는 순수 로직(파서, 계산) | 베이스 없음. 외부 입력 파싱처럼 경계가 분명한 것만 쓴다 |
| migration | `MigrationIntegrationTest`처럼 컨테이너를 직접 띄운다. 이미지는 `TestImages` 상수를 쓴다 |
| v1 서버와 응답 비교 | `AbstractParityTest` (parity 모듈). 사용법은 [parity.md](parity.md) |

`AbstractIntegrationTest`(공통 루트)가 해 주는 것:
- 매 테스트 전에 모든 테이블을 비우고(빌트인 테마 제외) Redis를 `FLUSHALL`하고 `clock`을 시스템 시각으로 되돌린다.
- `pushClient`, `mailClient` mock (기본 스텁: 발송 성공).
- `clock: MutableClock`, `fixtures: ScenarioFixtures` 주입.
- DataSource를 datasource-proxy로 감싼다(쿼리 수 측정용).

### 컨텍스트 캐시 규칙 (중요)

Spring 컨텍스트는 모듈마다 한 번만 떠야 한다. 지금 api 1개, batch 1개, parity 1개다.
- 테스트 클래스에 `@MockitoBean`, `@MockitoSpyBean`, `@Import`, `@TestPropertySource`, `@DirtiesContext`, `@SpringBootTest(properties=...)`를 새로 달지 않는다. 하나라도 다르면 컨텍스트가 하나 더 뜬다(수십 초, 메모리 수백 MB).
- 새 mock이 필요하면 모듈 베이스(`AbstractApiIntegrationTest`, `AbstractBatchIntegrationTest`)에 추가한다. 스텁하지 않은 mock은 기본값(null, 0, 빈 컬렉션)을 돌려주고 매 테스트 뒤 reset되므로 다른 테스트에 영향이 없다.
- 이미 베이스에 있는 mock: api는 `uploadUriIssuer`, `googleClient`(`GOOGLE`), `appleClient`(`APPLE`)이고, batch는 `sugangSnuLectureApi`, `sugangSnuLectureEnricher`, `lectureBuildingSync`, `registrationPeriodExtractor`, `registrationStatusCrawler`다.
- api 테스트 컨텍스트는 `snutt.scheduling.enabled=false`로 `@Scheduled`를 끈다. 스케줄러는 `ReminderScheduler.fireDueReminders()`처럼 직접 호출한다.

## mock 경계

- 외부 HTTP API(sugang.snu.ac.kr, OAuth 제공자, GitHub 등)는 네트워크 경계(WireMock 등)에서 mock하는 것을 목표로 한다. 지금 베이스에 있는 빈 mock(sugang, OAuth)은 과도기 상태다. 네트워크 경계 mock으로 바꾸면 베이스에서 빼고, 그 클라이언트의 base URL은 설정값으로 주입한다.
- FCM(`PushClient`), 메일(`MailClient`), OCI 업로드(`UploadUriIssuer`)처럼 SDK가 경계인 것은 빈 mock을 그대로 쓴다.
- 우리 코드의 서비스나 리포지토리는 mock하지 않는다. 실제 DB와 Redis를 통과시킨다.

## 시간: `MutableClock`

프로덕션 코드는 현재 시각을 주입받은 `java.time.Clock` 빈으로만 읽는다(`core/common/time/ClockConfig`). `Instant.now()`, `LocalDateTime.now()`, `System.currentTimeMillis()`를 새로 쓰지 않는다. 학기는 `SemesterCalendar.current(clock)`/`next(clock)`으로 구한다.

테스트에서는 `Clock` 빈이 `MutableClock`(`@Primary`)으로 바뀐다.

```kotlin
clock.setKst(LocalDateTime.of(2026, 9, 15, 9, 50))   // KST 벽시계로 고정 (= 00:50Z)
clock.setInstant(Instant.parse("2026-09-15T00:50:00Z"))
clock.advance(Duration.ofMinutes(10))                 // 고정한 채로 이동
clock.nowKst()                                        // ZonedDateTime(KST)
```

- 기본은 시스템 시각을 따른다. 시간에 따라 결과가 달라지는 테스트는 반드시 고정한다. 매 테스트 전에 자동으로 `reset()`된다.
- KST 자정이나 학기 시작일 같은 경계는 `setKst`로 경계 직전과 직후를 둘 다 잡는다. 예: `LocalDateTime.of(2026, 8, 31, 23, 59)`, `LocalDateTime.of(2026, 9, 1, 0, 0)`.
- Clock을 따르지 않는 시각(실제 시스템 시각을 쓴다):
  - Hibernate `@CreationTimestamp`/`@UpdateTimestamp`(`BaseEntity`의 `created_at`, `updated_at`, `LectureRegistrationStatus`)
  - `User` 생성자 기본값 `lastLoginAt`, `notificationCheckedAt`(`core/domain/user/model/User.kt`, 엔티티라 빈 주입 불가)
  - `OciUploadUriIssuer`의 업로드 URL 만료 시각(dev/prod 프로파일 전용, 테스트에서는 `uploadUriIssuer` mock)
  - `migration` 모듈의 `Instant.now()` 전부(일회성 이관 도구, Spring 컨텍스트 테스트 아님)
  - DB의 `NOW()`, Redis TTL
- 이 값들과 Clock 시각을 비교하는 로직을 테스트할 때는 해당 컬럼을 JDBC로 직접 세팅한다(예: `MailLogIntegrationTest`). `User`를 만든 뒤 시각이 중요하면 필드를 명시적으로 넣는다.

## 쿼리 수: `assertQueryCount`

```kotlin
import com.wafflestudio.snutt.core.test.query.assertQueryCount

val detail = assertQueryCount(select = 6, insert = 0, update = 0, delete = 0) {
    get("/v2/timetables/$timetableId", token)
}
```

- null인 종류는 검증하지 않는다. `total`로 전체를 볼 수도 있다. 개수만 필요하면 `recordQueries { }`가 `(결과, RecordedQueries)`를 돌려준다.
- JDBC 실행 단위로 센다. JDBC batch 한 번은 1개다.
- 스레드와 관계없이 모으므로 RANDOM_PORT 서버 스레드의 쿼리도 센다. 블록 안에서는 비동기 작업이 DB를 건드리지 않게 한다.
- 실패 메시지에 실행된 SQL 전체가 찍힌다. 기대값은 그 목록을 보고 N+1이 아닌지 확인한 뒤 정한다.
- 예시: `TimetableIntegrationTest`의 `custom 강의와 customization override`.

## 시나리오 fixture: `ScenarioFixtures`

학기 개설부터 시간표에 강의를 넣는 데까지를 몇 줄로 쓴다. api와 batch 테스트 모두 `fixtures`로 바로 쓸 수 있다.

```kotlin
val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
val lecture = fixtures.lecture(autumn, courseTitle = "운영체제", times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675))) {
    instructor = "김교수"; quota = 40
}
val user = fixtures.user("alice")                   // email = alice@snu.ac.kr, 인증됨
val timetable = fixtures.timetable(user, autumn)    // 대표 시간표
fixtures.addLecture(timetable, lecture)
```

- 리포지토리로 바로 저장하므로 서비스 규칙(시간 겹침, 리마인더 재계산)을 거치지 않는다. 그 규칙을 검증하려면 API나 서비스를 호출한다.
- 액세스 토큰이 필요한 HTTP 테스트는 `register(localId)`(api 베이스)로 가입한다.
- 강의와 수업시간만 저장하려면 `saveLectureWithTimes(...)`(`core.test.fixture`)를 쓴다.
- 필요한 엔티티가 없을 때만 메서드를 추가한다. 쓰는 곳이 하나뿐이면 그 테스트 안에 둔다.

## 새 테스트 체크리스트

1. 골든패스 시나리오인가? 쓰려는 사이드케이스가 외부 입력이나 레거시 데이터에서 생기는가?
2. 맞는 베이스를 상속했고, 클래스에 `@MockitoBean`/`@Import`/프로퍼티를 새로 달지 않았는가?
3. 외부 API mock이 네트워크 경계에 있는가? 빈 mock이 꼭 필요하면 모듈 베이스에 추가했는가?
4. 시간 의존 로직이면 `clock`을 고정했는가? KST 경계를 다루는가?
5. 난수 seed를 고정했는가? `Thread.sleep` 없이 polling으로 기다리는가?
6. 성능을 assert한다면 시간이 아니라 쿼리 수나 실행계획을 봤는가? 무거운 테스트에 `@PerfTest`/`@StressTest`를 붙였는가?
7. `./gradlew ktlintCheck test`(태그를 붙였으면 해당 태스크도)를 돌려 통과했는가?
