# perf 데이터 분포

`PerfDataGenerator`(`core/src/testFixtures/.../core/test/perf/`)가 따르는 운영 분포와 그 근거다. 생성기 값을 바꾸면 이 문서와 `PerfDataProfileTest`를 같이 고친다.

## 출처

BigQuery 프로젝트 `snutt-792f1`의 `snutt_raw_v1` 데이터셋(2026-11-27 적재한 v1 원본 스냅숏)에서 **집계값만** 조회했다. 개인 단위 행이나 PII는 조회하지 않았다.
테이블: `users`, `timetables`(Mongo 시간표, `lecture_list` JSON 배열), `friend`, `ev_lecture`(EV 과목), `ev_semester_lecture`, `ev_lecture_evaluation`, `ev_evaluation_like`.

알림·강의 일기장·리마인더·관심강좌·빈자리 알림·테마 공유는 이 데이터셋에 없다(v1 Mongo의 다른 컬렉션이거나 v2 신규 기능). 이것들은 추정값이고 아래 표에 **추정**으로 표시했다.

## 조회한 쿼리와 결과

### 테이블 크기

```sql
SELECT
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.users`) users,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.timetables`) timetables,
 (SELECT COUNT(DISTINCT user_id) FROM `snutt-792f1.snutt_raw_v1.timetables`) users_with_tt,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.friend`) friends,
 (SELECT COUNTIF(isAccepted) FROM `snutt-792f1.snutt_raw_v1.friend`) friends_accepted,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.ev_lecture`) ev_courses,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.ev_semester_lecture`) ev_semester_lectures,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.ev_lecture_evaluation`) evaluations,
 (SELECT COUNTIF(is_hidden) FROM `snutt-792f1.snutt_raw_v1.ev_lecture_evaluation`) evaluations_hidden,
 (SELECT COUNT(*) FROM `snutt-792f1.snutt_raw_v1.ev_evaluation_like`) likes,
 (SELECT COUNT(DISTINCT CONCAT(CAST(year AS STRING),'-',CAST(semester AS STRING))) FROM `snutt-792f1.snutt_raw_v1.timetables`) semesters
```

| users | timetables | 시간표 있는 사용자 | friend | 수락됨 | EV 과목 | EV 학기 강의 | 강의평 | 숨김 | 공감 | 학기 수 |
|---|---|---|---|---|---|---|---|---|---|---|
| 219,981 | 754,639 | 223,354 | 7,425 | 7,388 | 65,168 | 216,045 | 16,613 | 61 | 934 | 52 |

(시간표 있는 사용자가 users보다 많은 것은 탈퇴 사용자의 시간표가 남아 있어서다.)

### 시간표

```sql
WITH t AS (SELECT user_id, year, semester, ARRAY_LENGTH(JSON_QUERY_ARRAY(lecture_list)) n FROM `snutt-792f1.snutt_raw_v1.timetables`),
u AS (SELECT user_id, COUNT(*) c FROM t GROUP BY user_id),
us AS (SELECT user_id, year, semester, COUNT(*) c FROM t GROUP BY 1,2,3)
SELECT 'tt_per_user' metric, APPROX_QUANTILES(c, 100)[OFFSET(50)] p50, APPROX_QUANTILES(c,100)[OFFSET(90)] p90, APPROX_QUANTILES(c,100)[OFFSET(99)] p99, MAX(c) mx, AVG(c) avg FROM u
UNION ALL SELECT 'tt_per_user_semester', APPROX_QUANTILES(c,100)[OFFSET(50)], APPROX_QUANTILES(c,100)[OFFSET(90)], APPROX_QUANTILES(c,100)[OFFSET(99)], MAX(c), AVG(c) FROM us
UNION ALL SELECT 'lectures_per_tt', APPROX_QUANTILES(n,100)[OFFSET(50)], APPROX_QUANTILES(n,100)[OFFSET(90)], APPROX_QUANTILES(n,100)[OFFSET(99)], MAX(n), AVG(n) FROM t
```

| 지표 | p50 | p90 | p99 | max | 평균 |
|---|---|---|---|---|---|
| 사용자당 시간표 | 1 | 9 | 30 | 782 | 3.38 |
| 사용자·학기당 시간표 | 1 | 2 | 5 | 773 | 1.24 |
| 시간표당 강의 | 2 | 8 | 11 | 47 | 3.12 |

