package com.wafflestudio.snutt.migration.rehearsal.check

import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.oids
import com.wafflestudio.snutt.migration.str
import org.bson.Document

/**
 * v2 테이블별 기대 행 수를 v1 원본만으로 계산한다. 규칙은 migration 코드(step 패키지)의 정리 규칙을 원본 쿼리로 다시 쓴 것이고,
 * 이관이 스스로 센 값(MigrationContext.resolutions, ValidateStep)과는 따로 계산한다. 그래서 이관 코드가 세는 것을 빠뜨리거나
 * 잘못 세면 여기서 어긋난다.
 */
class ExpectedCounts(
    private val source: SourceIndex,
) {
    class Count(
        val label: String,
        /** v1 원본 건수(정리 전). 리포트에 같이 보여 준다. */
        val source: Long,
        val expected: Long,
        /** v2에서 실제 값을 세는 SQL. */
        val actualSql: String,
        /** 원본과 기대값이 다른 이유(이관 규칙). */
        val rule: String,
    )

    private val mongo = source.mongo
    private val ev = source.ev
    private val users = source.users

    /** v1 시간표에서 시간이 겹치는 강의가 있는 시간표 수(주인이 있는 것만). 불변식 점검의 기준값이다. */
    var overlappingTimetables = 0L
        private set

    fun compute(): List<Count> =
        buildList {
            addAll(userCounts())
            addAll(catalogCounts())
            addAll(lectureCounts())
            addAll(themeCounts())
            addAll(timetableCounts())
            addAll(userDataCounts())
            addAll(notificationCounts())
            addAll(evCounts())
        }

    private fun count(collection: String): Long = mongo.getCollection(collection).countDocuments()

    private fun exists(hex: String?) = hex != null && hex in users

    private fun active(hex: String?) = hex != null && users[hex]?.active == true

    private fun userCounts(): List<Count> {
        val all = users.values
        val active = all.filter { it.active }
        val tokens = all.mapNotNull { it.legacyToken }.groupingBy { it }.eachCount()
        val verifiedActive = active.filter { it.verified == true }
        return listOf(
            Count("user", all.size.toLong(), all.size.toLong(), "SELECT COUNT(*) FROM `user`", "모두 옮긴다"),
            Count(
                "user.local_id 있음",
                all.count { it.localId != null }.toLong(),
                active.mapNotNull { it.localId }.distinct().size + all.count { !it.active && it.localId != null }.toLong(),
                "SELECT COUNT(*) FROM `user` WHERE local_id IS NOT NULL",
                "같은 아이디의 활성 계정은 최종 로그인이 최신인 하나만 아이디를 남긴다(탈퇴 계정은 그대로)",
            ),
            Count(
                "user 활성·이메일 인증",
                verifiedActive.size.toLong(),
                verifiedActive.mapNotNull { it.email?.lowercase() }.distinct().size + verifiedActive.count { it.email == null }.toLong(),
                "SELECT COUNT(*) FROM `user` WHERE active AND is_email_verified",
                "같은 이메일(대소문자 무시)이 인증된 활성 계정은 최종 로그인이 최신인 하나만 인증을 남긴다",
            ),
            Count(
                "user.is_email_verified NULL(v1 필드 없음)",
                all.count { it.verified == null }.toLong(),
                all.count { it.verified == null }.toLong(),
                "SELECT COUNT(*) FROM `user` WHERE is_email_verified IS NULL",
                "v1에 isEmailVerified가 없던 계정은 false가 아니라 NULL로 보존한다(V5)",
            ),
            Count(
                "user_social_auth",
                all.sumOf { it.socials.size }.toLong(),
                active
                    .flatMap { it.socials }
                    .distinct()
                    .size
                    .toLong(),
                "SELECT COUNT(*) FROM user_social_auth",
                "탈퇴 계정의 소셜 연동은 옮기지 않고, 같은 소셜 계정의 활성 계정이 여럿이면 최종 로그인이 최신인 하나만 남긴다",
            ),
            Count(
                "legacy_access_token",
                tokens.values.sum().toLong(),
                tokens.count { it.value == 1 }.toLong(),
                "SELECT COUNT(*) FROM legacy_access_token",
                "로그인 수단이 있는 활성 계정의 credentialHash만 옮기고, 같은 값을 여러 활성 계정이 가지면 옮기지 않는다",
            ),
            Count("refresh_token", 0, 0, "SELECT COUNT(*) FROM refresh_token", "v1에 없다"),
        )
    }

    private fun catalogCounts(): List<Count> {
        fun same(
            table: String,
            collection: String,
        ) = count(collection).let { Count(table, it, it, "SELECT COUNT(*) FROM `$table`", "모두 옮긴다") }
        val targets = mongo.getCollection("diaryQuestion").find().sumOf { it.oids("targetDailyClassTypeIds").size.toLong() }
        return listOf(
            same("coursebook", "coursebooks"),
            same("lecture_building", "lectureBuilding"),
            same("semester_registration_period", "semesterRegistrationPeriod"),
            same("popup", "popup"),
            same("diary_daily_class_type", "diaryDailyClassType"),
            same("diary_question", "diaryQuestion"),
            Count("diary_question_target", targets, targets, "SELECT COUNT(*) FROM diary_question_target", "질문의 대상 종류 목록을 편다"),
            count("clientConfig").let {
                Count("client_config", it, it * 2, "SELECT COUNT(*) FROM client_config", "설정 하나가 iOS·Android 두 행이 된다")
            },
        )
    }

    private fun lectureCounts(): List<Count> {
        val mongoLectures = count("lectures")
        val evOnly = HashSet<Triple<String?, Int, Int>>()
        var evOnlySource = 0L
        ev.query("SELECT sl.year, sl.semester, sl.lecture_id FROM semester_lecture sl JOIN lecture l ON l.id = sl.lecture_id") { rs ->
            val semester = rs.getInt("year") to rs.getInt("semester")
            if (semester in source.mongoSemesters) return@query
            evOnlySource++
            evOnly += Triple(source.evCourseKey[rs.getLong("lecture_id")], semester.first, semester.second)
        }
        val evaluationCourseKeys = HashSet<String>()
        ev.query("SELECT sl.lecture_id FROM lecture_evaluation le JOIN semester_lecture sl ON sl.id = le.semester_lecture_id") { rs ->
            source.evCourseKey[rs.getLong("lecture_id")]?.let(evaluationCourseKeys::add)
        }
        val usedCourses = HashSet<String>()
        source.offerings.values.forEach { usedCourses += it.courseKey }
        evOnly.forEach { it.first?.let(usedCourses::add) }
        usedCourses += evaluationCourseKeys
        val evCourses = ev.queryForObject("SELECT COUNT(*) FROM lecture", Long::class.java)!!
        val offerings = source.offerings.size.toLong()
        return listOf(
            Count(
                "course",
                evCourses,
                usedCourses.size.toLong(),
                "SELECT COUNT(*) FROM course",
                "구 ev lecture의 같은 (교과목번호, 강사)는 하나로 합치고, SNUTT 강의에만 있는 과목을 새로 만들고, 강의도 강의평도 없는 과목은 지운다",
            ),
            Count(
                "lecture",
                mongoLectures + evOnlySource,
                offerings + evOnly.size,
                "SELECT COUNT(*) FROM lecture",
                "같은 개설(연도, 학기, 교과목번호, 분반)은 하나로 합치고, SNUTT 강의가 없는 학기의 EV 강의를 더한다(합친 과목의 같은 학기 강의는 하나)",
            ),
            Count(
                "lecture_registration_status",
                mongoLectures,
                offerings,
                "SELECT COUNT(*) FROM lecture_registration_status",
                "SNUTT 강의의 개설마다 하나",
            ),
            Count(
                "lecture_class_time",
                mongo.getCollection("lectures").find().sumOf { it.docs("class_time_json").size.toLong() },
                source.offerings.values.sumOf { it.classTimes.toLong() },
                "SELECT COUNT(*) FROM lecture_class_time",
                "합쳐진 개설은 처음 나온 문서의 수업 시간만 남는다",
            ),
        )
    }

    private fun themeCounts(): List<Count> {
        class Theme(
            val doc: Document,
            val userHex: String,
        ) {
            val downloaded get() = doc.str("status") == "DOWNLOADED"
            val name get() = doc.str("name")
            val colors get() = doc.docs("colors").map { it.str("bg") to it.str("fg") }
            val publishName get() = doc.doc("publishInfo")?.str("publishName")
        }
        val custom = mongo.getCollection("timetableTheme").find(Document("isCustom", true)).toList()
        val themes = custom.mapNotNull { doc -> doc.oid("userId")?.takeIf(::exists)?.let { Theme(doc, it) } }
        val own = themes.filterNot { it.downloaded }
        val published = own.filter { it.publishName != null }.associateBy { it.doc.id() }
        val archives = HashSet<String>()
        val downloads = HashSet<String>()
        themes.filter { it.downloaded }.forEach { theme ->
            val originId = theme.doc.doc("origin")?.oid("originId")
            val origin = published[originId]
            val publication =
                if (origin != null && origin.publishName == theme.name && origin.colors == theme.colors) {
                    "published:$originId"
                } else {
                    "archive:$originId\u0000${theme.name}\u0000${theme.colors}".also(archives::add)
                }
            downloads += "${theme.userHex}\u0000$publication"
        }
        val sourcePublished =
            custom.count { it.str("status") != "DOWNLOADED" && it.doc("publishInfo")?.str("publishName") != null }.toLong()
        return listOf(
            Count(
                "timetable_theme(사용자)",
                custom.size.toLong(),
                own.size.toLong() + downloads.size,
                "SELECT COUNT(*) FROM timetable_theme WHERE builtin_code IS NULL",
                "주인이 없는 테마는 빼고, 같은 사용자가 같은 게시 테마를 여러 번 받은 것은 하나로 합친다",
            ),
            Count(
                "published_theme",
                sourcePublished,
                published.size.toLong() + archives.size,
                "SELECT COUNT(*) FROM published_theme",
                "주인이 있는 게시 테마에, 원본과 내용이 달라진 다운로드마다(같은 내용은 하나) 비공개 스냅샷을 더한다",
            ),
            Count(
                "user_preference",
                themes
                    .map { it.userHex }
                    .distinct()
                    .size
                    .toLong(),
                themes
                    .map { it.userHex }
                    .distinct()
                    .size
                    .toLong(),
                "SELECT COUNT(*) FROM user_preference",
                "사용자 테마가 있는 사용자마다 기본 테마 하나",
            ),
        )
    }

    private fun timetableCounts(): List<Count> {
        val reminders = mongo.getCollection("timetableLectureReminder").find().toList()
        val reminderKeys = reminders.mapTo(HashSet()) { "${it.oid("timetableId")}\u0000${it.oid("timetableLectureId")}" }
        val liveReminderKeys = HashSet<String>()
        var timetables = 0L
        var lectures = 0L
        var sourceLectures = 0L
        val primaries = HashSet<String>()
        var sourcePrimaries = 0L
        overlappingTimetables = 0L
        mongo.getCollection("timetables").find().forEach { doc ->
            val items = doc.docs("lecture_list")
            sourceLectures += items.size
            if (doc.bool("is_primary")) sourcePrimaries++
            if (!exists(doc.oid("user_id"))) return@forEach
            timetables++
            lectures += items.size
            if (doc.bool("is_primary")) primaries += "${doc.oid("user_id")}|${doc.int("year")}|${doc.int("semester")}"
            items.forEach { item ->
                val key = "${doc.id()}\u0000${item.oid("_id")}"
                if (key in reminderKeys) liveReminderKeys += key
            }
            if (hasOverlap(items)) overlappingTimetables++
        }
        val liveReminders = reminders.filter { "${it.oid("timetableId")}\u0000${it.oid("timetableLectureId")}" in liveReminderKeys }
        return listOf(
            Count("timetable", count("timetables"), timetables, "SELECT COUNT(*) FROM timetable", "주인이 없는 시간표는 뺀다"),
            Count(
                "timetable 대표",
                sourcePrimaries,
                primaries.size.toLong(),
                "SELECT COUNT(*) FROM timetable WHERE is_primary",
                "(사용자, 학기)마다 대표는 하나. 여럿이면 가장 최근에 수정한 시간표만 대표로 남긴다",
            ),
            Count("timetable_lecture", sourceLectures, lectures, "SELECT COUNT(*) FROM timetable_lecture", "주인이 없는 시간표의 강의는 뺀다"),
            Count(
                "timetable_lecture_reminder",
                reminders.size.toLong(),
                liveReminders.size.toLong(),
                "SELECT COUNT(*) FROM timetable_lecture_reminder",
                "옮겨진 시간표 강의를 가리키는 리마인더만 옮긴다",
            ),
            Count(
                "timetable_lecture_reminder_schedule",
                reminders.sumOf { it.docs("schedules").size.toLong() },
                liveReminders.sumOf { it.docs("schedules").size.toLong() },
                "SELECT COUNT(*) FROM timetable_lecture_reminder_schedule",
                "옮겨진 리마인더의 스케줄",
            ),
        )
    }

    private fun userDataCounts(): List<Count> {
        val bookmarks = HashSet<String>()
        var sourceBookmarks = 0L
        mongo.getCollection("bookmarks").find().forEach { doc ->
            val refs = doc.docs("lectures").map { it.id() }.distinct()
            sourceBookmarks += refs.size
            val user = doc.oid("user_id")
            if (!exists(user)) return@forEach
            refs.mapNotNull(source.offeringOf::get).forEach { bookmarks += "$user\u0000$it" }
        }
        val vacancies = HashSet<String>()
        mongo.getCollection("vacancy_notifications").find().forEach { doc ->
            val user = doc.oid("userId")
            val offering = doc.oid("lectureId")?.let(source.offeringOf::get)
            if (exists(user) && offering != null) vacancies += "$user\u0000$offering"
        }
        var devices = 0L
        val activeRegistrations = HashSet<String>()
        mongo.getCollection("userDevice").find().forEach { doc ->
            val user = doc.oid("userId")
            if (!exists(user)) return@forEach
            devices++
            val registration = doc.str("fcmRegistrationId").orEmpty()
            if (active(user) && !doc.bool("isDeleted") && registration.isNotBlank()) activeRegistrations += registration
        }
        var preferences = 0L
        var sourcePreferences = 0L
        mongo.getCollection("pushPreference").find().forEach { doc ->
            val size = doc.docs("pushPreferences").size
            sourcePreferences += size
            if (exists(doc.oid("userId"))) preferences += size
        }
        val friends = HashSet<String>()
        mongo.getCollection("friend").find().forEach { doc ->
            val from = doc.oid("fromUserId")
            val to = doc.oid("toUserId")
            if (active(from) && active(to)) friends += listOf(from!!, to!!).sorted().joinToString("\u0000")
        }
        var submissions = 0L
        var types = 0L
        var answers = 0L
        mongo.getCollection("diarySubmission").find().forEach { doc ->
            if (!exists(doc.oid("userId"))) return@forEach
            submissions++
            types += doc.oids("dailyClassTypeIds").size
            answers += doc.docs("questionAnswers").size
        }
        return listOf(
            Count(
                "bookmark_lecture",
                sourceBookmarks,
                bookmarks.size.toLong(),
                "SELECT COUNT(*) FROM bookmark_lecture",
                "주인이 없거나 강의가 없는 항목을 빼고, 합쳐진 개설을 가리키는 같은 사용자의 항목은 하나로",
            ),
            Count(
                "vacancy_notification",
                count("vacancy_notifications"),
                vacancies.size.toLong(),
                "SELECT COUNT(*) FROM vacancy_notification",
                "주인·강의가 없는 것을 빼고, (사용자, 개설)마다 하나",
            ),
            Count("user_device", count("userDevice"), devices, "SELECT COUNT(*) FROM user_device", "주인이 없는 기기는 뺀다"),
            Count(
                "user_device 활성",
                mongo.getCollection("userDevice").countDocuments(Document("isDeleted", Document("\$ne", true))),
                activeRegistrations.size.toLong(),
                "SELECT COUNT(*) FROM user_device WHERE is_deleted = FALSE",
                "탈퇴한 사용자의 기기, 등록 토큰이 빈 기기, 같은 토큰의 이전 기기는 비활성화한다(토큰마다 활성 하나)",
            ),
            Count("push_preference", sourcePreferences, preferences, "SELECT COUNT(*) FROM push_preference", "주인이 없는 설정은 뺀다"),
            Count(
                "friend",
                count("friend"),
                friends.size.toLong(),
                "SELECT COUNT(*) FROM friend",
                "한쪽이라도 없거나 탈퇴한 관계를 빼고, 같은 사용자 쌍은 하나로",
            ),
            Count("diary_submission", count("diarySubmission"), submissions, "SELECT COUNT(*) FROM diary_submission", "주인이 없는 기록은 뺀다"),
            Count(
                "diary_submission_daily_class_type",
                types,
                types,
                "SELECT COUNT(*) FROM diary_submission_daily_class_type",
                "옮겨진 기록의 수업 유형",
            ),
            Count("diary_submission_answer", answers, answers, "SELECT COUNT(*) FROM diary_submission_answer", "옮겨진 기록의 답"),
        )
    }

    private fun notificationCounts(): List<Count> {
        var kept = 0L
        mongo.getCollection("notifications").find().forEach { doc ->
            val owner = doc.oid("user_id")
            if (owner == null || exists(owner)) kept++
        }
        return listOf(
            Count("notification", count("notifications"), kept, "SELECT COUNT(*) FROM notification", "주인이 지정됐는데 없는 알림은 뺀다(전체 알림은 둔다)"),
            Count(
                "notification deeplink의 ObjectId",
                0,
                0,
                "SELECT COUNT(*) FROM notification WHERE deeplink REGEXP '[0-9a-fA-F]{24}'",
                "deeplink의 v1 id는 v2 id로 바꾸거나 지운다",
            ),
        )
    }

    private fun evCounts(): List<Count> {
        val hexOf = users.keys

        fun userRows(table: String): Pair<Long, Long> {
            var total = 0L
            var kept = 0L
            ev.query("SELECT user_id FROM `$table`") { rs ->
                total++
                if (rs.getString(1) in hexOf) kept++
            }
            return total to kept
        }
        val evaluations = ev.queryForObject("SELECT COUNT(*) FROM lecture_evaluation", Long::class.java)!!
        val joined =
            ev.queryForObject(
                "SELECT COUNT(*) FROM lecture_evaluation le JOIN semester_lecture sl ON sl.id = le.semester_lecture_id",
                Long::class.java,
            )!!
        val (likes, keptLikes) = userRows("evaluation_like")
        val (reports, keptReports) = userRows("evaluation_report")
        val tags = ev.queryForObject("SELECT COUNT(*) FROM tag", Long::class.java)!!
        val keptTags =
            ev.queryForObject(
                "SELECT COUNT(*) FROM tag t JOIN tag_group g ON g.id = t.tag_group_id WHERE g.name <> 'main'",
                Long::class.java,
            )!!
        return listOf(
            Count("evaluation", evaluations, joined, "SELECT COUNT(*) FROM evaluation", "학기 강의가 있는 강의평을 모두 옮긴다(작성자가 없으면 user_id NULL)"),
            Count("evaluation_like", likes, keptLikes, "SELECT COUNT(*) FROM evaluation_like", "사용자가 없는 공감은 뺀다"),
            Count("evaluation_report", reports, keptReports, "SELECT COUNT(*) FROM evaluation_report", "사용자가 없는 신고는 뺀다"),
            Count("legacy_search_tag", tags, keptTags, "SELECT COUNT(*) FROM legacy_search_tag", "큐레이션 그룹(main)의 태그는 뺀다"),
        )
    }

    companion object {
        /** 서로 다른 강의 둘이 같은 요일에 시간이 겹치는가. 이관 뒤 v2 SQL([Invariants])과 같은 기준이다. */
        fun hasOverlap(items: List<Document>): Boolean {
            val slots =
                items.flatMapIndexed { index, item ->
                    item.docs("class_time_json").mapNotNull { time ->
                        val day = time.int("day") ?: return@mapNotNull null
                        val start = time.int("startMinute") ?: return@mapNotNull null
                        val end = time.int("endMinute") ?: return@mapNotNull null
                        Slot(index, day, start, end)
                    }
                }
            return slots.indices.any { i ->
                (i + 1 until slots.size).any { j ->
                    val a = slots[i]
                    val b = slots[j]
                    a.item != b.item && a.day == b.day && a.start < b.end && b.start < a.end
                }
            }
        }

        private class Slot(
            val item: Int,
            val day: Int,
            val start: Int,
            val end: Int,
        )
    }
}
