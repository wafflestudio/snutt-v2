# v1 동등성 비교(parity)

같은 데이터에 같은 요청을 v1 서버(snutt + snutt-ev)와 v2의 v1compat 엔드포인트로 보내고, 응답이 같은지 비교한다. 정답은 v1 코드의 실제 동작이다. 시드 데이터를 v2로 옮길 때 실제 `migration` 단계를 쓰므로 이관도 함께 검증된다.

엔드포인트 목록과 코드 읽기로 찾은 차이는 [v1-compat-audit.md](v1-compat-audit.md)에 있다. parity는 그 문서의 "parity 시나리오 후보"를 실행 가능한 비교로 만든 것이다.

## 한눈에

```
                       ┌──────────── 테스트 JVM (./gradlew parityTest) ─────────────┐
 ParitySeed ──► v1 Mongo 문서 ──┐                                                    │
   (코드 시드)   EV MySQL 행 ───┼─► migration 단계(실제 코드) ─► v2 MySQL ─► v2 api(RANDOM_PORT)
                                │                                                    │
                                └─► v1 snutt 컨테이너 ◄── EV 프록시 ── v1 snutt-ev 컨테이너
                                                                                     │
 시나리오 ── 같은 요청 ──► v1, v2 순서대로 ──► 정규화 ──► JSON diff ──► 허용 목록 대조 ──► 리포트
                       └─────────────────────────────────────────────────────────────┘
```

- 위치: `parity` 모듈(테스트 소스만 있음). 시나리오는 `parity/src/test/kotlin/.../parity/scenarios/`.
- 태그와 태스크:

| 태그 | 어노테이션 | 태스크 | 내용 |
|---|---|---|---|
| `parity` | `@ParityTest` | `./gradlew parityTest` | 시드로 도는 비교. 등록되지 않은 차이가 있으면 실패 |
| `parity-known-failure` | `@ParityKnownFailure` | `./gradlew parityKnownFailureTest` | parity가 찾은 버그 재현. 고쳐지기 전까지 실패 |
| `parity-snapshot` | `@ParitySnapshot` | `./gradlew paritySnapshotTest -Dparity.source.…` | 외부 덤프로 도는 비교(작업 M) |

`./gradlew test`(PR CI)에는 parity 모듈의 `AllowedDiffsFileTest`만 들어간다. 허용 목록 YAML이 깨지지 않았는지 컨테이너 없이 확인한다.

## v1 서버

### 기준 커밋

[`parity/v1/versions.properties`](../parity/v1/versions.properties)에 고정한다. 둘 다 운영에 배포되는 `main` 브랜치의 커밋이다.

| 서버 | 커밋 | 근거 |
|---|---|---|
| snutt | `47bab6e` | 운영 배포는 `main` push(`.github/workflows/deploy-api-prod.yml`). audit 기준 `develop` 6aac982와는 일기장 딥링크 1줄과 CI 러너만 다르다 |
| snutt-ev | `b727a4f` | 운영은 `main`. audit의 **[dev]** 항목(`evaluation_count`, `sort`/`year`/`semester`)은 운영에 없으므로 allowed-diffs에 [dev]로 등록했다 |

### 이미지 확보 방법

고정 커밋을 clone해서 `./gradlew :api:bootJar`로 jar를 만들고, `eclipse-temurin:25-jre` 컨테이너에 jar를 복사해 띄운다(`parity/v1/build-v1-jar.sh`, Gradle 태스크 `buildV1SnuttJar`/`buildV1EvJar`). jar는 `~/.gradle/caches/snutt-parity/<서버>-api-<sha>.jar`에 남아 `clean`과 무관하게 재사용된다.

고른 이유:
- OCIR 이미지 pull은 OCI 인증 토큰이 필요하고, 이미지 태그(`github.run_number`)와 커밋을 대응시킬 방법이 레포에 없다. 커밋으로 고정할 수 없다.
- v1 Dockerfile을 그대로 `ImageFromDockerfile`로 빌드하면 BuildKit secret(`--mount=type=secret,id=github_token`)이 필요한데 Testcontainers가 이를 지원하지 않는다. 컨테이너 안 Gradle 빌드라 매번 수 분이 걸리기도 한다.
- 호스트 빌드 + JRE 이미지는 GitHub Actions arm64 러너에서도 그대로 돈다(`eclipse-temurin:25-jre`는 arm64를 제공). 운영 이미지(GraalVM JDK 25, JVM 모드)와 JVM 구현만 다르고 같은 jar다.