```sql
SELECT year, semester, COUNT(*) tt FROM `snutt-792f1.snutt_raw_v1.timetables` GROUP BY 1,2 ORDER BY 1 DESC, 2 DESC LIMIT 8
```

| 학기 | 2026-4 | 2026-3 | 2026-2 | 2026-1 | 2025-4 | 2025-3 | 2025-2 | 2025-1 |
|---|---|---|---|---|---|---|---|---|
| 시간표 | 4,686 | 24,506 | 7,079 | 30,025 | 6,311 | 26,880 | 7,523 | 30,182 |

### 강의별 수강자 쏠림 (2025-2학기)

```sql
WITH t AS (SELECT year, semester, JSON_VALUE(l, '$.lecture_id') lid FROM `snutt-792f1.snutt_raw_v1.timetables`, UNNEST(JSON_QUERY_ARRAY(lecture_list)) l WHERE year = 2025 AND semester = 3),
lc AS (SELECT lid, COUNT(*) n FROM t WHERE lid IS NOT NULL GROUP BY 1),
tot AS (SELECT COUNT(*) c, COUNTIF(lid IS NULL) custom FROM t)
SELECT (SELECT c FROM tot) refs, (SELECT custom FROM tot) custom_refs, COUNT(*) distinct_lectures, APPROX_QUANTILES(n,100)[OFFSET(50)] p50, APPROX_QUANTILES(n,100)[OFFSET(90)] p90, APPROX_QUANTILES(n,100)[OFFSET(99)] p99, MAX(n) mx FROM lc
```

| 시간표 강의 | custom(lecture_id 없음) | 담긴 강의 수 | 강의당 p50 | p90 | p99 | max |
|---|---|---|---|---|---|---|
| 141,803 | 7,661 (5.4%) | 5,732 | 12 | 57 | 163 | 709 |

### 강의평

```sql
WITH ev AS (SELECT e.id, sl.lecture_id course_id FROM `snutt-792f1.snutt_raw_v1.ev_lecture_evaluation` e JOIN `snutt-792f1.snutt_raw_v1.ev_semester_lecture` sl ON sl.id = e.semester_lecture_id),
c AS (SELECT course_id, COUNT(*) n FROM ev GROUP BY 1),
s AS (SELECT lecture_id, COUNT(*) n FROM `snutt-792f1.snutt_raw_v1.ev_semester_lecture` GROUP BY 1),
ue AS (SELECT user_id, COUNT(*) n FROM `snutt-792f1.snutt_raw_v1.ev_lecture_evaluation` WHERE user_id IS NOT NULL GROUP BY 1)
SELECT 'evals_per_evaluated_course' m, COUNT(*) grp, APPROX_QUANTILES(n,100)[OFFSET(50)] p50, APPROX_QUANTILES(n,100)[OFFSET(90)] p90, APPROX_QUANTILES(n,100)[OFFSET(99)] p99, MAX(n) mx FROM c
UNION ALL SELECT 'semester_lectures_per_course', COUNT(*), APPROX_QUANTILES(n,100)[OFFSET(50)], APPROX_QUANTILES(n,100)[OFFSET(90)], APPROX_QUANTILES(n,100)[OFFSET(99)], MAX(n) FROM s
UNION ALL SELECT 'evals_per_author', COUNT(*), APPROX_QUANTILES(n,100)[OFFSET(50)], APPROX_QUANTILES(n,100)[OFFSET(90)], APPROX_QUANTILES(n,100)[OFFSET(99)], MAX(n) FROM ue

SELECT COUNTIF(from_snuev) from_snuev, COUNTIF(user_id IS NULL) null_user, COUNT(*) total,
       APPROX_QUANTILES(like_count,100)[OFFSET(99)] like_p99, MAX(like_count) like_max
FROM `snutt-792f1.snutt_raw_v1.ev_lecture_evaluation`
```

| 지표 | 그룹 수 | p50 | p90 | p99 | max |
|---|---|---|---|---|---|
| 강의평 있는 과목당 강의평 | 2,993 | 2 | 12 | 52 | 212 |
| 과목당 학기 강의 | 65,168 | 2 | 8 | 22 | 50 |
| 작성자당 강의평 | 410 | 1 | 6 | 13 | 15,607 |

