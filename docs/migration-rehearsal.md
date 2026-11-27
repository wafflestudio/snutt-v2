# 마이그레이션 리허설 런북

운영 스냅샷(개인정보 마스킹본)으로 v1 → v2 이관 전체를 미리 돌려 보고, 결과가 이관 규칙대로인지, 걸리는 시간이 다운타임 안에 드는지 확인하는 절차다. 도구는 레포에 있고, 운영 데이터를 다루는 실행은 사람이 보안 환경에서 한다.

- 도구 코드: `migration/src/test/kotlin/.../migration/rehearsal/` (테스트 소스셋. 운영 jar에 들어가지 않는다)
- 이관 코드: `migration` 모듈의 `MigrationRunner`와 `step/` 아래 단계들. 리허설은 이 러너를 그대로 쓴다(단계 순서, 비우기 정책 포함)
- 도구 자체의 검증: `./gradlew :migration:rehearsalTest` (합성 덤프로 전체 파이프라인을 돈다. `test`에서는 빠진다)

```
 운영자(보안 환경)                          리허설 도구(로컬 Docker)
 ─────────────────                          ─────────────────────────────────────────────────────────────
 mongodump ─► snutt.archive.gz ─┐
                                ├─► maskDump ─► mongo.archive.gz ─┐
 mysqldump ─► snutt-ev.sql.gz ──┘    (원본은 여기서만 읽음)   ev.sql.gz ─┤
                                                                  ├─► rehearse ─► rehearsal-report.md/.json
                                                                  │     복원 → Flyway(V1~) → 이관 → 검증 → 재이관     roles-sample.yml
                                                                  └─► paritySnapshotTest (v1 서버 vs v1compat)
```

## 1. 사전 준비

### 덤프 획득 (운영자가 수행)

획득 방법(접속 정보, 권한, 보관 위치)은 운영자가 정한다. 도구가 받는 형식은 다음과 같다.

| 원본 | 형식 | 만드는 명령의 예 |
|---|---|---|
| v1 Mongo(snutt) | `mongodump --archive --gzip`, DB 하나 | `mongodump --uri=<v1 uri> --db=snutt --archive=snutt.archive.gz --gzip --readPreference=secondary` |
| EV MySQL(snutt-ev) | `mysqldump` SQL(.sql 또는 .sql.gz), `--databases` 없이 DB 하나 | `mysqldump --no-tablespaces --single-transaction --set-gtid-purged=OFF <ev db> \| gzip > snutt-ev.sql.gz` |

- 두 덤프는 가능한 한 같은 시각에 뜬다. 시각이 벌어지면 그 사이 생긴 사용자·강의평이 한쪽에만 있어 "사용자가 없는 공감" 같은 정리 건수가 늘어난다(실패는 아니지만 수치 해석이 어려워진다).
- Mongo 덤프 안의 DB 이름이 `snutt`가 아니면 `--mongo-database=<이름>`을 준다.
- `--set-gtid-purged=OFF`를 빼먹어도 도구가 받아들인다(복원 전에 로컬 서버의 GTID 기록을 비운다).
- 원본 덤프는 개인정보 원문이다. 마스킹이 끝나면 원본은 운영자 절차대로 지운다. 리허설 이후 단계(rehearse, parity)는 마스킹본만 쓴다.

### 실행 환경

- Docker(Testcontainers가 `mongo:8.2.7`, `mysql:26.7`을 띄운다. 이미지 버전은 `core/src/testFixtures/.../TestImages.kt`), JDK 25, 이 레포.
- 메모리: 운영 크기 스냅샷이면 MySQL 컨테이너 버퍼 풀과 리허설 JVM 힙을 키운다: `-Drehearsal.mysql-buffer-pool=4G -Drehearsal.heap=6g`. 리허설 JVM은 이관 컨텍스트(사용자·강의·시간표 id 표)와 원본 색인(사용자·강의)을 메모리에 든다. 기본값(128MB 버퍼 풀)으로는 디스크 I/O가 지배해 소요 시간이 운영보다 훨씬 길게 나온다.
- 디스크: 덤프 크기의 약 4배(컨테이너 안 복원본 + 마스킹 출력).
- 마스킹 비밀값: 16바이트 이상의 임의 문자열을 환경변수 `REHEARSAL_MASK_SECRET`로 준다. 같은 비밀값이면 같은 결과가 나와 리허설을 반복해도 표본이 같다. 비밀값은 저장하지 않는다(비밀값과 마스킹본이 함께 새면 원문 추측 공격이 가능해진다).

## 2. 마스킹

```bash
export REHEARSAL_MASK_SECRET='<운영자가 정한 값>'
./gradlew :migration:maskDump --args="--mongo-archive=/secure/snutt.archive.gz --ev-dump=/secure/snutt-ev.sql.gz --out=/secure/masked"
# 출력: /secure/masked/{mongo.archive.gz, ev.sql.gz, mask-report.md}
```