필요한 토큰: v1 빌드는 GitHub Packages(`wafflestudio/spring-waffle`)에서 의존성을 받는다. v1 `build.gradle.kts`가 `gpr.key` Gradle 프로퍼티 → `GITHUB_TOKEN` 환경변수 → `gh auth token` 순서로 찾으므로, 로컬에서는 `gh auth login`이 되어 있으면 된다. CI에서는 `GITHUB_TOKEN`(`packages: read`)을 넘기고, org 관리자가 spring-waffle 패키지의 "Manage Actions access"에 이 레포를 추가해야 한다.

`PARITY_SNUTT_REPO`, `PARITY_EV_REPO` 환경변수로 clone할 저장소를 바꿀 수 있다(예: 로컬 사본 `~/IdeaProjects/snutt`). 커밋은 바뀌지 않는다.

### 의존성과 설정

| | 이미지 | 근거 |
|---|---|---|
| Mongo(snutt) | `TestImages.MONGO` (mongo 8.2.7) | 하네스 기반 작업이 정한 값. 운영 버전 근거는 레포에 없다 |
| MySQL(snutt-ev) | `TestImages.MYSQL` 서버의 `snutt_ev` DB | 메모리 때문에 v2와 같은 MySQL 서버를 쓰고 DB만 나눈다 |
| Redis(snutt db 0, snutt-ev db 1) | `TestImages.VALKEY` | v1 운영 Redis 버전 근거 없음. 캐시·인증 코드 용도라 버전 영향이 적다 |

v1 설정은 jar를 고치지 않고 바깥에서 바꾼다. 바꾼 것과 이유는 설정 파일 주석에도 있다.

- snutt: [`snutt-application.yml`](../parity/src/test/resources/parity/v1/snutt-application.yml)을 `spring.config.location`으로 넣어 jar 안 설정을 대신한다. 내용은 v1 기본 설정과 같고 `oci.vault.secret-ids`만 뺐다(있으면 기동 중 OCI Vault를 조회하고, 빈 값도 조회를 시도한다). 활성 프로파일은 `prod`다(v1 `PhaseUtils`가 dev/prod/local/test 외에는 예외를 던진다). 파일에 프로파일별 구역이 없으므로 운영 접속 정보는 들어오지 않는다.
- snutt: `spring.data.mongodb.auto-index-creation: true`. v1 설정의 `spring.mongodb.auto-index-creation`은 Spring Boot 4에 없는 키라 무시된다. 운영 Mongo에는 예전 버전이 만든 인덱스(유일 제약)가 있으므로 빈 Mongo에서도 같게 만든다. 없으면 빈자리 알림 중복 같은 유일 제약 동작이 운영과 달라진다.
- snutt-ev: [`ev-application.yml`](../parity/src/test/resources/parity/v1/ev-application.yml)을 `spring.config.additional-location`으로 얹는다. `spring.flyway.locations`를 `classpath:db`로 고친다. v1 값 `classpath:/db`는 앞의 `/` 때문에 Flyway가 마이그레이션을 찾지 못해 빈 DB에 스키마를 만들지 않는다(운영은 이미 스키마가 있어 드러나지 않음).

### 외부 연동

| 연동 | v1 | v2 |
|---|---|---|
| FCM, OCI 메일, OCI Object Storage | shim(`parity/src/v1shim`)이 빈을 생성자 없는 무동작 객체로 바꾼다. loader.path로 얹은 jar의 `spring.factories`로 등록되는 `ApplicationContextInitializer`다. `ociAuthProvider`는 lazy로 바뀌어 만들어지지 않는다 | `pushClient`, `mailClient`, `uploadUriIssuer` 빈 mock (모듈 베이스 `AbstractParityTest`) |
| OAuth(Google·Apple·Kakao·Facebook) | 호출하지 않는다(소셜 로그인 쓰기 시나리오 없음) | `GOOGLE`, `APPLE` 빈 mock |
| sugang, GitHub 피드백, Firebase Dynamic Links | 호출하지 않는다 | 호출하지 않는다 |

소셜 로그인, 친구 초대 링크(Dynamic Links), 피드백, 이미지 업로드 URL은 외부 호출이 필요해 시나리오에 넣지 않았다.

## 시드