- from_snuev 15,607 / 16,613 (94%). 작성자 max 15,607은 snuev 이관 계정 하나다. user_id NULL은 0.
- like_count p99 2, max 23.

### 친구

```sql
WITH f AS (SELECT fromUserId u FROM `snutt-792f1.snutt_raw_v1.friend` UNION ALL SELECT toUserId FROM `snutt-792f1.snutt_raw_v1.friend`),
c AS (SELECT u, COUNT(*) n FROM f GROUP BY 1)
SELECT COUNT(*) users_with_friend, APPROX_QUANTILES(n,100)[OFFSET(50)] p50, APPROX_QUANTILES(n,100)[OFFSET(90)] p90, APPROX_QUANTILES(n,100)[OFFSET(99)] p99, MAX(n) mx FROM c
```

| 친구 있는 사용자 | p50 | p90 | p99 | max |
|---|---|---|---|---|
| 5,024 | 1 | 7 | 19 | 115 |

## 생성기 규모

사용자에 비례하는 테이블은 1/10로 줄였다. 강의 검색은 학기 단위로 돌기 때문에 **학기당 강의 수는 줄이지 않았다**(옵티마이저가 학기 인덱스를 고를지가 이 크기에 달려 있다). 강의평은 테이블 자체가 작아 운영 값을 그대로 쓴다.

| 테이블 | 운영 | 생성 | 규칙 |
|---|---|---|---|
| user | 219,981 | 22,000 | 1/10 |
| timetable | 754,639 | 약 81,500 | 사용자 학기 수 분포 × 학기당 시간표 수(1: 82%, 2: 13%, 3+: 5%) |
| timetable_lecture | 약 2.35M | 약 286,000 | 시간표당 p50 2·p90 8·max 47, custom 5% |
| 학기 | 52 | 23 (2021-1 ~ 2026-2학기) | 정규학기 가중치 10, 여름 1.5, 겨울 1 |
| course | 65,168 | 13,000 | 학기 수를 줄인 만큼 줄임. 과목당 개설 학기 1개 35%, 2개 20%, 3~5개 20%, 6~10개 15%, 11개+ 10% |
| lecture | 약 216,000(EV 기준) | 약 80,000 | 정규학기당 약 5,000~6,000개 유지. 분반 1개 90%, 2~4개 8%, 5~15개 2% |
| 강의별 쏠림 | p50 12, max 709 | Zipf(s=0.5) | 1/10 규모에서 max 약 70이 되도록 |
| evaluation | 16,613 | 16,613 | 과목 2,993개에 로그정규(μ=ln 2, σ=1.4, 상한 212). snuev 계정 94% |
| evaluation_like | 934 | 934 | 무작위 |
| friend | 7,425 | 약 875 | 1/10 + 극단 사용자 1명(친구 115, 대기 10) |
| notification | 없음 | 약 54,600 | **추정**: 전체 공지 300개, 사용자 30%가 평균 8개, 극단 사용자 2,000개 |
| diary_submission | 없음 | 약 9,100 | **추정**: 사용자 10%가 최근 두 학기에 1~7개, 극단 사용자 300개, 제출당 답변 3개 |
| timetable_lecture_reminder | 없음 | 약 2,200 | **추정**: 이번 학기 대표 시간표 강의의 10% |
| bookmark_lecture | 없음 | 약 22,000 | **추정**: 사용자 20%가 최근 세 학기 강의 평균 5개 |
| vacancy_notification | 없음 | 약 3,400 | **추정**: 사용자 5%가 이번 학기 인기 강의 1~5개 |
| published_theme | 없음 | 2,200 | **추정**: 사용자 수의 1/10. 200개일 때는 옵티마이저가 테이블 스캔을 골랐다 |

극단값은 운영 최댓값을 그대로 넣는다: 시간표 773개(한 학기), 친구 115명, 과목 강의평 212개, snuev 계정 강의평 약 15,600개. `PerfDataset`이 이 id들을 노출한다.

## 생성 시간

로컬(Testcontainers `mysql:26.7`) 측정: Flyway migration 약 2.8초, 데이터 생성과 ANALYZE 약 22초(약 83만 행). perf JVM에서 한 번만 돈다.