경로는 레포 루트 기준이다. 원본을 로컬 컨테이너에 복원해 제자리에서 바꾸고 같은 형식으로 다시 덤프한다. 끝나면 컨테이너 안 작업 DB를 지우고, 컨테이너는 JVM이 끝날 때 사라진다.

### 마스킹 규칙

- 결정적: HMAC-SHA256(비밀값, 영역, 원문)으로 글자를 바꾼다. 같은 원문은 같은 가명이 되므로 이관의 중복 판정(같은 아이디, 같은 토큰, 같은 FCM 토큰, 같은 제목)이 원본과 같게 돈다.
- 분포 보존: 글자 종류(숫자, 영문 대·소문자, 한글 음절)와 코드포인트 수를 유지하고, 공백·줄바꿈·구두점·`@`·`#`·`$`는 그대로 둔다. 길이, 빈 문자열, null, 필드 유무가 그대로라 null 비율이 같다.
- 충돌 회피(아래 표의 INJECTIVE): 유일 제약이나 중복 판정에 쓰이는 값은 서로 다른 원문이 같은 가명이 되지 않게 한다. 충돌하면 다시 만들고, 결과가 처리 순서와 무관하도록 정렬된 키 순서로 배정한다.
- 관계 보존: ObjectId, EV id, `user_id`(ObjectId 문자열), 참조, 날짜, 숫자, 불리언, enum은 바꾸지 않는다.
- 닫힌 목록: 검토하지 않은 Mongo 컬렉션이 있으면 멈춘다(`--drop-unknown-collections=true`면 지운다. 이관은 그 컬렉션을 읽지 않는다). 검토하지 않은 EV 테이블·컬럼이 있으면 멈춘다. `users`의 목록 밖 최상위 필드와 `credential` 하위 필드는 지운다. 다른 개인정보 컬렉션(`friend`, `userDevice`, `timetables`, `timetableTheme`, `notifications`, `diarySubmission`)의 목록 밖 최상위 필드와, 마스킹 필드에 문자열이 아닌 값이 든 경우는 지우지 않고 `mask-report.md`의 "검토하지 않은 필드"에 건수를 남긴다(v1 서버·이관이 읽는 필드를 잘못 지우지 않으려고). 이 목록이 비어 있지 않으면 내용을 보고 규칙을 넣은 뒤 다시 마스킹한다.
- 유일 인덱스: 덤프의 인덱스가 함께 복원되는데, 제자리 갱신 중에는 새 가명이 아직 바꾸지 않은 다른 문서의 원문과 같아질 수 있다(최종 결과는 충돌이 없다). 그래서 개인정보 컬렉션의 `_id` 외 인덱스를 내렸다가 마스킹이 끝나면 같은 정의로 다시 만든다.
- 표식: 마스킹본에는 `_rehearsal_masked` 컬렉션이 들어간다. 리허설 실행기는 이 표식이 없는 덤프를 거부한다(리포트에 원본 값이 찍히지 않게). 원문이 아닌 덤프(합성 데이터, parity 시드)에만 `--allow-unmasked=true`를 쓴다.
- 흔적: 작업 MySQL은 바이너리 로그를 끈 채 뜨고(`--disable-log-bin`, 복원·UPDATE의 원본 행 이미지가 남지 않게), 컨테이너 안 복사본은 실패해도 지운다. 컨테이너 자체는 JVM이 끝나면 사라진다.

### 마스킹 대상

코드의 목록은 `rehearsal/mask/MaskingPlan.kt`이고 이 표와 같아야 한다.