[`ParitySeed`](../parity/src/test/kotlin/com/wafflestudio/snutt/parity/seed/ParitySeed.kt) 하나가 v1 Mongo 문서와 EV MySQL 행을 만든다. 문서 모양은 v1 데이터 클래스(필드 이름, enum은 Int)를, EV 행은 snutt-ev Flyway 스키마(V1~V5)를 따른다. EV 태그는 snutt-ev의 seed CSV(`parity/src/test/resources/parity/ev-seed/`)를 그대로 넣는다. v2 행은 손으로 만들지 않는다. 매 테스트 전에 v1 저장소를 비우고 시드를 다시 넣은 뒤 `migration` 단계(MigrationRunner와 같은 순서)로 옮기므로 시나리오끼리 독립이다.

담은 것: 학기 4개, 사용자 9명(로컬, Facebook 연동, Kakao+Google, Apple, 탈퇴), 시간표 여러 개(대표·비대표, 학기 여러 개, 사용자 테마, 직접 만든 강의, 리마인더), 테마(개인·게시·다운로드), 친구(수락·요청·탈퇴 상대), 북마크, 빈자리 알림, 푸시 설정, 기기, 알림(전체·개인, deeplink), 설정, 건물, EV 과목·학기 강의·강의평·공감과 `lectures.evInfo`.

레거시의 이상한 모양 데이터(시드 코드에 ★로 표시, migration의 `ResolutionReasons`와 audit에서 고름): `isEmailVerified` 없는 계정, 이메일 없는 Apple 계정, 같은 아이디의 활성 계정 둘, 같은 `credentialHash`의 계정 둘, 같은 개설 강의 문서 둘, 빈 문자열 강사, 범위 밖 팔레트 번호, 배경색만 있는 직접 만든 강의, 같은 학기 같은 제목의 대표 시간표 둘, 주인 없는 시간표, `is_primary` 없는 시간표, 수강편람에서 사라진 강의를 담은 북마크, 사용자가 없는 강의평과 공감, ObjectId가 든 deeplink, 같은 이름의 설정 둘, 영문 학년이 숫자인 강의, 오래된 문서의 남는 필드.

시각: 두 서버 모두 실제 시각으로 돈다(v1 컨테이너의 시계를 고정할 수 없다). "현재 학기" 같은 시간 의존 응답도 양쪽이 같은 시각을 보므로 비교 결과는 같다. 시각 필드는 형식만 비교한다.

## 실행

```bash
./gradlew parityTest              # 처음 한 번은 v1 jar 빌드로 수 분, 이후 2분 안팎
./gradlew parityKnownFailureTest  # 알려진 버그 재현. 남은 것이 없으면 테스트 0개로 성공한다
```

- 필요한 것: Docker, JDK 25, GitHub Packages 토큰(위 "이미지 확보 방법").
- 메모리: v1 컨테이너 둘(각 ~400MB, `-Xmx384m`), Mongo, MySQL, Valkey 둘, 테스트 JVM(`-Xmx1g`, `-Dparity.heap=2g`로 바꿈).
- 리포트: `parity/build/reports/parity/<태스크>/summary.md`(집계, 비교한 엔드포인트, 쓰이지 않은 허용 항목)와 `scenarios/<시나리오>.md`(요청, 양쪽 응답 바디, 차이 경로와 판정). v1 컨테이너 로그는 같은 디렉터리의 `v1-snutt.log`, `v1-ev.log`.
- 실패 메시지는 등록되지 않은 차이를 요청별로 15개까지 보여 주고 리포트 경로를 알려 준다.

CI: `.github/workflows/parity.yml`(수동 실행)이 같은 명령을 arm64 러너에서 돈다. PR 게이트에는 넣지 않았다(v1 빌드 토큰 권한이 필요하고 시간이 길다).

## 시나리오 추가

```kotlin
@ParityTest
class BookmarkParityTest : AbstractParityTest() {
    @Test
    fun `북마크를 담는다`() =
        parity("북마크 담기") {
            post("/v1/bookmarks/lecture", """{"lecture_id":"@{lecture:os}"}""", `as` = "alice")
            get("/v1/bookmarks?year=2026&semester=3", `as` = "alice", label = "담은 뒤 목록")
        }
}
```

