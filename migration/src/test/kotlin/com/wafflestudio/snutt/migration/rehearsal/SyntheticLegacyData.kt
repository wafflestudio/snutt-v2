package com.wafflestudio.snutt.migration.rehearsal

import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.random.Random

/**
 * 리허설 파이프라인 검증용 합성 v1 데이터(작은 운영 스냅샷 흉내). seed가 같으면 ObjectId까지 같은 데이터가 나온다.
 *
 * 문서 모양은 migration 단계가 읽는 필드(step 패키지)와 v1 데이터 클래스를 따른다. migration의 정리 규칙(ResolutionReasons)이
 * 모두 한 번 이상 걸리도록 레거시 이상 데이터를 섞는다: 같은 아이디·인증 이메일(대소문자만 다름)·소셜 계정·토큰의 활성 계정,
 * isEmailVerified 없는 계정, 탈퇴 계정의 친구·기기, 같은 개설의 강의 문서 둘, 공백만 다른 EV 과목, 주인 없는 시간표·테마·알림,
 * 같은 학기 같은 제목·대표 시간표 둘, 범위 밖 팔레트 번호, 잘못된 색, 사라진 강의를 담은 북마크, 사용자가 없는 공감·신고 등.
 *
 * [sensitive]에는 마스킹 뒤 덤프에 남으면 안 되는 원문(개인정보 흉내)을 모은다.
 */
