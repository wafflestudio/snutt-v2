package com.wafflestudio.snutt.parity.seed

import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant

/**
 * v1 문서 모양(snutt core 모듈 각 도메인의 data 패키지, Spring Data Mongo 매핑)대로 시드를 쌓는다.
 * 필드 이름·타입은 v1 데이터 클래스의 @Field와 컨버터(enum은 Int로 저장)를 따른다.
 * EV 행은 v1 snutt-ev Flyway 스키마(V1~V5)의 컬럼을 따른다.
 */
class SeedBuilder(
    val baseTime: Instant,
) {
    private val ids = ObjectIds(baseTime.epochSecond)
    private val collections = LinkedHashMap<String, MutableList<Document>>()
    private val evRows = mutableListOf<EvRow>()
    private val aliases = LinkedHashMap<String, SeedAlias>()
    private val users = LinkedHashMap<String, SeedUser>()
    private val passwordEncoder = BCryptPasswordEncoder(4)
    private val evIds = HashMap<String, Long>()

    fun objectId(): ObjectId = ids.next()

    fun at(minutes: Long): Instant = baseTime.plusSeconds(minutes * 60)

    fun insert(
        collection: String,
        document: Document,
    ): Document {
        collections.getOrPut(collection) { mutableListOf() } += document
        return document
    }

    fun alias(
        name: String,
        v1: String,
        v2: (MigrationResult) -> String?,
    ) {
        check(aliases.put(name, SeedAlias(name, v1, v2)) == null) { "별칭 중복: $name" }
    }

    fun evRow(
        table: String,
        vararg values: Pair<String, Any?>,
    ) {
        evRows += EvRow(table, linkedMapOf(*values))
    }

    fun nextEvId(table: String): Long = evIds.merge(table, 1L, Long::plus)!!

    fun build(): LegacyData = LegacyData(collections, evRows, aliases.values.toList(), users)

    // ---- SNUTT ----

    fun coursebook(
        year: Int,
        semester: Int,
        updatedAt: Instant = baseTime,
    ) = insert("coursebooks", doc("_id" to objectId(), "year" to year, "semester" to semester, "updated_at" to updatedAt.toDate()))

    class Time(
        val day: Int,
        val place: String,
        val startMinute: Int,
        val endMinute: Int,
    ) {
        fun toDocument() = doc("day" to day, "place" to place, "startMinute" to startMinute, "endMinute" to endMinute)
    }

    class SeedLecture(
        val alias: String,
        val id: ObjectId,
        val document: Document,
    ) {
        val year get() = document.getInteger("year")
        val semester get() = document.getInteger("semester")
        val courseNumber: String get() = document.getString("course_number")
        val instructor: String? get() = document.getString("instructor")
    }

    @Suppress("LongParameterList")
    fun lecture(
        alias: String?,
        year: Int,
        semester: Int,
        courseNumber: String,
        lectureNumber: String,
        title: String,
        instructor: String?,
        times: List<Time>,
        credit: Long = 3,
        classification: String? = "전선",
        department: String? = "컴퓨터공학부",
        academicYear: String? = "3학년",
        category: String? = "",
        categoryPre2025: String? = null,
        quota: Int = 80,
        freshmanQuota: Int? = null,
        registrationCount: Int = 40,
        wasFull: Boolean = false,
        remark: String? = "",
        english: Map<String, String> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): SeedLecture {
        val id = objectId()
        val document =
            compactDoc(
                "_id" to id,
                "academic_year" to academicYear,
                "category" to category,
                "class_time_json" to times.map { it.toDocument() },
                "classification" to classification,
                "credit" to credit,
                "department" to department,
                "instructor" to instructor,
                "lecture_number" to lectureNumber,
                "quota" to quota,
                "freshmanQuota" to freshmanQuota,
                "remark" to remark,
                "semester" to semester,
                "year" to year,
                "course_number" to courseNumber,
                "course_title" to title,
                "registrationCount" to registrationCount,
                "wasFull" to wasFull,
                "categoryPre2025" to categoryPre2025,
            )
        english.forEach { (key, value) -> document[key] = value }
        extra.forEach { (key, value) -> document[key] = value }
        insert("lectures", document)
        if (alias != null) alias("lecture:$alias", id.toHexString()) { it.context.lectureIds[id.toHexString()]?.toString() }
        return SeedLecture(alias ?: id.toHexString(), id, document)
    }

    fun tagList(
        year: Int,
        semester: Int,
        lectures: List<SeedLecture>,
    ) {
        // v1 batch(SugangSnuSyncService)가 학기 강의에서 만드는 모양을 따른다: 빈 값 제외, 정렬, credit은 "N학점".
        fun values(key: String) =
            lectures
                .mapNotNull { it.document.getString(key) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        val credits = lectures.map { (it.document["credit"] as Number).toLong() }.distinct().sorted()
        insert(
            "taglists",
            doc(
                "_id" to objectId(),
                "year" to year,
                "semester" to semester,
                "updated_at" to baseTime.toDate(),
                "tags" to
                    doc(
                        "classification" to values("classification"),
                        "department" to values("department"),
                        "academic_year" to values("academic_year").filter { it.length > 1 },
                        "credit" to credits.map { "${it}학점" },
                        "instructor" to values("instructor"),
                        "category" to values("category"),
                        "categoryPre2025" to values("categoryPre2025"),
                        "classification_en" to values("classification_en"),
                        "department_en" to values("department_en"),
                        "academic_year_en" to values("academic_year_en"),
                        "credit_en" to credits.map { "$it credits" },
                        "instructor_en" to values("instructor_en"),
                        "category_en" to values("category_en"),
                    ),
            ),
        )
    }

    class Social(
        val fbId: String? = null,
        val fbName: String? = null,
        val googleSub: String? = null,
        val googleEmail: String? = null,
        val kakaoSub: String? = null,
        val kakaoEmail: String? = null,
        val appleSub: String? = null,
        val appleEmail: String? = null,
    )

    /**
     * @param isEmailVerified null이면 필드를 넣지 않는다(오래된 v1 계정의 모양).
     * @param token v1 credentialHash. 같은 값을 둘 이상에게 주면 모호한 토큰 사례가 된다.
     */
    @Suppress("LongParameterList")
    fun user(
        alias: String,
        localId: String?,
        password: String? = localId?.let { "password1" },
        email: String?,
        isEmailVerified: Boolean?,
        nickname: String,
        social: Social = Social(),
        token: String = "parity-token-$alias",
        active: Boolean = true,
        isAdmin: Boolean = false,
        regDate: Instant = baseTime,
        lastLogin: Instant = baseTime,
        notificationCheckedAt: Instant = baseTime,
    ): SeedUser {
        val id = objectId()
        val credential =
            compactDoc(
                "localId" to localId,
                "localPw" to password?.let(passwordEncoder::encode),
                "fbId" to social.fbId,
                "fbName" to social.fbName,
                "appleSub" to social.appleSub,
                "appleEmail" to social.appleEmail,
                "googleSub" to social.googleSub,
                "googleEmail" to social.googleEmail,
                "kakaoSub" to social.kakaoSub,
                "kakaoEmail" to social.kakaoEmail,
            )
        insert(
            "users",
            compactDoc(
                "_id" to id,
                "email" to email,
                "nickname" to nickname,
                "isEmailVerified" to isEmailVerified,
                "credential" to credential,
                "credentialHash" to token,
                "active" to active,
                "isAdmin" to isAdmin,
                "regDate" to regDate.toDate(),
                "lastLoginTimestamp" to lastLogin.toEpochMilli(),
                "notificationCheckedAt" to notificationCheckedAt.toDate(),
            ),
        )
        alias("user:$alias", id.toHexString()) { it.context.userIds[id.toHexString()]?.toString() }
        return SeedUser(alias, id, token, localId, password).also { users[alias] = it }
    }

    class SeedTheme(
        val alias: String,
        val id: ObjectId,
    )

    @Suppress("LongParameterList")
    fun theme(
        alias: String,
        owner: SeedUser,
        name: String,
        colors: List<Pair<String, String>>,
        status: String = "PRIVATE",
        publishName: String? = null,
        downloads: Int = 0,
        origin: Pair<SeedTheme, SeedUser>? = null,
        createdAt: Instant = baseTime,
        updatedAt: Instant = baseTime,
    ): SeedTheme {
        val id = objectId()
        insert(
            "timetableTheme",
            compactDoc(
                "_id" to id,
                "userId" to owner.id,
                "name" to name,
                "colors" to colors.map { (bg, fg) -> doc("bg" to bg, "fg" to fg) },
                "isCustom" to true,
                "origin" to
                    origin?.let { (theme, author) -> doc("originId" to theme.id.toHexString(), "authorId" to author.id.toHexString()) },
                "status" to status,
                "publishInfo" to
                    publishName?.let { doc("publishName" to it, "authorAnonymous" to false, "downloads" to downloads) },
                "createdAt" to createdAt.toDate(),
                "updatedAt" to updatedAt.toDate(),
            ),
        )
        alias("theme:$alias", id.toHexString()) { it.context.themeIds[id.toHexString()]?.toString() }
        return SeedTheme(alias, id)
    }

    class SeedTimetable(
        val alias: String,
        val id: ObjectId,
        val lectureIds: Map<String, ObjectId>,
    )

    /** 시간표 강의. [lecture]가 있으면 v1이 강의를 추가할 때처럼 강의 필드를 복사하고 [overrides]로 덮는다. */
    class Entry(
        val alias: String,
        val lecture: SeedLecture?,
        val colorIndex: Int,
        val color: Pair<String?, String?>? = null,
        val overrides: Map<String, Any?> = emptyMap(),
    )

    @Suppress("LongParameterList")
    fun timetable(
        alias: String,
        owner: SeedUser?,
        ownerId: ObjectId? = owner?.id,
        year: Int,
        semester: Int,
        title: String,
        isPrimary: Boolean?,
        theme: Int = 0,
        themeId: ObjectId? = null,
        updatedAt: Instant = baseTime,
        entries: List<Entry> = emptyList(),
    ): SeedTimetable {
        val id = objectId()
        val lectureIds = LinkedHashMap<String, ObjectId>()
        val lectureList =
            entries.map { entry ->
                val itemId = objectId()
                lectureIds[entry.alias] = itemId
                val source = entry.lecture?.document
                val item =
                    compactDoc(
                        "_id" to itemId,
                        "academic_year" to source?.get("academic_year"),
                        "category" to source?.get("category"),
                        "class_time_json" to (source?.get("class_time_json") ?: emptyList<Document>()),
                        "classification" to source?.get("classification"),
                        "credit" to source?.get("credit"),
                        "department" to source?.get("department"),
                        "instructor" to source?.get("instructor"),
                        "lecture_number" to source?.get("lecture_number"),
                        "quota" to source?.get("quota"),
                        "freshmanQuota" to source?.get("freshmanQuota"),
                        "remark" to source?.get("remark"),
                        "course_number" to source?.get("course_number"),
                        "course_title" to source?.get("course_title"),
                        "color" to entry.color?.let { (bg, fg) -> compactDoc("bg" to bg, "fg" to fg) },
                        "colorIndex" to entry.colorIndex,
                        "lecture_id" to entry.lecture?.id,
                        "categoryPre2025" to source?.get("categoryPre2025"),
                    )
                entry.overrides.forEach { (key, value) -> if (value == null) item.remove(key) else item[key] = value }
                val ttHex = id.toHexString()
                val itemHex = itemId.toHexString()
                alias("tl:$alias/${entry.alias}", itemHex) { it.context.timetableLectureIds[ttHex to itemHex]?.toString() }
                item
            }
        insert(
            "timetables",
            compactDoc(
                "_id" to id,
                "user_id" to ownerId,
                "year" to year,
                "semester" to semester,
                "lecture_list" to lectureList,
                "title" to title,
                "theme" to theme,
                "themeId" to themeId?.toHexString(),
                "is_primary" to isPrimary,
                "updated_at" to updatedAt.toDate(),
            ),
        )
        alias("tt:$alias", id.toHexString()) { it.context.timetableIds[id.toHexString()]?.toString() }
        return SeedTimetable(alias, id, lectureIds)
    }

    fun friend(
        alias: String,
        from: SeedUser,
        to: SeedUser,
        accepted: Boolean,
        fromDisplayName: String? = null,
        toDisplayName: String? = null,
        createdAt: Instant = baseTime,
    ) {
        val id = objectId()
        insert(
            "friend",
            compactDoc(
                "_id" to id,
                "fromUserId" to from.id,
                "toUserId" to to.id,
                "fromUserDisplayName" to fromDisplayName,
                "toUserDisplayName" to toDisplayName,
                "isAccepted" to accepted,
                "createdAt" to createdAt.toDate(),
                "updatedAt" to createdAt.toDate(),
            ),
        )
        alias("friend:$alias", id.toHexString()) { result ->
            val fromId = result.context.userIds[from.id.toHexString()]
            val toId = result.context.userIds[to.id.toHexString()]
            result.jdbc
                .queryForList(
                    "SELECT id FROM friend WHERE from_user_id = ? AND to_user_id = ?",
                    Long::class.java,
                    fromId,
                    toId,
                ).singleOrNull()
                ?.toString()
        }
    }

    /** 수강편람에서 사라진 강의(북마크·시간표에만 사본이 남은 경우)용. lectures 컬렉션에는 넣지 않는다. */
    fun ghostLecture(
        alias: String,
        year: Int,
        semester: Int,
        courseNumber: String,
        title: String,
    ): SeedLecture {
        val id = objectId()
        alias("lecture:$alias", id.toHexString()) { null }
        val document =
            doc(
                "_id" to id,
                "academic_year" to "4학년",
                "category" to "",
                "class_time_json" to listOf(Time(3, "43-1-201", 900, 975).toDocument()),
                "classification" to "전선",
                "credit" to 3L,
                "department" to "컴퓨터공학부",
                "instructor" to "폐강교수",
                "lecture_number" to "001",
                "quota" to 30,
                "remark" to "",
                "semester" to semester,
                "year" to year,
                "course_number" to courseNumber,
                "course_title" to title,
            )
        return SeedLecture(alias, id, document)
    }

    fun bookmark(
        owner: SeedUser,
        year: Int,
        semester: Int,
        lectures: List<SeedLecture>,
    ) {
        val items =
            lectures.map { lecture ->
                val source = lecture.document
                compactDoc(
                    "_id" to lecture.id,
                    "academic_year" to source["academic_year"],
                    "category" to source["category"],
                    "class_time_json" to source["class_time_json"],
                    "classification" to source["classification"],
                    "credit" to source["credit"],
                    "department" to source["department"],
                    "instructor" to source["instructor"],
                    "lecture_number" to source["lecture_number"],
                    "quota" to source["quota"],
                    "freshmanQuota" to source["freshmanQuota"],
                    "remark" to source["remark"],
                    "course_number" to source["course_number"],
                    "course_title" to source["course_title"],
                    "categoryPre2025" to source["categoryPre2025"],
                )
            }
        insert(
            "bookmarks",
            doc("_id" to objectId(), "user_id" to owner.id, "year" to year, "semester" to semester, "lectures" to items),
        )
    }

    fun notification(
        alias: String,
        owner: SeedUser?,
        title: String,
        message: String,
        type: Int,
        createdAt: Instant,
        deeplink: String? = null,
    ) {
        val id = objectId()
        insert(
            "notifications",
            compactDoc(
                "_id" to id,
                "user_id" to owner?.id,
                "title" to title,
                "message" to message,
                "type" to type,
                "deeplink" to deeplink,
                "created_at" to createdAt.toDate(),
            ),
        )
        alias("notification:$alias", id.toHexString()) { result ->
            result.jdbc
                .queryForList("SELECT id FROM notification WHERE title = ? AND message = ?", Long::class.java, title, message)
                .singleOrNull()
                ?.toString()
        }
    }

    fun pushPreference(
        owner: SeedUser,
        vararg preferences: Pair<String, Boolean>,
    ) = insert(
        "pushPreference",
        doc(
            "_id" to objectId(),
            "userId" to owner.id,
            "pushPreferences" to preferences.map { (type, enabled) -> doc("type" to type, "isEnabled" to enabled) },
        ),
    )

    fun vacancyNotification(
        owner: SeedUser,
        lecture: SeedLecture,
    ) = insert("vacancy_notifications", doc("_id" to objectId(), "userId" to owner.id, "lectureId" to lecture.id))

    fun device(
        owner: SeedUser,
        osType: String,
        fcmRegistrationId: String,
        appVersion: String = "4.0.0",
    ) = insert(
        "userDevice",
        doc(
            "_id" to objectId(),
            "userId" to owner.id,
            "osType" to osType,
            "osVersion" to "18.0",
            "deviceId" to "device-${owner.alias}",
            "deviceModel" to "parity-phone",
            "appType" to "RELEASE",
            "appVersion" to appVersion,
            "fcmRegistrationId" to fcmRegistrationId,
            "createdAt" to baseTime.toDate(),
            "updatedAt" to baseTime.toDate(),
        ),
    )

    fun clientConfig(
        name: String,
        value: String,
        minIosVersion: String? = null,
        minAndroidVersion: String? = null,
        createdAt: Instant = baseTime,
    ) = insert(
        "clientConfig",
        compactDoc(
            "_id" to objectId(),
            "name" to name,
            "value" to value,
            "minIosVersion" to minIosVersion,
            "minAndroidVersion" to minAndroidVersion,
            "createdAt" to createdAt.toDate(),
            "updatedAt" to createdAt.toDate(),
        ),
    )

    fun building(
        number: String,
        nameKor: String,
        nameEng: String,
        campus: String,
        latitude: Double,
        longitude: Double,
    ) = insert(
        "lectureBuilding",
        doc(
            "_id" to objectId(),
            "buildingNumber" to number,
            "buildingNameKor" to nameKor,
            "buildingNameEng" to nameEng,
            "locationInDMS" to doc("latitude" to latitude, "longitude" to longitude),
            "locationInDecimal" to doc("latitude" to latitude * 13_000, "longitude" to longitude * 8_700),
            "campus" to campus,
        ),
    )

    fun reminder(
        timetable: SeedTimetable,
        entryAlias: String,
        offsetMinutes: Int,
        schedules: List<Pair<Int, Int>>,
    ) = insert(
        "timetableLectureReminder",
        doc(
            "_id" to objectId(),
            "timetableId" to timetable.id,
            "timetableLectureId" to timetable.lectureIds.getValue(entryAlias),
            "offsetMinutes" to offsetMinutes,
            "schedules" to schedules.map { (day, minute) -> doc("day" to day, "minute" to minute) },
        ),
    )

    /**
     * EV에 과목이 없는 SNUTT 강의에 대해 이관이 새로 만드는 과목(CourseStep.mintMissingCourses)의 별칭 `course-minted:<강의>`.
     * v1에는 대응하는 값이 없으므로 v1 쪽 값은 응답에 나올 수 없는 자리 표시다.
     */
    fun mintedCourse(lecture: SeedLecture) {
        val key = lecture.courseNumber.trim() to lecture.instructor.orEmpty().trim()
        alias("course-minted:${lecture.alias}", "<v1에 없음:${lecture.alias}>") { result ->
            result.context.courseIds[result.context.courseKey(key.first, key.second)]?.toString()
        }
    }

    // ---- EV ----

    class EvCourse(
        val alias: String,
        val id: Long,
    )

    class EvSemesterLecture(
        val id: Long,
        val course: EvCourse,
    )

    fun evCourse(
        alias: String,
        courseNumber: String,
        instructor: String,
        title: String,
        credit: Int = 3,
        department: String = "컴퓨터공학부",
        classification: String = "전선",
        academicYear: String = "3학년",
        category: String = "",
        id: Long = nextEvId("lecture") + EV_LECTURE_ID_BASE,
    ): EvCourse {
        evRow(
            "lecture",
            "id" to id,
            "created_at" to baseTime.toSqlTimestamp(),
            "updated_at" to baseTime.toSqlTimestamp(),
            "academic_year" to academicYear,
            "category" to category,
            "classification" to classification,
            "course_number" to courseNumber,
            "credit" to credit,
            "department" to department,
            "instructor" to instructor,
            "title" to title,
        )
        alias("course:$alias", id.toString()) { result -> (result.context.courseIdRemap[id] ?: id).toString() }
        return EvCourse(alias, id)
    }

    /** EV 학기 강의. [snuttLectures]는 EV `snutt_lecture_id_map`으로 연결된 v1 snutt 강의다. */
    fun evSemesterLecture(
        course: EvCourse,
        year: Int,
        semester: Int,
        snuttLectures: List<SeedLecture> = emptyList(),
        // EV batch(SnuttLectureSyncJobConfig)가 SNUTT 강의 remark를 넣는다. EV 엔티티는 null을 받지 못한다.
        extraInfo: String =
            snuttLectures
                .firstOrNull()
                ?.document
                ?.getString("remark")
                .orEmpty(),
    ): EvSemesterLecture {
        val id = nextEvId("semester_lecture") + EV_SEMESTER_LECTURE_ID_BASE
        // EV batch는 학기 강의 정보를 SNUTT 강의에서 복사한다. SNUTT 강의가 없는 학기(snuev 시절)는 과목 값을 쓴다.
        val source = snuttLectures.firstOrNull()?.document
        val courseRow = evRows.first { it.table == "lecture" && it.values["id"] == course.id }.values
        evRow(
            "semester_lecture",
            "id" to id,
            "created_at" to baseTime.toSqlTimestamp(),
            "updated_at" to baseTime.toSqlTimestamp(),
            "academic_year" to (source?.getString("academic_year") ?: courseRow["academic_year"]),
            "category" to (source?.getString("category") ?: courseRow["category"]),
            "classification" to (source?.getString("classification") ?: courseRow["classification"]),
            "credit" to ((source?.get("credit") as? Number)?.toInt() ?: courseRow["credit"]),
            "extra_info" to extraInfo,
            "semester" to semester,
            "year" to year,
            "lecture_id" to course.id,
        )
        snuttLectures.forEach { lecture ->
            evRow(
                "snutt_lecture_id_map",
                "id" to nextEvId("snutt_lecture_id_map"),
                "created_at" to baseTime.toSqlTimestamp(),
                "updated_at" to baseTime.toSqlTimestamp(),
                "snutt_id" to lecture.id.toHexString(),
                "semester_lecture_id" to id,
            )
        }
        return EvSemesterLecture(id, course)
    }

    class EvEvaluation(
        val alias: String,
        val id: Long,
        val rating: Double,
        val hidden: Boolean,
        val course: EvCourse,
    )

    @Suppress("LongParameterList")
    fun evaluation(
        alias: String,
        semesterLecture: EvSemesterLecture,
        authorId: String,
        content: String,
        rating: Double,
        scores: Double? = 3.0,
        hidden: Boolean = false,
        reported: Boolean = false,
        likeCount: Long = 0,
        fromSnuev: Boolean = false,
        createdAt: Instant = baseTime,
    ): EvEvaluation {
        val id = nextEvId("lecture_evaluation") + EV_EVALUATION_ID_BASE
        evRow(
            "lecture_evaluation",
            "id" to id,
            "created_at" to createdAt.toSqlTimestamp(),
            "updated_at" to createdAt.toSqlTimestamp(),
            "content" to content,
            "gains" to scores,
            "grade_satisfaction" to scores,
            "is_hidden" to hidden,
            "is_reported" to reported,
            "life_balance" to scores,
            "like_count" to likeCount,
            "rating" to rating,
            "teaching_skill" to scores,
            "user_id" to authorId,
            "semester_lecture_id" to semesterLecture.id,
            "from_snuev" to fromSnuev,
        )
        alias("evaluation:$alias", id.toString()) { id.toString() }
        return EvEvaluation(alias, id, rating, hidden, semesterLecture.course)
    }

    fun evaluationLike(
        evaluation: EvEvaluation,
        userId: String,
    ) = evRow(
        "evaluation_like",
        "id" to nextEvId("evaluation_like"),
        "created_at" to baseTime.toSqlTimestamp(),
        "updated_at" to baseTime.toSqlTimestamp(),
        "user_id" to userId,
        "lecture_evaluation_id" to evaluation.id,
    )

    /** EV 원본 seed CSV(snutt-ev core 모듈 resources의 data 디렉터리, 운영 태그와 같은 id 순서)를 넣는다. */
    fun evTagsFromCsv(
        tagGroupCsv: List<String>,
        tagCsv: List<String>,
    ) {
        tagGroupCsv.filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val (createdAt, updatedAt, name, ordering, valueType) = line.split(",")
            evRow(
                "tag_group",
                "id" to index + 1L,
                "created_at" to createdAt,
                "updated_at" to updatedAt,
                "color" to null,
                "name" to name,
                "ordering" to ordering.toInt(),
                "value_type" to valueType,
            )
        }
        tagCsv.filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val cols = line.split(",", limit = 8)
            evRow(
                "tag",
                "id" to index + 1L,
                "created_at" to cols[0],
                "updated_at" to cols[1],
                "int_value" to cols[2].ifEmpty { null }?.toInt(),
                "name" to cols[3],
                "ordering" to cols[4].toInt(),
                "string_value" to cols[5].ifEmpty { null },
                "tag_group_id" to cols[6].toLong(),
                "description" to cols[7].ifEmpty { null },
            )
        }
    }

    /**
     * EV가 강의평이 바뀔 때 snutt `lectures.evInfo`를 갱신하던 결과를 재현한다(snutt-ev MongoService.updateEvInfoToSnuttIds).
     * 숨김이 아닌 강의평의 평균과 개수를 과목 단위로 계산해 매핑된 snutt 강의에 넣는다.
     */
    fun applyEvInfo(
        evaluations: List<EvEvaluation>,
        mapping: Map<EvCourse, List<SeedLecture>>,
    ) {
        mapping.forEach { (course, lectures) ->
            val visible = evaluations.filter { it.course == course && !it.hidden }
            val info =
                if (visible.isEmpty()) {
                    doc("evId" to course.id, "avgRating" to null, "count" to 0)
                } else {
                    doc("evId" to course.id, "avgRating" to visible.map { it.rating }.average(), "count" to visible.size)
                }
            lectures.forEach { it.document["evInfo"] = info }
        }
    }

    private fun Instant.toSqlTimestamp() = java.sql.Timestamp.from(this)

    companion object {
        const val EV_LECTURE_ID_BASE = 64_999L
        const val EV_SEMESTER_LECTURE_ID_BASE = 215_999L
        const val EV_EVALUATION_ID_BASE = 32_999L
    }
}