- `parity(이름) { … }` 안의 요청은 v1, v2 순서로 나간다. 시나리오 끝에서 등록되지 않은 차이가 있으면 실패한다(중간에 멈추지 않고 끝까지 돈다).
- `@{별칭}`은 쪽마다 다른 값으로 바뀐다. 시드 별칭은 `user:alice`, `lecture:os`, `tt:alice-main`, `tl:alice-main/os`, `theme:alice-custom`, `friend:alice-bob`, `course:os`, `evaluation:bob-os` 같은 모양이다(`SeedBuilder`가 등록).
- `as = "alice"`는 그 사용자의 레거시 토큰을 `x-access-token`으로 보낸다. 시드 사용자의 토큰은 v1 `credentialHash`이고, 이관이 그 해시를 `legacy_access_token`에 넣으므로 양쪽에서 통한다.
- 새로 생긴 값은 `.capture("tt:new", "/-1/_id")`로 잡아 다음 요청에 쓴다. 토큰은 `capture("token:<이름>", "/token")`으로 잡고 `as = "<이름>"`으로 쓴다.
- 기본 헤더는 iOS 구 앱(`x-access-apikey`, `x-os-type: ios`, `x-app-version: 4.0.0`)이다. `defaultHeaders`를 바꾸거나 요청마다 `headers`로 덮는다(빈 문자열은 헤더 제거).
- 시드가 필요하면 `ParitySeed`에 더한다. v1 문서 모양은 v1 데이터 클래스를 확인하고 맞춘다(모양이 틀리면 v1이 500을 낸다).

## 비교와 정규화

비교하는 것: 상태 코드, 응답 헤더 `Content-Type`(미디어 타입만)과 `Location`, JSON 바디 전체(키, 값, JSON 타입, 배열 순서). JSON이 아닌 바디는 문자열로 비교한다.

정규화 규칙은 [`NormalizationRules`](../parity/src/test/kotlin/com/wafflestudio/snutt/parity/compare/Normalization.kt) 한곳에 있고 각각 이유를 주석으로 달았다.

1. id(`_id`, `id`, `user_id` 등 정해진 키): v1 ObjectId와 v2 Long은 값이 다를 수밖에 없다. 시드·캡처 별칭(`@user:alice`)이나 같은 자리(엔드포인트 + 경로)에 처음 나온 순서(`#1`)로 바꾼다. 숫자로 나간 id에는 `~num`이 붙어 문자열 id와 구별된다. v2 id는 테이블마다 1부터 시작하므로 같은 경로의 v1 별칭 종류를 힌트로 고른다.
   v1은 새 id인데 v2 값이 그 자리에 오던 종류의 기존 엔티티면 순번 대신 후보 별칭을 적어 차이로 남긴다(복사 응답에 원본이 나오는 버그 등).
2. 알림 `deeplink` 안의 `timetableId=`·`lectureId=` 값은 1과 같은 방식으로 별칭으로 바꾼다. 나머지 문자열은 그대로 비교한다.
3. 서버가 발급하는 토큰(`token`, 빈 값 제외): `<token>`.
4. 시각 문자열: 데이터를 준비한 뒤에 생긴 시각(요청 시각, 이관이 `Instant.now()`로 채운 시각)은 형식(`<date>`, `<datetime:local>`, `<datetime:Z>`, `<datetime:+09:00>`)만 비교한다. 그 전의 시각(시드 값을 이관이 옮긴 것)은 형식과 순간을 함께 비교해 시간대·날짜 밀림을 잡는다. 소수부 자릿수는 보지 않는다(v1 Mongo 밀리초, v2 MySQL 마이크로초, 앱은 자릿수와 무관하게 파싱).

이 밖의 차이는 정규화하지 않는다. 의도한 차이는 허용 목록에 등록한다.

## 허용 차이 등록

[`allowed-diffs.yml`](../parity/src/test/resources/parity/allowed-diffs.yml)에만 등록한다.

```yaml
- endpoint: GET /v1/tables/{id}          # METHOD + 경로 템플릿(@{별칭} → {id}), * 와 {a,b} 가능
  path: $.lecture_list[*].colorIndex     # [*] 인덱스, * 키 하나, ** 아무 경로, {a,b}, status, header:이름
  kind: changed                          # changed / only-v1 / only-v2 / order (생략하면 전부)
  v1: 12                                 # 그 값일 때만(객체면 적은 키만 비교)
  v2: 3
  scenario: 시간표 조회: 이관 데이터     # 이름 또는 목록. label(요청 label, 이름 또는 목록)로 더 좁힐 수 있음
  reason: 왜 의도한 차이인지, 앱에서 왜 문제가 없는지
  ref: docs/v1-compat-audit.md #55
```