| 원본 | 필드 | 규칙 | 이유 |
|---|---|---|---|
| users | `email` | INJECTIVE | 개인 이메일. 대소문자 무시 유일(v1 인덱스, v2 `active_email`)과 중복 인증 정리에 쓰여 소문자 기준으로 충돌을 피하고 대소문자 모양은 원문을 따른다. 도메인은 주소 5개 미만이 쓰는 드문 도메인만 바꾼다(소속 추정 방지) |
| users | `nickname` | INJECTIVE | `#` 앞 이름만 바꾸고 4자리 태그는 둔다. v1 유일 인덱스와 v2 `(닉네임, 태그)` 유일 제약 |
| users | `credential.localId` | INJECTIVE | 로그인 아이디. 같은 아이디 정리(LOCAL_ID_DUPLICATE) |
| users | `credential.localPw` | 결정적 | bcrypt 해시(오프라인 대입 대상). 머리(`$2a$10$`, 알고리즘·비용)는 두고 솔트·해시 53자만 바꾼다. 형식은 맞지만 어떤 비밀번호와도 맞지 않는다. 마스킹본으로는 비밀번호 로그인을 할 수 없다 |
| users | `credential.fbId`, `appleSub`, `appleTransferSub`, `googleSub`, `kakaoSub` | INJECTIVE | 소셜 계정 id. `(provider, sub)` 유일과 중복 정리(SOCIAL_AUTH_DUPLICATE) |
| users | `credential.fbName` | 결정적 | Facebook 실명 |
| users | `credential.appleEmail`, `googleEmail`, `kakaoEmail` | INJECTIVE | 이메일. `users.email`과 같은 영역이라 같은 주소는 같게 바뀐다 |
| users | `credential.tempSeed` | 결정적 | 임시 인증 시드(비밀값). `tempDate`는 둔다 |
| users | `credentialHash` | INJECTIVE | 구 앱 액세스 토큰 그 자체. 같은 토큰 정리(LEGACY_TOKEN_AMBIGUOUS). 바뀐 값이 v1·v2 양쪽에서 토큰이 되므로 parity가 그대로 돈다 |
| users | `fcmKey` | 결정적 | 구 FCM 기기 그룹 키(푸시를 보낼 수 있다) |
| users | 목록 밖 최상위 필드 | 삭제 | 예전 버전이 남긴 필드는 내용을 모른다. 이관은 읽지 않는다 |
| userDevice | `fcmRegistrationId` | 결정적 | FCM 등록 토큰. 같은 토큰은 같게 바뀌어 중복 기기 정리가 같게 돈다. 100자가 넘어 충돌 확률은 무시한다 |
| userDevice | `deviceId` | 결정적 | 기기 식별자 |
| friend | `fromUserDisplayName`, `toUserDisplayName` | 결정적 | 친구에게 붙인 표시 이름(실명이 흔하다) |
| timetables | `title` | INJECTIVE | 사용자가 쓴 제목. 같은 학기 제목 중복 정리(TIMETABLE_TITLE_DUPLICATE)가 같게 돈다 |
| timetables | `lecture_list[].course_title`, `instructor`, `remark` | 결정적 | 직접 만든 강의(`lecture_id` 없음)의 내용과, 수강편람 강의를 사용자가 고친 값. 수강편람(같은 개설의 첫 문서, 이관이 비교하는 기준)과 같은 값은 둬서 override 분포를 지킨다 |
| timetables | 직접 만든 강의의 `class_time_json[].place` | 결정적 | 사용자가 쓴 장소. 수강편람 강의의 장소는 이관이 수업 시간 비교에 쓰므로 둔다 |
| timetableTheme | `name`, `publishInfo.publishName` | INJECTIVE | 사용자가 지은 테마 이름. 다운로드 테마가 원본 게시 이름과 같은지로 게시 스냅샷을 고르므로 같은 영역을 쓴다 |
| notifications | `title`, `message` (`user_id`가 있는 문서만) | 결정적 | 개인 알림 본문(친구 닉네임 등). 전체 공지는 둔다 |
| diarySubmission | `comment` | 결정적 | 강의 일기장 자유 서술 |
| EV `lecture_evaluation` | `content` | 결정적 | 강의평 본문 |
| EV `evaluation_report` | `content` | 결정적 | 신고 사유 |

바꾸지 않는 것과 이유:
- 수강편람(`lectures`, EV `lecture`·`semester_lecture`의 강사·과목명), 건물, 설정, 팝업, 일기장 질문, 검색 태그: 공개 정보다. 강사 이름은 수강편람에 공개되어 있고 과목 키(교과목번호, 강사)로 이관이 과목을 합치므로 바꾸면 이관 결과가 달라진다.
- id·참조·시각·숫자·enum: 관계와 분포를 보존해야 리허설이 의미가 있다. 가입 시각(`regDate`)과 최종 로그인 시각은 남으므로 마스킹본도 외부로 내보내지 않는다.
- `isEmailVerified`, `active`, `isAdmin`: 이관 규칙의 입력이다.

마스킹이 원본과 다르게 만들 수 있는 것(알고 쓰는 한계):
- 시간표 제목 안의 숫자도 바뀌므로 `"X"`와 `"X (2)"`처럼 이관의 번호 붙이기와 맞물리는 제목은 원본과 다르게 엮일 수 있다. 행 수는 같고 `TIMETABLE_TITLE_DUPLICATE` 건수만 조금 달라질 수 있다.
- 악센트·전각 문자는 한글로 바뀐다. v2의 `active_email`·FCM 토큰 유일 키는 MySQL 기본 콜레이션(`utf8mb4_0900_ai_ci`, 악센트·대소문자 무시)으로 비교하지만 이관의 중복 정리는 Kotlin `lowercase()`·정확히 같음으로 판정한다. 원본에 `é`/`e`만 다른 인증 이메일이 있으면 운영 이관은 유일 키 위반으로 실패하는데 마스킹본에서는 드러나지 않는다. 운영 전에 v1에서 직접 확인한다: `db.users.aggregate([{$match:{active:true,isEmailVerified:true}},{$group:{_id:{$toLower:"$email"},n:{$sum:1}}}], {collation:{locale:"en",strength:1}})` 같은 질의로 strength 1(악센트 무시)에서 겹치는 주소가 있는지 본다.