class SyntheticLegacyData(
    seed: Long,
    private val userCount: Int = 60,
) {
    private val random = Random(seed)
    private val base: Instant = Instant.parse("2026-03-02T00:00:00Z")
    val sensitive = LinkedHashSet<String>()

    private val docs = LinkedHashMap<String, MutableList<Document>>()

    private fun oid() = ObjectId(random.nextBytes(12))

    private fun at(hours: Long): Date = Date.from(base.plus(Duration.ofHours(hours)))

    private fun add(
        collection: String,
        document: Document,
    ) {
        docs.getOrPut(collection) { mutableListOf() } += document
    }

    private fun secret(value: String): String = value.also { if (it.length >= 6) sensitive += it }

    private val surnames = listOf("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임")
    private val givens = listOf("민준", "서연", "도윤", "하은", "지호", "수아", "예준", "지유", "시우", "서윤", "와플", "정우")

    private fun koreanName() = surnames.random(random) + givens.random(random)

    private fun sentence(words: Int): String =
        (1..words).joinToString(" ") { listOf("수업", "과제가", "많고", "교수님이", "친절하다", "시험은", "어렵다", "추천", "Good", "A+").random(random) }

    private fun alnum(length: Int) =
        (1..length)
            .map {
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random(random)
            }.joinToString("")

    class User(
        val id: ObjectId,
        val active: Boolean,
    )

    class Lecture(
        val id: ObjectId,
        val year: Int,
        val semester: Int,
        val courseNumber: String,
        val title: String,
        val instructor: String?,
        val times: List<Document>,
    )

    val users = mutableListOf<User>()
    private val lectures = mutableListOf<Lecture>()
    private lateinit var duplicateLecture: Lecture

    fun write(
        mongo: MongoDatabase,
        ev: JdbcTemplate,
    ) {
        catalog()
        lectures()
        users()
        themesAndTimetables()
        userData()
        notifications()
        diary()
        add("feedbacks", Document("_id", oid()).append("email", secret("feedback-writer@example.com")).append("message", "문의"))
        docs.forEach { (collection, list) -> mongo.getCollection(collection).insertMany(list) }
        writeEv(ev)
    }

    private fun catalog() {
        listOf(2025 to 3, 2026 to 1).forEach { (year, semester) ->
            add("coursebooks", Document("_id", oid()).append("year", year).append("semester", semester).append("updated_at", at(-100)))
        }
        add(
            "lectureBuilding",
            Document("_id", oid())
                .append("buildingNumber", "301")
                .append("buildingNameKor", "제1공학관")
                .append("buildingNameEng", "Engineering Building 1")
                .append("campus", "GWANAK")
                .append("locationInDMS", Document("latitude", 37.45).append("longitude", 126.95))
                .append("locationInDecimal", Document("latitude", 487_350.0).append("longitude", 1_104_465.0)),
        )
        add(
            "semesterRegistrationPeriod",
            Document("_id", oid())
                .append("year", 2026)
                .append("semester", 1)
                .append(
                    "registrationPeriods",
                    listOf(
                        Document("date", at(24))
                            .append("vacantSeatRegistrationTimes", listOf(Document("startMinute", 540).append("endMinute", 600)))
                            .append("phase", "REGULAR"),
                    ),
                ),
        )
        listOf("vacancy_notification" to """{"visible":true}""", "theme_market" to """{"enabled":true}""").forEach { (name, value) ->
            add(
                "clientConfig",
                Document("_id", oid())
                    .append("name", name)
                    .append("value", value)
                    .append("minIosVersion", "3.0.0")
                    .append("createdAt", at(-50))
                    .append("updatedAt", at(-50)),
            )
        }
        add(
            "popup",
            Document("_id", oid())
                .append("key", "popup-2026")
                .append("imageOriginUri", "https://example.com/popup.png")
                .append("hiddenDays", 7)
                .append("createdAt", at(-10))
                .append("updatedAt", at(-10)),
        )
    }

    private fun time(
        day: Int,
        start: Int,
    ) = Document("day", day).append("place", "301-${100 + day}").append("startMinute", start).append("endMinute", start + 75)

    private fun lectures() {
        val instructors = listOf("김교수", "Chenglin Fan", "", null, "이강사")
        listOf(2025 to 3, 2026 to 1).forEach { (year, semester) ->
            (1..14).forEach { i ->
                val instructor = instructors[i % instructors.size]
                val times = listOf(time(i % 5, 540 + 90 * (i % 6)), time((i + 2) % 5, 540 + 90 * (i % 6)))
                lectures += lecture(year, semester, "M1522.${i.toString().padStart(6, '0')}", "001", "과목 $i", instructor, times)
            }
        }
        // ★ 같은 개설의 강의 문서 둘(두 번째는 강사가 다르다)
        val original = lectures.first()
        duplicateLecture = lecture(original.year, original.semester, original.courseNumber, "001", original.title, "다른강사", original.times)
    }

    private fun lecture(
        year: Int,
        semester: Int,
        courseNumber: String,
        lectureNumber: String,
        title: String,
        instructor: String?,
        times: List<Document>,
    ): Lecture {
        val id = oid()
        val doc =
            Document("_id", id)
                .append("year", year)
                .append("semester", semester)
                .append("course_number", courseNumber)
                .append("lecture_number", lectureNumber)
                .append("course_title", title)
                .append("department", "컴퓨터공학부")
                .append("academic_year", if (year == 2026) "3" else "3학년")
                .append("academic_year_en", if (year == 2026) "3" else "Year 3")
                .append("classification", "전선")
                .append("credit", 3)
                .append("quota", 80)
                .append("registrationCount", random.nextInt(0, 80))
                .append("wasFull", random.nextBoolean())
                .append("remark", if (random.nextBoolean()) "" else "영어강의")
                .append("class_time_json", times)
        if (instructor != null) doc.append("instructor", instructor)
        add("lectures", doc)
        return Lecture(id, year, semester, courseNumber, title, instructor, times)
    }

    private fun users() {
        val sharedToken = alnum(40)
        repeat(userCount) { i ->
            val id = oid()
            val active = i % 10 != 9
            val name = koreanName()
            val email =
                when {
                    i == 3 -> "Dup.Verified@SNU.ac.kr" // ★ 대소문자만 다른 인증 이메일
                    i == 4 -> "dup.verified@snu.ac.kr"
                    i == 5 -> secret("lab-member$i@waffle-lab.kr") // 드문 도메인
                    i % 7 == 6 -> null
                    else -> "user${i}_${alnum(4)}@${if (i % 2 == 0) "snu.ac.kr" else "gmail.com"}"
                }?.also(::secret)
            val credential = Document()
            when {
                i == 1 || i == 2 -> credential.append("localId", "duplicate-id") // ★ 같은 아이디의 활성 계정 둘
                i % 4 == 0 -> credential.append("localId", secret("local${i}id${alnum(3)}"))
                else -> Unit
            }
            if (credential.containsKey("localId")) credential.append("localPw", secret("\$2a\$10\$" + alnum(53)))
            if (i % 5 == 1 || i == 7 || i == 8) {
                credential.append("fbId", if (i == 7 || i == 8) "fb-shared-0001" else secret("fb${alnum(12)}"))
                credential.append("fbName", secret("${koreanName()} Facebook"))
            }
            if (i % 6 == 2) {
                credential.append("appleSub", secret("001234.${alnum(32)}.0123"))
                credential.append("appleEmail", secret("${alnum(10).lowercase()}@privaterelay.appleid.com"))
                credential.append("appleTransferSub", secret("transfer.${alnum(20)}"))
            }
            if (i % 6 == 3) {
                credential.append("googleSub", secret("1${(1..20).map { random.nextInt(10) }.joinToString("")}"))
                credential.append("googleEmail", email ?: secret("g$i.${alnum(5)}@gmail.com"))
            }
            if (i % 6 == 5) {
                credential.append("kakaoSub", secret("${random.nextLong(1_000_000_000L, 9_999_999_999L)}"))
                credential.append("kakaoEmail", secret("kakao$i${alnum(3)}@kakao.com"))
            }
            if (i == 11) credential.append("tempDate", "2020-01-01").append("tempSeed", secret("temp-seed-${alnum(16)}"))
            val doc =
                Document("_id", id)
                    .append("nickname", secret("$name${if (i == 12) "" else i}#${(1000 + i * 37 % 9000).toString().padStart(4, '0')}"))
                    .append("credential", credential)
                    .append("credentialHash", if (i == 16 || i == 17) sharedToken else secret(alnum(40))) // ★ 같은 토큰 둘
                    .append("active", active)
                    .append("isAdmin", i == 0)
                    .append("regDate", at(-1000L + i))
                    .append("lastLoginTimestamp", base.toEpochMilli() + (if (i == 2) 5_000 else i) * 1_000L)
                    .append("notificationCheckedAt", at(i.toLong()))
            if (email != null) doc.append("email", email)
            if (i % 5 != 4) doc.append("isEmailVerified", i % 3 != 0 || i == 3) // ★ 일부는 필드 없음
            if (i == 4) doc["isEmailVerified"] = true
            if (i % 8 == 0) doc.append("fcmKey", secret("fcm-group-${alnum(30)}"))
            if (i == 15) doc.append("phoneNumber", secret("010-1234-5678")) // ★ 예전 버전이 남긴 필드
            add("users", doc)
            users += User(id, active)
        }
    }

    private val activeUsers get() = users.filter { it.active }

    private fun themesAndTimetables() {
        val owner = activeUsers[0]
        val publishedId = oid()
        val palette = listOf(Document("bg", "#1BD0C8").append("fg", "#FFFFFF"), Document("bg", "#E54459").append("fg", "#FFFFFF"))
        add(
            "timetableTheme",
            Document("_id", publishedId)
                .append("userId", owner.id)
                .append("name", secret("${koreanName()}의 봄 테마"))
                .append("colors", palette)
                .append("isCustom", true)
                .append("status", "PUBLISHED")
                .append("publishInfo", Document("publishName", "공개 봄 테마").append("authorAnonymous", false).append("downloads", 3))
                .append("createdAt", at(-200))
                .append("updatedAt", at(-200)),
        )
        val themesByUser = HashMap<ObjectId, ObjectId>()
        activeUsers.drop(1).take(6).forEachIndexed { index, user ->
            val id = oid()
            val renamed = index == 2
            add(
                "timetableTheme",
                Document("_id", id)
                    .append("userId", user.id)
                    .append("name", if (renamed) "내 마음대로 바꾼 이름" else "공개 봄 테마")
                    .append("colors", palette)
                    .append("isCustom", true)
                    .append("status", "DOWNLOADED")
                    .append("origin", Document("originId", publishedId.toHexString()).append("authorId", owner.id.toHexString()))
                    .append("createdAt", at(-100L + index))
                    .append("updatedAt", at(-100L + index)),
            )
            themesByUser[user.id] = id
            if (index == 0) {
                // ★ 같은 게시 테마를 두 번 받음
                add(
                    "timetableTheme",
                    Document("_id", oid())
                        .append("userId", user.id)
                        .append("name", "공개 봄 테마")
                        .append("colors", palette)
                        .append("isCustom", true)
                        .append("status", "DOWNLOADED")
                        .append("origin", Document("originId", publishedId.toHexString()).append("authorId", owner.id.toHexString()))
                        .append("createdAt", at(-90))
                        .append("updatedAt", at(-90)),
                )
            }
        }
        // ★ 주인 없는 테마
        add(
            "timetableTheme",
            Document("_id", oid())
                .append("userId", oid())
                .append("name", "주인 없는 테마")
                .append("colors", palette)
                .append("isCustom", true)
                .append("status", "PRIVATE"),
        )

        val titles = listOf("1안", "2안", secret("${koreanName()} 시간표"), "최종", "나의 시간표")
        activeUsers.forEachIndexed { index, user ->
            val count = 1 + index % 3
            repeat(count) { n ->
                val semester = if (n % 2 == 0) 2026 to 1 else 2025 to 3
                // ★ index % 9 == 2면 같은 학기에 같은 제목의 대표 시간표가 둘
                val title = if (n == 0 || (index % 9 == 2 && n == 2)) "나의 시간표" else titles[(index + n) % titles.size]
                timetable(
                    user,
                    semester,
                    title,
                    primary = n == 0 || (index % 9 == 2 && n == 2),
                    themeId =
                        themesByUser[user.id]?.takeIf {
                            n ==
                                0
                        },
                )
            }
        }
        // ★ 주인 없는 시간표
        timetable(User(oid(), true), 2026 to 1, "주인 없는 시간표", primary = false, themeId = null)
    }

    private fun timetable(
        user: User,
        semester: Pair<Int, Int>,
        title: String,
        primary: Boolean,
        themeId: ObjectId?,
    ) {
        val id = oid()
        val pool = lectures.filter { it.year == semester.first && it.semester == semester.second }
        val items =
            pool.shuffled(random).take(random.nextInt(1, 5)).mapIndexed { index, lecture ->
                val item =
                    Document("_id", oid())
                        .append("lecture_id", lecture.id)
                        .append("course_number", lecture.courseNumber)
                        .append("lecture_number", "001")
                        .append("course_title", if (index == 1) secret("내가 바꾼 ${lecture.title} 이름") else lecture.title)
                        .append("credit", 3)
                        .append("class_time_json", lecture.times)
                        .append("colorIndex", if (index == 2) 12 else index + 1) // ★ 범위 밖 팔레트 번호
                if (lecture.instructor != null) item.append("instructor", lecture.instructor)
                item
            } +
                listOf(
                    Document("_id", oid())
                        .append("course_title", secret("과외 ${koreanName()} 학생"))
                        .append("instructor", secret("${koreanName()} 선생님"))
                        .append("remark", secret("매주 ${koreanName()}에게 연락"))
                        .append("class_time_json", listOf(time(5, 600)))
                        .append("colorIndex", 0)
                        .append(
                            "color",
                            // ★ 잘못된 색(글자색 없음, 짧은 배경색)
                            if (random.nextInt(4) == 0) Document("bg", "#1234") else Document("bg", "#AABBCC").append("fg", "#FFFFFF"),
                        ),
                )
        val doc =
            Document("_id", id)
                .append("user_id", user.id)
                .append("year", semester.first)
                .append("semester", semester.second)
                .append("title", title)
                .append("theme", random.nextInt(0, 6))
                .append("is_primary", primary)
                .append("lecture_list", items)
                .append("updated_at", at(random.nextLong(-500, 0)))
        if (themeId != null) doc.append("themeId", themeId.toHexString())
        add("timetables", doc)
        if (random.nextInt(3) == 0) {
            add(
                "timetableLectureReminder",
                Document("_id", oid())
                    .append("timetableId", id.toHexString())
                    .append("timetableLectureId", items.first().getObjectId("_id").toHexString())
                    .append("offsetMinutes", -10)
                    .append("schedules", listOf(Document("day", 1).append("minute", 530))),
            )
        }
    }

    private fun userData() {
        val all = users
        val missingLecture = oid()
        all.forEachIndexed { index, user ->
            if (index % 2 == 0) {
                val refs =
                    lectures.shuffled(random).take(3).map { it.id } +
                        (if (index % 6 == 0) listOf(missingLecture) else emptyList()) +
                        (if (index == 0) listOf(lectures.first().id, duplicateLecture.id) else emptyList()) // ★ 합쳐지는 개설 둘
                add(
                    "bookmarks",
                    Document("_id", oid())
                        .append("user_id", user.id)
                        .append("year", 2026)
                        .append("semester", 1)
                        .append("lectures", refs.map { Document("_id", it) }),
                )
            }
            if (index % 3 == 0) {
                val lecture = lectures.random(random)
                add("vacancy_notifications", Document("_id", oid()).append("userId", user.id).append("lectureId", lecture.id))
                if (index % 9 ==
                    0
                ) {
                    add("vacancy_notifications", Document("_id", oid()).append("userId", user.id).append("lectureId", missingLecture))
                }
                if (index ==
                    0
                ) {
                    add("vacancy_notifications", Document("_id", oid()).append("userId", user.id).append("lectureId", lecture.id)) // ★ 중복
                }
            }
            val token =
                when (index) {
                    22, 23 -> "fcm-shared-${"x".repeat(140)}" // ★ 같은 FCM 토큰의 활성 기기 둘
                    24 -> "" // ★ 빈 토큰
                    else -> secret(alnum(152))
                }
            add(
                "userDevice",
                Document("_id", oid())
                    .append("userId", user.id)
                    .append("osType", if (index % 2 == 0) "ios" else "android")
                    .append("osVersion", "18.0")
                    .append("deviceId", secret("device-${alnum(16)}"))
                    .append("deviceModel", "iPhone15,2")
                    .append("appType", "RELEASE")
                    .append("appVersion", "4.0.0")
                    .append("fcmRegistrationId", token)
                    .append("isDeleted", index % 11 == 10)
                    .append("createdAt", at(index.toLong()))
                    .append("updatedAt", at(index.toLong())),
            )
            if (index % 4 == 1) {
                add(
                    "pushPreference",
                    Document("_id", oid())
                        .append("userId", user.id)
                        .append(
                            "pushPreferences",
                            listOf(
                                Document("type", "LECTURE_UPDATE").append("isEnabled", false),
                                Document("type", "VACANCY_NOTIFICATION").append("isEnabled", true),
                            ),
                        ),
                )
            }
        }
        add("userDevice", Document("_id", oid()).append("userId", oid()).append("osType", "ios").append("fcmRegistrationId", alnum(152)))
        (0 until all.size - 1 step 2).forEach { i ->
            friend(all[i], all[i + 1], accepted = i % 4 == 0)
        }
        friend(all[3], all[2], accepted = false) // ★ 같은 쌍의 반대 방향
        friend(all[0], User(oid(), true), accepted = true) // ★ 없는 사용자
    }

    private fun friend(
        from: User,
        to: User,
        accepted: Boolean,
    ) = add(
        "friend",
        Document("_id", oid())
            .append("fromUserId", from.id)
            .append("toUserId", to.id)
            .append("fromUserDisplayName", secret("${koreanName()}친구"))
            .append("toUserDisplayName", if (random.nextBoolean()) null else secret("${koreanName()}동기"))
            .append("isAccepted", accepted)
            .append("createdAt", at(10))
            .append("updatedAt", at(10)),
    )

    private fun notifications() {
        add(
            "notifications",
            Document("_id", oid())
                .append("title", "공지")
                .append("message", "SNUTT 점검 안내")
                .append("type", 0)
                .append("created_at", at(1)),
        )
        users.take(10).forEach { user ->
            add(
                "notifications",
                Document("_id", oid())
                    .append("user_id", user.id)
                    .append("title", "친구 요청")
                    .append("message", secret("${koreanName()}님이 친구 요청을 보냈습니다"))
                    .append("type", 4)
                    .append("created_at", at(2)),
            )
        }
        val lecture = lectures[3]
        add(
            "notifications",
            Document("_id", oid())
                .append("user_id", users[1].id)
                .append("title", "빈자리")
                .append("message", "빈자리가 생겼습니다")
                .append("type", 5)
                .append("deeplink", "snutt://bookmarks?year=2026&semester=1&lectureId=${lecture.id.toHexString()}")
                .append("created_at", at(3)),
        )
        add(
            "notifications",
            Document("_id", oid())
                .append("user_id", users[1].id)
                .append("title", "알림")
                .append("message", "사라진 대상")
                .append("type", 2)
                .append("deeplink", "snutt://timetable-lecture?timetableId=${oid().toHexString()}&lectureId=${oid().toHexString()}")
                .append("created_at", at(4)),
        )
        add(
            "notifications",
            Document(
                "_id",
                oid(),
            ).append("user_id", oid()).append("title", "고아").append("message", "주인 없음").append("type", 2).append("created_at", at(5)),
        )
    }

    private fun diary() {
        val types = listOf(oid(), oid())
        types.forEachIndexed { index, id ->
            add("diaryDailyClassType", Document("_id", id).append("name", listOf("수업", "퀴즈")[index]).append("active", true))
        }
        val question = oid()
        add(
            "diaryQuestion",
            Document("_id", question)
                .append("question", "오늘 수업은 어땠나요?")
                .append("shortQuestion", "수업")
                .append("answers", listOf("좋았다", "보통", "별로"))
                .append("shortAnswers", listOf("좋음", "보통", "별로"))
                .append("targetDailyClassTypeIds", types)
                .append("active", true),
        )
        users.take(6).forEach { user ->
            val lecture = lectures.random(random)
            add(
                "diarySubmission",
                Document("_id", oid())
                    .append("userId", user.id)
                    .append("dailyClassTypeIds", listOf(types[0]))
                    .append("questionAnswers", listOf(Document("questionId", question.toHexString()).append("answerIndex", 1)))
                    .append("lectureId", lecture.id)
                    .append("courseTitle", lecture.title)
                    .append("comment", secret("${koreanName()} 교수님 수업 ${sentence(4)}"))
                    .append("createdAt", at(6))
                    .append("year", lecture.year)
                    .append("semester", lecture.semester),
            )
        }
    }

    private fun writeEv(ev: JdbcTemplate) {
        javaClass
            .getResource("/rehearsal/ev-schema.sql")!!
            .readText()
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .filter { it.isNotBlank() }
            .forEach(ev::execute)
        val now = Timestamp.from(base)
        var lectureId = 100L
        var semesterLectureId = 1_000L
        val semesterLectures = mutableListOf<Long>()
        val distinctCourses = lectures.distinctBy { it.courseNumber to it.instructor.orEmpty() }
        distinctCourses.take(20).forEachIndexed { index, lecture ->
            lectureId++
            // ★ 공백만 다른 과목(구 ev의 중복 과목)
            val courseNumbers = if (index == 0) listOf(lecture.courseNumber, " ${lecture.courseNumber} ") else listOf(lecture.courseNumber)
            courseNumbers.forEachIndexed { copy, number ->
                val id = lectureId + copy * 500
                ev.update(
                    "INSERT INTO lecture VALUES (?, ?, ?, '3학년', '', '전선', ?, 3, '컴퓨터공학부', ?, ?)",
                    id,
                    now,
                    now,
                    number,
                    lecture.instructor.orEmpty(),
                    lecture.title,
                )
                listOf(lecture.year to lecture.semester, 2024 to 3).forEach { (year, semester) ->
                    semesterLectureId++
                    ev.update(
                        "INSERT INTO semester_lecture VALUES (?, ?, ?, '3학년', '', '전선', 3, NULL, ?, ?, ?)",
                        semesterLectureId,
                        now,
                        now,
                        semester,
                        year,
                        id,
                    )
                    semesterLectures += semesterLectureId
                }
            }
        }
        // EV에만 있는 과목(SNUTT 강의 없음). EV.ONLY01은 강의평이 있고, EV.ONLY02는 SNUTT가 없는 학기(2025-1) 강의만 있고,
        // ★ EV.UNUSED는 SNUTT가 있는 학기(2025-3)에만 있어 강의도 강의평도 없는 과목으로 지워진다.
        val evOnlyCourses =
            listOf(
                Triple("EV.ONLY01", true, 2025 to 1),
                Triple("EV.ONLY02", false, 2025 to 1),
                Triple("EV.UNUSED", false, 2025 to 3),
            )
        evOnlyCourses.forEach { (number, withEvaluation, semester) ->
            lectureId++
            ev.update("INSERT INTO lecture VALUES (?, ?, ?, NULL, NULL, NULL, ?, 2, NULL, '박교수', 'EV 전용 과목')", lectureId, now, now, number)
            semesterLectureId++
            ev.update(
                "INSERT INTO semester_lecture VALUES (?, ?, ?, NULL, NULL, NULL, 2, NULL, ?, ?, ?)",
                semesterLectureId,
                now,
                now,
                semester.second,
                semester.first,
                lectureId,
            )
            if (withEvaluation) semesterLectures += semesterLectureId
        }
        val authors = users.map { it.id.toHexString() } + listOf("없는-사용자-0001")
        var evaluationId = 5_000L
        val evaluations = mutableListOf<Long>()
        repeat(80) { n ->
            evaluationId++
            val content = secret("  ${sentence(6)}\n${koreanName()} 교수님 ${sentence(3)} $n ")
            val partial = n % 5 == 0
            ev.update(
                "INSERT INTO lecture_evaluation VALUES (?, ?, ?, ?, ?, ?, ?, b'0', ?, 0, ?, ?, ?, ?, ?)",
                evaluationId,
                Timestamp.from(base.plusSeconds(n * 60L)),
                Timestamp.from(base.plusSeconds(n * 60L)),
                content,
                if (partial) null else random.nextInt(1, 6).toDouble(),
                if (partial) null else random.nextInt(1, 6).toDouble(),
                n % 13 == 0,
                if (partial) null else random.nextInt(1, 6).toDouble(),
                random.nextInt(1, 6).toDouble(),
                if (partial) null else 4.5,
                authors[n % authors.size],
                semesterLectures[n % semesterLectures.size],
                n % 7 == 0,
            )
            evaluations += evaluationId
        }
        var likeId = 0L
        evaluations.take(30).forEachIndexed { index, evaluation ->
            (0..index % 3).forEach { k ->
                likeId++
                ev.update(
                    "INSERT INTO evaluation_like VALUES (?, ?, ?, ?, ?)",
                    likeId,
                    now,
                    now,
                    authors[(index + k * 7) % authors.size],
                    evaluation,
                )
            }
        }
        // ★ 사용자가 없는 공감·신고
        ev.update("INSERT INTO evaluation_like VALUES (?, ?, ?, ?, ?)", ++likeId, now, now, authors.last(), evaluations.last())
        ev.update(
            "INSERT INTO evaluation_report VALUES (100, ?, ?, '없는 사용자의 신고', b'0', ?, ?)",
            now,
            now,
            authors.last(),
            evaluations.last(),
        )
        evaluations.take(5).forEachIndexed { index, evaluation ->
            ev.update(
                "INSERT INTO evaluation_report VALUES (?, ?, ?, ?, b'0', ?, ?)",
                index + 1L,
                now,
                now,
                secret("${koreanName()}이(가) 쓴 욕설 신고 $index"),
                authors[(index * 11 + 3) % authors.size],
                evaluation,
            )
        }
        ev.update("INSERT INTO tag_group VALUES (1, ?, ?, NULL, 'main', -1, 'LOGIC')", now, now)
        ev.update("INSERT INTO tag_group VALUES (5, ?, ?, '#1BD0C8', '학과', 4, 'STRING')", now, now)
        ev.update("INSERT INTO tag VALUES (1, ?, ?, '최근 등록된 강의평', NULL, '최신', 1, NULL, 1)", now, now)
        ev.update("INSERT INTO tag VALUES (30, ?, ?, NULL, NULL, '컴퓨터공학부', 1, '컴퓨터공학부', 5)", now, now)
    }
}