규칙:
- `reason`은 필수다. "앱이 이 필드를 어떻게 쓰는지"(Android/iOS 코드 위치)나 "이관의 어떤 규칙 때문인지"를 적는다. 근거가 없으면 허용하지 않는다.
- 넓은 경로(`**`, `*` 엔드포인트)는 `v1`/`v2` 값 조건, `onlyErrors`, `scenario`/`label`로 좁힌다. 예: 빈 문자열 → 생략은 `v1: ""`일 때만.
- 배열 순서: `kind: order` + `sortBy`(내용 키, 점으로 하위 필드)는 양쪽을 안정 정렬한 뒤 비교한다. 같은 키를 가진 원소끼리의 순서는 그대로라 그 안의 차이는 잡힌다. 앱이 그 키로 다시 정렬하거나, v2 쿼리에 명시적 정렬이 있을 때만 쓴다. 정렬 키는 id 대신 내용을 쓴다(외부 덤프에는 id 별칭이 없다).
- 원소 하나가 끼거나 빠지는 경우: `kind: only-v1/only-v2` + `element: {키: 값}`이 그 원소를 빼고 나머지를 비교한다.
- 실제 버그는 등록하지 않는다(아래).
- `summary.md`의 "이번 실행에서 쓰이지 않은 허용 항목"이 생기면 고쳐졌거나 경로가 틀린 것이다. 지운다.

## 알려진 차이(버그)

parity가 찾은 실제 차이(버그)는 `@ParityKnownFailure` 시나리오로 재현해 두고(`./gradlew parityKnownFailureTest`, 고쳐지기 전까지 실패), 시나리오마다 그 버그를 드러내는 요청만 둔다. 고치면 시나리오를 `@ParityTest` 클래스로 옮긴다.

여러 응답에 두루 나타나 시나리오 분리만으로는 나머지 비교를 할 수 없는 버그는 [`known-failures.yml`](../parity/src/test/resources/parity/known-failures.yml)에도 둔다. `parityTest`는 여기 걸린 차이를 실패 대신 "알려진 버그"로 리포트에 남기고, `@ParityKnownFailure` 시나리오는 이 목록 없이 돌아 실패한다. 허용 목록과 같은 형식이지만 의도한 차이가 아니라는 점이 다르다.

지금은 남은 known-failure가 없다(시나리오 0개, `known-failures.yml`은 빈 목록). 아래는 지금까지 찾은 것과 처리 결과다.

| 번호 | 차이 | 재현 시나리오 | 비고 |
|---|---|---|---|
| B1 | EV 과목 없음·분반 없음 errcode가 뒤바뀜(v1 24001/24002 → v2 24002/24001) | 강의평 웹: 없는 과목·분반 | 고침(작업 D). audit 요약 2 |
| B2 | 푸시 설정을 저장한 적 없는 사용자의 `GET /v1/push/preferences`: v1 기본값 3개, v2 빈 목록 | 푸시 설정: 저장한 적 없음(bob) | 고침(작업 D). audit 요약 3. 두 앱 모두 없는 종류를 켜짐으로 읽어(Android `UserMappers`, iOS `PushNotificationAPIRepository`) 화면 영향은 낮다 |
| B3 | `isEmailVerified`가 없던 계정이 강의평에서 403 0x3011 | 강의평: isEmailVerified 없는 계정(carol) | 고침(작업 D). audit 요약 4. 앱의 강의 상세 요약(`/v1/ev/lectures/{id}/summary`)은 막히지 않는다 |
| B4 | v1 경로의 없는 경로(404)·지원하지 않는 메서드(405)가 ProblemDetail(`errcode` 없음) | 레거시 에러 바디: 404·405 | 고침(작업 D). audit 요약 6 |
| B5 | EV 응답의 `user_id → user {id, email, local_id}` 치환이 없음 | 강의평 웹: 조회 등 EV 시나리오 전반 | 고치지 않음(의도한 차이, allowed-diffs.yml). 작성자 이메일·아이디 노출 제거. 강의평 웹이 `user` 필드를 읽는지 확인 필요 |
| B5b | EV `cursor=`(빈 값): v1 첫 페이지, v2 400 20001 | 강의평 웹: 빈 cursor | 고침(작업 G2, v1compat `legacyCursor`). audit "커서". 빈 값이 아닌 잘못된 커서는 v1(첫 페이지)과 달리 400으로 남긴다 |
| B6 | 세부 점수가 NULL인 강의평에서 v1 EV는 `0.0`, v2는 필드 생략 | 강의평 웹: 세부 점수 없는 강의평 | 고침(작업 G2, v1compat EV 매퍼만, v2 API는 NULL 그대로). 수정(PATCH) 응답은 v1처럼 생략 |
| B7 | 다른 시간표를 대표로 지정하면 v2는 대표에서 풀린 시간표의 `updated_at`도 바꾼다(v1은 그대로) | 시간표: 대표 변경과 수정 시각 | 고침(작업 G2). 대표 지정·해제는 `is_primary` 컬럼만 JPQL로 고쳐 `@UpdateTimestamp`를 거치지 않는다(`TimetableRepository.updateIsPrimary`). v2 엔드포인트도 같은 서비스라 함께 바뀐다(새 대표의 updated_at도 그대로) |