### 마스킹 결과 확인

`mask-report.md`에 필드별로 바꾼 값 수, 지운 필드, 지운 컬렉션이 나온다. 확인할 것:
- 예상한 컬렉션 외에 지운 것이 없는지, 지운 `users` 필드 목록에 의외의 것이 없는지.
- 원문이 남지 않았는지 표본으로 본다. 예: 운영자가 아는 테스트 계정 이메일 일부로 `zcat masked/mongo.archive.gz | grep -a -c '<문자열>'`, `zcat masked/ev.sql.gz | grep -c '<강의평 일부>'`가 0인지.

## 3. 실행

```bash
./gradlew :migration:rehearse -Drehearsal.mysql-buffer-pool=4G -Drehearsal.heap=6g \
  --args="--mongo-archive=/secure/masked/mongo.archive.gz --ev-dump=/secure/masked/ev.sql.gz --out=/secure/report"
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `--mongo-database` | `snutt` | 덤프 안 Mongo DB 이름 |
| `--sample-users` | 200 | 필드 대조할 표본 사용자 수 |
| `--sample-seed` | 20261128 | 표본 seed. 같은 입력·seed면 같은 표본 |
| `--parity-users` | 50 | parity 역할 파일(`roles-sample.yml`)에 넣을 사용자 수 |
| `--allow-unmasked` | false | 마스킹 표식 없는 덤프도 받는다(합성 데이터·parity 시드 전용) |

순서: v1 Mongo·EV 복원 → 빈 v2 MySQL에 Flyway(V1~최신) → 원본 색인·기대 건수 계산 → 이관 1회차(`MigrationRunner.migrate(truncate=false)`, ValidateStep 포함) → 검증 → 1회차 지문 → 비우지 않은 재실행(거부되어야 함) → 동기화 상태 행 남기기 → 이관 2회차(`--truncate`) → 2회차 지문 → 리포트.

종료 코드: 통과 0, 실패 1, 사용법 오류 2. 복원·검증 중 예외가 나도 그때까지의 시간·결과와 예외를 리포트에 남긴다. 출력:
- `rehearsal-report.md`: 사람이 읽는 리포트(아래 판정 기준 순서). `rehearsal-report.json`: 같은 내용(수치 비교·보관용).
- `roles-sample.yml`: parity용 표본 사용자(마스킹본의 v1 `users._id`).

리포트에는 표본 사용자 id와 불일치 값(마스킹된 값)이 들어간다. 마스킹본과 같은 등급으로 다룬다.

### 검증 항목

| 그룹 | 내용 | 코드 |
|---|---|---|
| 건수 대조 | v2 테이블별 행 수(와 일부 조건별 수)를 v1 원본에서 이관 규칙으로 따로 계산한 기대값과 맞춘다. 이관이 스스로 세는 값(`resolutions`, ValidateStep)을 쓰지 않으므로, 이관이 정리 건수를 잘못 세도 잡힌다. "v1 null → v2 null 보존"(`is_email_verified IS NULL` 수 = v1에서 필드가 Boolean이 아닌 계정 수)도 여기 있다 | `check/ExpectedCounts.kt` |
| 표본 대조 | seed 고정 표본 사용자마다 사용자 행(이메일, 닉네임·태그, 아이디, 인증 여부, 관리자, 최종 로그인), 소셜 연동, 시간표(학기, 제목, 대표, 강의 수), 시간표 강의마다 개설·앱이 보는 제목·강사·학점·수업 시간, 북마크·빈자리 알림 개설, 기기 토큰, 푸시 설정, 친구, 개인 알림 수, 일기장 수, 강의평·공감 id를 v1 원본과 필드 단위로 대조한다 | `check/SampleComparison.kt` |
| 체크섬 | id를 그대로 옮기는 EV 테이블(강의평, 공감, 신고, 검색 태그)의 모든 행을 기대 모양으로 바꿔 SHA-256 지문을 비교하고, 다르면 어긋난 id를 보여 준다 | `check/RowStreams.kt` |
| 불변식 | 모든 FK의 고아 행, 모든 유일 키 중복(information_schema에서 읽음), 도메인 규칙 20개(학기당 대표 하나, 테마 소유자, 팔레트 번호 범위, 직접 만든 강의 모양, 과목 집계·공감 수, 탈퇴 사용자의 활성 친구·기기·토큰 없음, deeplink ObjectId 없음, 수업 시간 범위 등). 시간이 겹치는 시간표 수는 v1 원본의 겹친 시간표 수와 같아야 한다(구 앱이 막지 않던 시기의 데이터가 있어 0이 기준이 아니다) | `check/Invariants.kt` |
| 멱등성·재실행 | 비우지 않은 재실행은 쓰기 전에 거부되는지, `--truncate` 재실행이 같은 결과(테이블별 행 수 + 내용 지문, 실행 시각으로 채우는 열 제외)와 같은 정리 집계를 내는지 | `check/Fingerprint.kt`, `Rehearsal.kt` |
| 소요 시간 | 단계별(복원, Flyway, 이관 단계 14개, 검증) ms | `RehearsalReport` |

### 증분(delta) 이관

지원하지 않는다. 코드로 확인한 근거:
- `MigrationRunner`는 `--truncate` 외의 인자를 거부하고, 대상 테이블이 비어 있지 않으면 `requireEmpty`로 쓰기 전에 멈춘다(리허설의 "비우지 않은 재실행" 항목이 이 동작을 고정한다).
- 대상 테이블은 단계가 쓰는 테이블과 수강편람 동기화 상태(`sugang_sync_pending_*`, `sugang_sync_outbox`, `sugang_sync_push_outbox`, `MigrationRunner.SYNC_STATE_TABLES`)다. 동기화 상태는 이관이 쓰지 않지만 이관 id를 외래 키 없이 가리키므로 재이관 때 함께 비운다. 나머지 테이블은 이유와 함께 `UNMANAGED_TABLES`에 둔다. 새 테이블을 만들면 둘 중 한 곳에 넣는다(`MigrationIntegrationTest`가 information_schema와 맞춰 보고, 리허설은 2회차 전에 동기화 상태를 남겨 `--truncate`가 비우는지 본다).
- v1 → v2 id 표(`MigrationContext`)는 실행 중 메모리에만 있고 v2 id는 매번 1부터 다시 매긴다. 이전 실행 이후 바뀐 문서만 골라낼 기준(수정 시각 필터, 체크포인트)도 없다.

따라서 전환은 "v1 쓰기 중단 → 전체 이관 → 전환"이고, 다운타임은 전체 이관 시간으로 정해진다(6장).

## 4. 결과 판정 기준

리포트 첫 줄이 "통과"이면 아래 1~5가 모두 만족된 것이다. 하나라도 어긋나면 "실패"와 실패 목록이 나온다.

1. 이관 1회차가 예외 없이 끝나고 ValidateStep(이관 자체 건수 검증)을 통과한다.
2. 건수 대조의 모든 행에서 기대 = 실제.
3. 표본 대조 불일치 0, 체크섬 4개 일치.
4. 불변식 위반 0(시간 겹침은 v1 원본 값과 같음).
5. 비우지 않은 재실행 거부, 2회차 성공, 1·2회차 지문과 정리 집계가 같음.

수치로 따로 판단할 것(도구가 실패로 치지 않는다):
- 이관이 손본 항목(`resolutions`)의 건수: 원본이 v2 제약을 어겨 정리한 건수다. 각 항목이 데이터 품질상 납득되는 규모인지 본다. 특히 `LEGACY_TOKEN_AMBIGUOUS`(전환 직후 강제 로그아웃되는 사용자 수), `LOCAL_ID_DUPLICATE`·`SOCIAL_AUTH_DUPLICATE`(로그인 수단을 잃는 계정 수), `VERIFIED_EMAIL_DUPLICATE`(강의평 접근을 다시 인증해야 하는 계정 수)는 공지 대상 규모라 기록해 둔다.
- 소요 시간: "이관 1회차"가 6장의 다운타임 예산 안에 드는지.

## 5. parity 연동

마스킹본을 v1 서버(snutt, snutt-ev 고정 커밋)와 이관된 v2에 각각 올리고 같은 읽기 요청을 비교한다. 하네스는 [parity.md](parity.md) "외부 데이터로 돌리기".

```bash
# 리허설이 뽑은 표본으로
./gradlew :parity:paritySnapshotTest -Dparity.heap=4g \
  -Dparity.source.mongo-archive=/secure/masked/mongo.archive.gz \
  -Dparity.source.ev-dump=/secure/masked/ev.sql.gz \
  -Dparity.source.roles=/secure/report/roles-sample.yml