parity가 찾았고 이 작업에서 고친 것: v1compat 시간표 목록 순서(같은 학기 안에서 v1은 생성 순, v2는 유일 인덱스 때문에 제목 순). `V1CompatTimetableController`에서 id 순으로 정렬한다.

## 외부 데이터로 돌리기

작업 M(마이그레이션 리허설)은 시드 대신 운영 스냅샷을 넣는다. 같은 v1 컨테이너, 같은 migration 단계, 같은 허용 목록을 쓴다.

```bash
./gradlew paritySnapshotTest \
  -Dparity.source.mongo-archive=/path/snutt.archive.gz \  # mongodump --archive --gzip --db <db>
  -Dparity.source.mongo-database=snutt \                  # 덤프의 DB 이름(기본 snutt)
  -Dparity.source.ev-dump=/path/snutt-ev.sql \            # mysqldump --no-tablespaces <db> (.sql 또는 .sql.gz, --databases 없이)
  -Dparity.source.roles=/path/roles.yml \
  -Dparity.heap=4g
```

```yaml
# roles.yml: 비교할 사용자(v1 users._id). 토큰은 덤프의 credentialHash를 쓴다.
users:
  heavy-user: "5f0c…"      # 시간표·친구·강의평이 많은 사용자
  social-only: "60a1…"
aliases:                    # 선택. 종류: user, lecture, tt, theme, course, evaluation
  tt:heavy-main: "5f0d…"
```

- 확장 지점은 `ParityDataSource` 인터페이스다. 시드는 `SeedDataSource`, 덤프는 [`SnapshotDataSource`](../parity/src/test/kotlin/com/wafflestudio/snutt/parity/env/SnapshotDataSource.kt)가 구현한다. Mongo는 `mongorestore --drop`으로 넣으므로 덤프의 인덱스가 그대로 들어간다.
- 시나리오는 [`SnapshotParityTest`](../parity/src/test/kotlin/com/wafflestudio/snutt/parity/scenarios/SnapshotParityTest.kt)다. 시드 별칭 없이 역할 사용자와 응답에서 잡은 id로 읽기 흐름만 비교한다. 역할 사용자마다 시나리오 하나가 생기고, 차이가 있는 사용자를 모두 모아 한 번에 실패한다.
- 시드에서 시나리오 한정으로 허용한 이관 정리(같은 아이디 계정, 중복 대표 시간표, 잘못된 색 등)는 스냅샷에서 "허용 목록에 없는 차이"로 나온다. 리허설에서는 이 차이가 이관 규칙대로인지 보는 것이 목적이다.
- 형식 확인용으로 시드를 같은 형식으로 내보낼 수 있다: `./gradlew parityTest --tests '*AppStartParityTest' -Dparity.dump-seed-to=/tmp/seed-dump`가 `mongo.archive.gz`, `ev.sql`, `roles.yml`을 만든다. 이 파일로 `paritySnapshotTest`를 돌려 경로를 검증했다.
- 운영 덤프에는 실제 토큰과 개인정보가 있다. 리포트(`parity/build/reports`)에 응답 바디가 그대로 남으므로 공유하지 않는다.

## 한계

- 강의평 웹, 테마 마켓 웹의 소스는 보지 않았다. 웹이 읽는 필드에 대한 판단(B5의 `user`, 테마 마켓 `name`, 태그 그룹 순서)은 확인이 필요하다.
- 무작위가 끼는 동작(색 자동 선택, 가입·닉네임 변경의 닉네임)은 결정적인 경우만 비교한다(`강의 추가: 색 자동 선택`).
- 수강편람 동기화 이후의 차이(v1 스냅샷 ↔ v2 조인)는 보지 않는다. 시드는 이관 직후 상태다.
- 쓰기 시나리오는 상태가 바뀐 뒤의 조회까지 비교하지만, 푸시·메일이 실제로 나갔는지는 보지 않는다(양쪽 모두 stub).