# 또는 덤프에서 바로 N명을 뽑아 대량으로(roles와 함께 줘도 된다)
./gradlew :parity:paritySnapshotTest -Dparity.heap=4g \
  -Dparity.source.mongo-archive=… -Dparity.source.ev-dump=… \
  -Dparity.source.sample-users=500 -Dparity.source.sample-seed=20261128
```

- `sample-users`로 뽑은 사용자는 `rand-001`…로 붙고, roles에 이미 있는 사용자는 빼고 뽑는다.
- 표본은 구 토큰이 이관되는 사용자(활성, 로그인 수단 있음, 토큰을 다른 활성 계정과 공유하지 않음)에서만 뽑는다. 토큰은 마스킹된 `credentialHash`이고 양쪽에서 통한다.
- 사용자마다 앱 시작·시간표·알림·친구·테마·북마크·빈자리·강의평 읽기 흐름 14~16개 요청을 비교한다(`SnapshotParityTest`). 시간표가 있는 사용자는 "구 앱이 캐시한 v1 시간표 id" 요청도 비교한다: v2에는 그 사용자의 실제 v1 ObjectId를, v1에는 덤프에 없는 ObjectId를 보내 v2가 v1의 "없는 시간표" 응답과 같은 에러(404, 같은 errcode)를 주는지 본다.
- 판정: 허용 목록에 없는 차이가 나오면 실패로 표시된다. 스냅샷에서는 시드에서 시나리오 한정으로 허용한 이관 정리(중복 대표 시간표·제목 번호, 잘못된 사용자 색, 같은 아이디 계정의 로컬 아이디, 없는 사용자의 공감 수, 사라진 강의를 담은 북마크)가 차이로 나온다. 차이마다 리허설 리포트의 `resolutions`에 해당 항목이 있는지 대조한다. 설명되는 차이는 통과, 이관 규칙으로 설명되지 않는 차이가 0이어야 한다. 설명되지 않는 차이는 버그로 다룬다(7장).
- 리포트(`parity/build/reports/parity/paritySnapshotTest/`)에 응답 바디가 그대로 남는다. 마스킹본과 같은 등급으로 다룬다.

## 6. 실제 전환 당일과의 관계

리허설은 전환 당일 절차의 축소판이다. 운영 이관은 덤프가 아니라 운영 v1 Mongo·EV MySQL을 직접 읽어 운영 v2 MySQL에 쓰고(`migration` bootJar, `migration.mongo.uri`, `migration.old-ev.*`, `migration.target.*` 설정), 검증은 이관에 들어 있는 ValidateStep이 한다. 리허설 도구의 추가 검증(기대 건수, 표본, 불변식)은 리허설에서 미리 통과시켜 둔다.

### 순서 (앞 작업에서 넘어온 조건 포함)

1. 사전: 마지막 리허설이 통과했고, 소요 시간으로 다운타임 공지를 냈다.
2. v2 스키마: 운영 v2 DB에 Flyway V5(`is_email_verified` NULL 허용)까지 적용하고 NULL을 읽을 수 있는 새 api 파드 배포를 끝낸다. 그 뒤에 이관 도구를 실행한다. V4 스키마나 구 파드가 살아 있는 상태에서 NULL 행이 생기면 insert가 실패하거나 구 파드가 엔티티를 읽다 실패한다(`MigrationRunner` 주석과 같은 조건).
3. 쓰기 중단: v1 서버를 점검 모드로 바꾸거나 트래픽을 끊어 v1 Mongo·EV에 더 이상 쓰기가 없게 한다(증분 이관이 없으므로 이후 v1 쓰기는 잃는다). 배치(수강편람 동기화, 빈자리 알림)도 멈춘다.
4. 이관: `java -jar snutt-migration.jar` (처음이면 인자 없이, 다시 하면 `--truncate`). ValidateStep이 실패하면 예외로 끝난다.
5. 확인: 로그의 행 수 검증 통과, `resolutions` 건수가 리허설과 같은 규모인지, 스모크(표본 계정 몇 개의 앱 시작·시간표 조회).
6. 전환: 트래픽을 v2로 돌린다. 구 앱은 v1compat 경로를 쓴다.

### 다운타임 산정

증분 이관이 없으므로 다운타임 = 3~6의 합이다.

```
다운타임 ≈ T_중단확인 + T_이관 × (운영 행 수 / 스냅샷 행 수) × k + T_확인 + T_전환 + 여유
```

- 리허설은 EV 원본과 v2 대상을 같은 MySQL 서버(같은 버퍼 풀)에 둔다. 운영은 서로 다른 서버다.
- `T_이관`: 리허설 리포트의 "이관 1회차"(단계별 시간은 "이관 단계별"). 운영 크기 전체 스냅샷이면 행 수 비는 1이다. 단계 대부분이 행 수에 선형이다(스트리밍 + 배치 insert). `AggregateStep`의 과목 집계·삭제는 조인이라 선형보다 빨리 늘 수 있으니 그 단계는 따로 본다.
- `k`: 리허설 환경과 운영 환경의 차이 보정. 리허설은 로컬 디스크, 같은 서버 안의 원본·대상, 네트워크 왕복 없음이다. 운영은 OCI HeatWave MySQL(네트워크 너머)과 Mongo에서 읽는다. 첫 리허설 뒤에는 dev 환경에서 같은 스냅샷으로 한 번 이관해 보고 `k = dev 시간 / 리허설 시간`으로 잡는다. 그 전에는 보수적으로 2~3을 쓴다.
- 리허설은 두 번 이상 돌려 큰 값을 쓴다. 메모리(힙)가 부족하면 GC로 시간이 크게 늘어나니 리허설 JVM 힙은 운영 이관 JVM보다 크게 둔다(리허설 JVM은 이관 컨텍스트에 더해 원본 색인을 든다. 1회차 컨텍스트는 2회차 전에 놓는다). 리포트 입력 절에 최대 힙이 찍힌다.
- 불변식의 시간 겹침 SQL은 모든 수업 시간을 펼쳐 자기 조인한다. 운영 크기에서 오래 걸리면 "검증: 불변식" 시간을 보고 판단한다(이관 시간에는 들어가지 않는다).
- 여유: 실패 시 `--truncate`로 한 번 더 돌릴 시간(=T_이관)을 넣을지 결정한다. 넣지 않으면 실패는 곧 롤백이다.

### 롤백 기준

이관 중이나 직후(트래픽 전환 전)에 다음 중 하나면 v2로 넘기지 않고 v1 쓰기를 다시 연다. v1 데이터는 3단계 이후 바뀌지 않았으므로 그대로 돌아간다.
- 이관 단계 예외, ValidateStep 실패.
- `resolutions` 건수가 마지막 리허설과 크게 다름(덤프 시점 이후 데이터 변화로 설명되지 않음).
- 스모크 실패, 다운타임 예산 초과가 확실함.

트래픽 전환 뒤에는 v2에 새 쓰기가 생기므로 v1로 되돌리면 그 쓰기를 잃는다. 전환 뒤 롤백은 v2 쓰기를 v1로 옮기는 도구가 없다는 전제에서, 전환 직후 관찰 시간(예: 30분) 안에 치명적 오류율이 보일 때만 한다.

### 전환 영향

- v1 ObjectId ↔ v2 Long id 표가 이관에 남지 않는다. 구 앱이 캐시한 시간표 id(마지막으로 본 시간표, 위젯, 딥링크)는 전환 뒤 `GET /v1/tables/{id}`에서 404(v1의 "없는 시간표"와 같은 errcode), 그 밖의 경로에서는 숫자가 아닌 id라 400 40001이 된다([v1-compat-audit.md](v1-compat-audit.md) "마이그레이션 관련 관찰"). 리허설의 parity 스냅샷 모드가 표본 사용자마다 이 응답을 확인한다. 앱이 목록을 다시 받으면 새 id를 쓰게 된다.
- 알림 deeplink 안의 v1 id는 이관이 v2 id로 바꾸고, 대상을 못 찾으면 deeplink를 지운다(불변식이 남은 ObjectId 0을 확인한다).
- 같은 토큰을 공유한 계정(`LEGACY_TOKEN_AMBIGUOUS`)은 전환 직후 다시 로그인해야 한다. 건수는 리허설 리포트에 있다.
- 친구 초대 링크(Redis), 진행 중인 강의평 커서는 이관되지 않는다([v1-compat-audit.md](v1-compat-audit.md)).

### 이미 이관을 실행한 환경(dev 등)의 `is_email_verified`

V5 이전에 v1 → v2 사용자 이관을 실행한 환경에서는 v1에 `isEmailVerified` 필드가 없던 계정이 `false`로 들어가 있다. V5는 backfill하지 않는다. 둘 중 하나로 고친다.

1. 재이관(권장): V5 적용과 새 파드 배포 뒤 `java -jar snutt-migration.jar --truncate`. 이관 이후 그 환경에서 생긴 v2 데이터는 사라진다.
2. Mongo 기준 1회 backfill: v1 Mongo에서 대상 계정을 뽑아 v2에서 `(닉네임, 태그, 활성)`으로 찾아 고친다(v1 닉네임은 유일하고 이관이 이름·태그를 그대로 옮긴다). 이관 뒤 닉네임을 바꾼 계정은 빠지므로 출력 건수와 갱신 건수를 비교한다.

```js
// mongosh, v1 DB. 이관 코드와 같은 기준: Boolean이 아니면(필드 없음, null) null로 옮긴다.
db.users.find({ isEmailVerified: { $not: { $type: "bool" } } }, { nickname: 1, active: 1 }).forEach(u => {
  const i = u.nickname.lastIndexOf("#");
  const name = u.nickname.substring(0, i).replace(/'/g, "''");
  print(`UPDATE \`user\` SET is_email_verified = NULL WHERE nickname = '${name}' AND nickname_tag = '${u.nickname.substring(i + 1)}' AND active = ${u.active === true} AND is_email_verified = FALSE;`);
});
```

`is_email_verified = FALSE` 조건은 이관 뒤 v2에서 인증을 마친 계정을 건드리지 않게 한다. 고친 뒤 리허설의 "user.is_email_verified NULL" 기대값(v1에서 Boolean이 아닌 계정 수)과 그 환경의 `SELECT COUNT(*) FROM user WHERE is_email_verified IS NULL`을 비교한다.

## 7. 실패 시 대응

| 실패 | 먼저 볼 것 | 대응 |
|---|---|---|
| 마스킹이 "검토하지 않은 컬렉션/테이블/컬럼"으로 멈춤 | 메시지의 이름 | 내용을 보고 개인정보가 있으면 `MaskingPlan`에 규칙을 넣고 이 문서 표를 고친다. 이관이 읽지 않는 컬렉션이면 `--drop-unknown-collections=true` |
| 이관 1회차 예외(ValidateStep 아님) | 예외 메시지의 문서 id(`requireStr` 등은 `_id`를 찍는다) | 원본이 이관이 가정한 모양과 다르다. 해당 문서를 마스킹본에서 보고, 레거시 데이터 정리 규칙을 이관에 추가할지 결정한다(이관 코드 변경은 리뷰 대상) |
| ValidateStep 실패 | 메시지의 테이블과 원본/대상/제외 수 | 이관이 정리한 건수를 세지 않았거나 잘못 셌다. 건수 대조 표의 같은 테이블과 비교하면 어느 쪽이 맞는지 나온다 |
| 건수 불일치 | 그 행의 "원본과 다른 이유" | 이관 규칙과 기대값 계산 중 어느 쪽이 틀렸는지 코드로 본다. 이관이 틀렸으면 버그, 기대값 계산이 규칙을 잘못 옮겼으면 `ExpectedCounts`를 고친다 |
| 표본 불일치 | 상세의 `where.field: v1=…, v2=…` | 같은 사용자의 v1 문서와 v2 행을 직접 조회해 원인을 찾는다 |
| 체크섬 불일치 | 어긋난 id 예시 | EV 원본 행과 v2 행을 직접 비교한다 |
| 불변식 위반 | 상세의 SQL | SQL로 위반 행을 뽑아 원본 문서를 찾는다. 원본이 이미 어겼다면(레거시) 정리 규칙을 추가할지, v2에서 허용할지 결정한다 |
| 멱등성 불일치 | 지문이 다른 테이블 | 실행 시각 외의 비결정 값(HashMap 순회 순서 등)이 이관에 섞였다. 그 테이블을 쓰는 단계를 본다 |
| 시간 초과 | "이관 단계별"에서 오래 걸린 단계 | 버퍼 풀·힙을 늘려 다시 재고, 그래도 예산을 넘으면 그 단계의 쿼리·배치 크기를 개선한다 |

리허설에서 실패를 고친 뒤에는 같은 마스킹본으로 다시 돌려 통과를 확인한다. 버그가 원인이면 합성 덤프(`SyntheticLegacyData`)나 `MigrationIntegrationTest`에 그 모양의 데이터를 넣어 회귀를 막는다.

## 8. 도구 자체 검증 (`rehearsalTest`)

`./gradlew :migration:rehearsalTest`가 `SyntheticLegacyData`(seed 고정, 사용자 60명 규모, 레거시 이상 데이터 포함)를 덤프로 만들어 실제 CLI와 같은 경로를 돈다.

- 마스킹한 덤프로 리허설이 모든 검증을 통과하고, 합성 데이터가 이관의 정리 규칙 22개를 실제로 거친다(검증이 빈 데이터로 통과하지 않게).
- 원본 덤프에 있던 개인정보 흉내 문자열이 마스킹본(Mongo 아카이브, EV SQL)에 하나도 남지 않는다.
- 마스킹 전후로 이관 결과(건수 대조의 실제 값 전부)와 정리 집계가 같다. 즉 마스킹이 이관이 보는 관계·중복·분포를 바꾸지 않는다.
- 같은 비밀값이면 결과가 같고, 마스킹 필드의 길이·null 분포와 id가 그대로다.
- 검토하지 않은 컬렉션이 있으면 마스킹이 쓰기 전에 멈춘다.
- 마스킹 표식이 없는 덤프는 리허설이 거부하고, 거부 이유를 리포트에 남긴다.

parity 경로는 parity 시드를 내보내(`-Dparity.dump-seed-to`) `maskDump` → `rehearse` → `paritySnapshotTest -Dparity.source.sample-users=6`으로 확인했다(작업 M 보고서).
