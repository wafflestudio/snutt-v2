package com.wafflestudio.snutt.core.test.perf

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.SplittableRandom
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 운영 분포(docs/perf-data-profile.md)를 비율대로 줄여 v2 스키마를 채운다. seed가 같으면 같은 데이터가 나온다.
 *
 * - 사용자 수는 운영의 1/10(22,000명)이다. 시간표·시간표 강의·알림처럼 사용자에 비례하는 테이블은 같이 줄어든다.
 * - 학기당 강의 수(정규학기 약 6,000개)는 줄이지 않는다. 강의 검색은 학기 단위로 돌기 때문에 학기 크기가 실행계획을 정한다.
 * - 강의평(16,613개)과 그 쏠림(snuev 이관 계정 하나가 94%)은 운영 값을 그대로 쓴다.
 * - 운영 최댓값 같은 극단 사용자(시간표 773개, 친구 115명 등)를 [PerfDataset]의 id로 따로 둔다.
 *
 * id는 생성기가 직접 매긴다(1부터 연속). 행 순서가 곧 id 순서라 학기 순서대로 강의를 넣으면 최신 강의의 id가 가장 크다.
 */
class PerfDataGenerator(
    private val connection: Connection,
    seed: Long = SEED,
) {
    companion object {
        const val SEED = 20261128L

        const val USERS = 22_000
        const val COURSES = 13_000
        const val EVALUATIONS = 16_613
        const val EVALUATED_COURSES = 2_993
        const val EVALUATION_LIKES = 934
        const val FRIEND_PAIRS = 750
        const val GLOBAL_NOTIFICATIONS = 300

        /** 운영 집계가 없다. v1 테마 공유(2024~)가 사용자 5%에 퍼졌다고 보고 사용자 수의 1/10로 잡는다. */
        const val PUBLISHED_THEMES = 2_200

        const val SNUEV_USER = 1L
        const val HEAVY_TIMETABLE_USER = 2L
        const val HEAVY_FRIEND_USER = 3L
        const val HEAVY_NOTIFICATION_USER = 4L
        const val HEAVY_DIARY_USER = 5L
        private const val FIRST_REGULAR_USER = 6

        val CURRENT = PerfSemester(2026, 3)
    }

    private val random = SplittableRandom(seed)

    private val semesters: List<PerfSemester> =
        (2021..2026).flatMap { year -> (1..4).map { PerfSemester(year, it) } }.filterNot { it.year == 2026 && it.semester == 4 }

    private val semesterIndex = semesters.withIndex().associate { (i, s) -> s to i }

    private data class CourseRow(
        val id: Long,
        val courseNumber: String,
        val instructor: String,
        val title: String,
        val titleEn: String,
        val department: Int,
        val classification: String,
        val category: String?,
        val academicYear: String,
        val credit: Int,
        val semesters: List<Int>,
        val sections: Int,
    )

    private data class LectureRef(
        val id: Long,
        val courseId: Long,
        val semester: Int,
        val slots: List<Slot>,
    )

    private data class Slot(
        val day: Int,
        val start: Int,
        val end: Int,
    )

    private val courses = mutableListOf<CourseRow>()
    private val lecturesBySemester = Array(semesters.size) { mutableListOf<LectureRef>() }
    private val lecturesById = HashMap<Long, LectureRef>()
    private val samplers = arrayOfNulls<ZipfSampler<LectureRef>>(semesters.size)
    private val userCreatedAt = LongArray(USERS + 1)
    private val counts = linkedMapOf<String, Int>()

    private var typicalUserId = 0L
    private var heavyFriendPartnerId = 0L
    private var typicalTimetableId = 0L
    private var heavyTimetableId = 0L
    private var popularCourseId = 0L
    private var popularLectureId = 0L
    private var liberalLectureCourseCount = 0

    fun generate(): PerfDataset {
        val started = System.nanoTime()
        connection.autoCommit = false
        connection.createStatement().use {
            it.execute("SET foreign_key_checks = 0")
            it.execute("SET unique_checks = 0")
        }
        insertCoursebooks()
        insertCoursesAndLectures()
        insertUsers()
        insertTimetables()
        insertEvaluations()
        insertFriends()
        insertNotifications()
        insertDiaries()
        insertBookmarksAndVacancies()
        insertThemesAndDevices()
        insertSugangSyncPending()
        connection.createStatement().use {
            it.execute("SET foreign_key_checks = 1")
            it.execute("SET unique_checks = 1")
        }
        connection.commit()
        connection.autoCommit = true
        analyzeAll()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return PerfDataset(
            generationMillis = elapsed,
            rowCounts = counts.toMap(),
            current = CURRENT,
            previous = semesters[semesters.lastIndex - 2],
            beforePrevious = semesters[semesters.lastIndex - 4],
            snuevUserId = SNUEV_USER,
            heavyTimetableUserId = HEAVY_TIMETABLE_USER,
            heavyTimetableId = heavyTimetableId,
            heavyFriendUserId = HEAVY_FRIEND_USER,
            heavyFriendPartnerId = heavyFriendPartnerId,
            heavyNotificationUserId = HEAVY_NOTIFICATION_USER,
            heavyDiaryUserId = HEAVY_DIARY_USER,
            typicalUserId = typicalUserId,
            typicalTimetableId = typicalTimetableId,
            popularCourseId = popularCourseId,
            popularLectureId = popularLectureId,
        )
    }

    // ---- 수강편람 ----

    private fun insertCoursebooks() {
        batch("coursebook", "id", "year", "semester", "created_at", "updated_at") { add ->
            semesters.forEachIndexed { i, s -> add(listOf(i + 1L, s.year, s.semester, semesterStart(i), semesterStart(i))) }
        }
    }

    private fun insertCoursesAndLectures() {
        val semesterWeights =
            semesters.map {
                if (it.semester == 1 || it.semester == 3) {
                    10.0
                } else if (it.semester == 2) {
                    1.5
                } else {
                    1.0
                }
            }
        repeat(COURSES) { index ->
            val id = index + 1L
            val department = zipfIndex(DEPARTMENTS.size, 0.8)
            val classification = weighted(CLASSIFICATIONS)
            val words = (1..2 + random.nextInt(2)).map { random.nextInt(TITLE_WORDS.size) }
            val offered = weightedWithoutReplacement(semesterWeights, offeredSemesterCount())
            courses +=
                CourseRow(
                    id = id,
                    courseNumber = "${1000 + department}.${(index + 1).toString().padStart(6, '0')}",
                    instructor = SURNAMES[random.nextInt(SURNAMES.size)] + GIVEN_NAMES[random.nextInt(GIVEN_NAMES.size)],
                    title = words.joinToString(" ") { TITLE_WORDS[it].first },
                    titleEn = words.joinToString(" ") { TITLE_WORDS[it].second },
                    department = department,
                    classification = classification,
                    category = if (classification == "교양") LIBERAL_CATEGORIES[random.nextInt(LIBERAL_CATEGORIES.size)] else null,
                    academicYear = weighted(ACADEMIC_YEARS),
                    credit = weighted(CREDITS),
                    semesters = offered.sorted(),
                    sections = sectionCount(),
                )
        }
        batch("course", "id", "course_number", "instructor", "title", "created_at", "updated_at") { add ->
            courses.forEach { add(listOf(it.id, it.courseNumber, it.instructor, it.title, semesterStart(0), semesterStart(0))) }
        }

        var lectureId = 0L
        var classTimeId = 0L
        val classTimes = mutableListOf<List<Any?>>()
        batch(
            "lecture",
            "id",
            "course_id",
            "year",
            "semester",
            "course_number",
            "lecture_number",
            "course_title",
            "instructor",
            "department",
            "academic_year",
            "category",
            "classification",
            "course_title_en",
            "instructor_en",
            "department_en",
            "academic_year_en",
            "category_en",
            "classification_en",
            "credit",
            "quota",
            "remark",
            "created_at",
            "updated_at",
        ) { add ->
            semesters.indices.forEach { semester ->
                val s = semesters[semester]
                courses.filter { semester in it.semesters }.forEach { course ->
                    repeat(course.sections) { section ->
                        lectureId++
                        val slots = slots()
                        val ref = LectureRef(lectureId, course.id, semester, slots)
                        lecturesBySemester[semester] += ref
                        lecturesById[lectureId] = ref
                        slots.forEach { slot ->
                            classTimeId++
                            classTimes +=
                                listOf(
                                    classTimeId,
                                    lectureId,
                                    slot.day,
                                    "${300 + random.nextInt(200)}-${100 + random.nextInt(400)}",
                                    slot.start,
                                    slot.end,
                                )
                        }
                        add(
                            listOf(
                                lectureId,
                                course.id,
                                s.year,
                                s.semester,
                                course.courseNumber,
                                (section + 1).toString().padStart(3, '0'),
                                course.title,
                                course.instructor,
                                DEPARTMENTS[course.department],
                                course.academicYear,
                                course.category,
                                course.classification,
                                course.titleEn,
                                "Prof. ${course.instructor}",
                                "Dept. ${course.department}",
                                course.academicYear,
                                course.category?.let { "Liberal $it" },
                                CLASSIFICATION_EN[course.classification],
                                course.credit,
                                20 + random.nextInt(180),
                                remark(),
                                semesterStart(semester),
                                semesterStart(semester),
                            ),
                        )
                    }
                }
            }
        }
        batch("lecture_class_time", "id", "lecture_id", "day", "place", "start_minute", "end_minute") { add -> classTimes.forEach(add) }
        liberalLectureCourseCount = courses.count { it.classification == "교양" }

        // 강의 하나가 여러 시간표에 담기는 쏠림. 운영 2025-2학기: 강의당 수강자 p50 12, p99 163, max 709(시간표 강의 141,803개)를
        // 1/10 규모에서 재현하는 지수 0.5의 Zipf다. 강의 순서를 섞어 인기 강의가 id 순서와 무관하게 흩어지게 한다.
        semesters.indices.forEach { samplers[it] = ZipfSampler(lecturesBySemester[it].shuffled(), 0.5) }
        popularLectureId = samplers[semesters.lastIndex]!!.top().id

        val current = semesters.lastIndex
        batch("lecture_registration_status", "lecture_id", "registration_count", "was_full", "updated_at") { add ->
            lecturesBySemester[current].forEach { add(listOf(it.id, random.nextInt(200), random.nextInt(10) == 0, semesterStart(current))) }
        }
    }

    // ---- 사용자 ----

    private fun insertUsers() {
        val start =
            LocalDate
                .of(2019, 3, 1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .epochSecond
        val end =
            LocalDate
                .of(2026, 9, 1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .epochSecond
        batch(
            "`user`",
            "id",
            "email",
            "is_email_verified",
            "nickname",
            "nickname_tag",
            "local_id",
            "local_pw",
            "active",
            "last_login_at",
            "notification_checked_at",
            "created_at",
            "updated_at",
        ) { add ->
            (1..USERS).forEach { id ->
                val created = if (id < FIRST_REGULAR_USER) start else start + random.nextLong(end - start)
                userCreatedAt[id] = created
                val createdAt = Timestamp.from(Instant.ofEpochSecond(created))
                add(
                    listOf(
                        id.toLong(),
                        "u$id@snu.ac.kr",
                        random.nextInt(10) < 7,
                        "닉네임${id % 5000}",
                        (id / 5000).toString().padStart(4, '0'),
                        "user$id",
                        "\$2a\$10\$perfperfperfperfperfperfperfperfperfperfperfperfperfpe",
                        id == 1 || random.nextInt(50) != 0,
                        createdAt,
                        createdAt,
                        createdAt,
                        createdAt,
                    ),
                )
            }
        }
        batch("legacy_access_token", "id", "user_id", "token_hash", "created_at", "updated_at") { add ->
            (1..USERS).forEach { add(listOf(it.toLong(), it.toLong(), "%064x".format(it), at(it), at(it))) }
        }
    }

    // ---- 시간표 ----

    private fun insertTimetables() {
        val current = semesters.lastIndex
        var timetableId = 0L
        var timetableLectureId = 0L
        val timetableLectures = mutableListOf<List<Any?>>()
        val reminderCandidates = mutableListOf<Pair<Long, LectureRef>>()

        fun addLectures(
            timetable: Long,
            semester: Int,
            count: Int,
            primary: Boolean,
        ): Int {
            val chosen = LinkedHashSet<Long>()
            repeat(count) { if (random.nextInt(100) < 5) chosen += -1L - it else chosen += samplers[semester]!!.sample(random).id }
            chosen.forEach { lecture ->
                timetableLectureId++
                val custom = lecture < 0
                timetableLectures +=
                    listOf(
                        timetableLectureId,
                        timetable,
                        if (custom) null else lecture,
                        random.nextInt(9),
                        if (custom) """{"courseTitle":"직접 추가한 일정"}""" else null,
                        semesterStart(semester),
                        semesterStart(semester),
                    )
                if (!custom && primary && semester == current) reminderCandidates += timetableLectureId to lecturesById.getValue(lecture)
            }
            return chosen.size
        }

        batch("timetable", "id", "user_id", "year", "semester", "title", "theme_id", "is_primary", "created_at", "updated_at") { add ->
            fun timetable(
                user: Long,
                semester: Int,
                title: String,
                primary: Boolean,
                lectures: Int,
            ): Int {
                timetableId++
                val s = semesters[semester]
                add(
                    listOf(
                        timetableId,
                        user,
                        s.year,
                        s.semester,
                        title,
                        1L + random.nextInt(6),
                        primary,
                        semesterStart(semester),
                        semesterStart(semester),
                    ),
                )
                return addLectures(timetableId, semester, lectures, primary)
            }

            // 운영 최댓값: 한 사용자가 한 학기에 시간표 773개.
            repeat(773) { i -> timetable(HEAVY_TIMETABLE_USER, current, "시간표 ${i + 1}", i == 0, 2) }
            heavyTimetableId = 1L
            (FIRST_REGULAR_USER..USERS).forEach { user ->
                activeSemesters().forEach { semester ->
                    val tables = weighted(TIMETABLES_PER_SEMESTER)
                    repeat(tables) { i ->
                        val lectures =
                            timetable(
                                user.toLong(),
                                semester,
                                if (i ==
                                    0
                                ) {
                                    "나의 시간표"
                                } else {
                                    "시간표 ${i + 1}"
                                },
                                i == 0,
                                lecturesPerTimetable(),
                            )
                        if (typicalUserId == 0L && semester == current && tables == 1 && lectures in 3..5) {
                            typicalUserId = user.toLong()
                            typicalTimetableId = timetableId
                        }
                    }
                }
            }
        }
        batch("timetable_lecture", "id", "timetable_id", "lecture_id", "palette_index", "overrides", "created_at", "updated_at") { add ->
            timetableLectures.forEach(add)
        }

        // 리마인더는 v2 신규 기능이라 운영 값이 없다. 이번 학기 대표 시간표 강의의 10%에 건다고 추정한다.
        var reminderId = 0L
        var scheduleId = 0L
        val schedules = mutableListOf<List<Any?>>()
        batch("timetable_lecture_reminder", "id", "timetable_lecture_id", "offset_minutes", "created_at", "updated_at") { add ->
            reminderCandidates.filter { random.nextInt(10) == 0 }.forEach { (timetableLecture, lecture) ->
                reminderId++
                val offset = listOf(-15, -10, -5, 0, 10)[random.nextInt(5)]
                add(listOf(reminderId, timetableLecture, offset, semesterStart(current), semesterStart(current)))
                lecture.slots.map { it.day to it.start + offset }.distinct().forEach { (day, minute) ->
                    scheduleId++
                    val notified = if (random.nextBoolean()) null else Timestamp.from(Instant.parse("2026-09-14T00:00:00Z"))
                    schedules += listOf(scheduleId, reminderId, day, minute, notified, semesterStart(current), semesterStart(current))
                }
            }
        }
        batch(
            "timetable_lecture_reminder_schedule",
            "id",
            "reminder_id",
            "day",
            "minute",
            "recent_notified_at",
            "created_at",
            "updated_at",
        ) { add ->
            schedules.forEach(add)
        }
    }

    /**
     * 사용자가 시간표를 가진 학기. 운영: 사용자당 시간표 p50 1, p90 9, p99 30, 평균 3.38, 사용자·학기당 평균 1.24.
     * 마지막 학기는 최근일수록 많고, 거기서 거슬러 올라가며 정규학기는 모두, 계절학기는 1/4 확률로 고른다.
     */
    private fun activeSemesters(): List<Int> {
        val count = weighted(SEMESTERS_PER_USER)
        var index = semesters.lastIndex - geometric(0.3)
        if (index < 0) index = 0
        val chosen = mutableListOf<Int>()
        while (index >= 0 && chosen.size < count) {
            val regular = semesters[index].semester == 1 || semesters[index].semester == 3
            if (regular || random.nextInt(4) == 0) chosen += index
            index--
        }
        return chosen
    }

    // ---- 강의평 ----

    private fun insertEvaluations() {
        // 운영: 강의평이 있는 과목 2,993개, 과목당 p50 2, p90 12, p99 52, max 212 → 로그정규(μ=ln 2, σ=1.4)로 맞는다.
        val popularity = courses.associate { it.id to it.semesters.size * it.sections + random.nextInt(3) }
        val evaluated =
            courses
                .sortedByDescending {
                    popularity.getValue(
                        it.id,
                    )
                }.take(EVALUATED_COURSES * 2)
                .shuffled()
                .take(EVALUATED_COURSES)
        val perCourse = evaluated.map { minOf(212, maxOf(1, ceil(exp(ln(2.0) + 1.4 * gaussian())).toInt())) }.toMutableList()
        val popularIndex = 0
        perCourse[popularIndex] = 212
        popularCourseId = evaluated[popularIndex].id
        // 총합을 운영 값(16,613)에 맞춘다. 많이 쓴 과목부터 깎거나 채운다.
        var diff = EVALUATIONS - perCourse.sum()
        var cursor = 1
        while (diff != 0) {
            val i = 1 + (cursor++ % (perCourse.size - 1))
            if (diff > 0) {
                perCourse[i]++
                diff--
            } else if (perCourse[i] > 1) {
                perCourse[i]--
                diff++
            }
        }
        // 실제 사용자가 쓴 강의평은 6%, 작성자 410명(작성자당 p50 1, p90 6). 나머지는 snuev 이관 계정 하나가 갖는다.
        val authors = (1..410).map { (FIRST_REGULAR_USER + random.nextInt(USERS - FIRST_REGULAR_USER)).toLong() }
        val authorSampler = ZipfSampler(authors, 1.0)
        var evaluationId = 0L
        batch(
            "evaluation",
            "id",
            "course_id",
            "user_id",
            "year",
            "semester",
            "content",
            "grade_satisfaction",
            "teaching_skill",
            "gains",
            "life_balance",
            "rating",
            "like_count",
            "is_hidden",
            "is_reported",
            "from_snuev",
            "created_at",
            "updated_at",
        ) { add ->
            evaluated.forEachIndexed { i, course ->
                repeat(perCourse[i]) {
                    evaluationId++
                    val snuev = random.nextInt(100) < 94
                    val semester = course.semesters[random.nextInt(course.semesters.size)]
                    val s = semesters[semester]
                    val likes =
                        when (random.nextInt(1000)) {
                            in 0..969 -> 0
                            in 970..994 -> 1 + random.nextInt(2)
                            else -> 3 + random.nextInt(21)
                        }
                    add(
                        listOf(
                            evaluationId,
                            course.id,
                            if (snuev) SNUEV_USER else authorSampler.sample(random),
                            s.year,
                            s.semester,
                            "성능 테스트용 강의평 본문 $evaluationId",
                            score(),
                            score(),
                            score(),
                            score(),
                            score(),
                            likes,
                            random.nextInt(1000) < 4,
                            random.nextInt(1000) < 6,
                            snuev,
                            semesterStart(semester),
                            semesterStart(semester),
                        ),
                    )
                }
            }
        }
        val likes = HashSet<Pair<Long, Long>>()
        while (likes.size <
            EVALUATION_LIKES
        ) {
            likes +=
                (1 + random.nextLong(evaluationId)) to (FIRST_REGULAR_USER + random.nextLong((USERS - FIRST_REGULAR_USER).toLong()))
        }
        batch("evaluation_like", "id", "evaluation_id", "user_id", "created_at", "updated_at") { add ->
            likes.forEachIndexed { i, (evaluation, user) -> add(listOf(i + 1L, evaluation, user, semesterStart(0), semesterStart(0))) }
        }
        connection.createStatement().use {
            it.executeUpdate(
                """
                UPDATE course c JOIN (
                    SELECT course_id, COUNT(*) n, AVG(grade_satisfaction) gs, AVG(teaching_skill) ts, AVG(gains) g,
                           AVG(life_balance) lb, AVG(rating) r
                    FROM evaluation WHERE is_hidden = FALSE GROUP BY course_id
                ) a ON a.course_id = c.id
                SET c.eval_count = a.n, c.avg_grade_satisfaction = a.gs, c.avg_teaching_skill = a.ts, c.avg_gains = a.g,
                    c.avg_life_balance = a.lb, c.avg_rating = a.r
                """.trimIndent(),
            )
            it.executeUpdate(
                "UPDATE course c JOIN (SELECT course_id, MAX(id) latest FROM lecture GROUP BY course_id) l ON l.course_id = c.id " +
                    "SET c.latest_lecture_id = l.latest",
            )
        }
    }

    // ---- 친구 ----

    private fun insertFriends() {
        // 운영: 친구 7,425쌍(사용자 220k) → 1/10. 친구가 있는 사용자의 친구 수 p50 1, p90 7, p99 19, max 115.
        val pairs = LinkedHashMap<Pair<Long, Long>, Boolean>()
        val socialUsers = (FIRST_REGULAR_USER..USERS).map { it.toLong() }.shuffled().take(500)
        val sampler = ZipfSampler(socialUsers, 0.9)
        while (pairs.size < FRIEND_PAIRS) {
            val a = sampler.sample(random)
            val b = sampler.sample(random)
            if (a != b) pairs.putIfAbsent(minOf(a, b) to maxOf(a, b), random.nextInt(200) != 0)
        }
        val heavyPartners = (FIRST_REGULAR_USER..USERS).map { it.toLong() }.shuffled().take(125)
        heavyPartners.forEachIndexed {
            i,
            partner,
            ->
            pairs[minOf(HEAVY_FRIEND_USER, partner) to maxOf(HEAVY_FRIEND_USER, partner)] = i < 115
        }
        heavyFriendPartnerId = heavyPartners.first()
        batch("friend", "id", "from_user_id", "to_user_id", "is_accepted", "created_at", "updated_at") { add ->
            pairs.entries.forEachIndexed { i, (pair, accepted) ->
                val (from, to) = if (random.nextBoolean()) pair else pair.second to pair.first
                add(listOf(i + 1L, from, to, accepted, at(from.toInt() + i), at(to.toInt() + i)))
            }
        }
    }

    // ---- 알림 ----

    private fun insertNotifications() {
        // 운영 집계가 없다(v1 Mongo 알림은 BigQuery에 없음). 전체 공지 300개(주 1회꼴), 사용자 30%가 평균 8개(빈자리·친구 알림), 극단 사용자 2,000개로 추정한다.
        var id = 0L
        val types = listOf("NORMAL", "LECTURE_VACANCY", "FRIEND", "LECTURE_UPDATE", "DIARY")
        batch("notification", "id", "user_id", "title", "message", "type", "created_at", "updated_at") { add ->
            fun notification(
                user: Long?,
                epochSecond: Long,
            ) {
                id++
                val createdAt = Timestamp.from(Instant.ofEpochSecond(epochSecond, (id % 1000) * 1_000_000))
                add(
                    listOf(
                        id,
                        user,
                        "알림 $id",
                        "알림 본문 $id",
                        if (user ==
                            null
                        ) {
                            "COURSEBOOK"
                        } else {
                            types[random.nextInt(types.size)]
                        },
                        createdAt,
                        createdAt,
                    ),
                )
            }
            val start = userCreatedAt[1]
            val end = Instant.parse("2026-11-20T00:00:00Z").epochSecond
            repeat(GLOBAL_NOTIFICATIONS) { notification(null, start + (end - start) * it / GLOBAL_NOTIFICATIONS) }
            repeat(2_000) { notification(HEAVY_NOTIFICATION_USER, start + random.nextLong(end - start)) }
            (FIRST_REGULAR_USER..USERS).forEach { user ->
                if (random.nextInt(10) < 3) {
                    val from = userCreatedAt[user]
                    repeat(minOf(200, 1 + geometric(1.0 / 8))) { notification(user.toLong(), from + random.nextLong(maxOf(1, end - from))) }
                }
            }
        }
    }

    // ---- 강의 일기장 ----

    private fun insertDiaries() {
        // v2 신규 기능이라 운영 값이 없다. 사용자 10%가 최근 두 학기에 평균 4개 제출, 제출당 답변 3개로 추정한다.
        batch("diary_daily_class_type", "id", "name", "active", "created_at", "updated_at") { add ->
            DAILY_CLASS_TYPES.forEachIndexed { i, name -> add(listOf(i + 1L, name, true, at(1), at(1))) }
        }
        batch(
            "diary_question",
            "id",
            "question",
            "short_question",
            "answer_list",
            "short_answer_list",
            "active",
            "created_at",
            "updated_at",
        ) { add ->
            (1..20).forEach {
                add(
                    listOf(it.toLong(), "질문 $it", "짧은 질문 $it", """["그렇다","아니다","보통"]""", """["O","X","-"]""", it <= 16, at(1), at(1)),
                )
            }
        }
        batch("diary_question_target", "id", "question_id", "daily_class_type_id", "created_at", "updated_at") { add ->
            (1..20).forEach { q ->
                repeat(2) { add(listOf((q - 1) * 2L + it + 1, q.toLong(), 1L + (q + it) % DAILY_CLASS_TYPES.size, at(1), at(1))) }
            }
        }
        val current = semesters.lastIndex
        val recent = listOf(current, current - 2)
        var submission = 0L
        val answers = mutableListOf<List<Any?>>()
        val types = mutableListOf<List<Any?>>()
        batch(
            "diary_submission",
            "id",
            "user_id",
            "year",
            "semester",
            "lecture_id",
            "course_title",
            "comment",
            "created_at",
            "updated_at",
        ) { add ->
            fun submit(user: Long) {
                submission++
                val semester = recent[random.nextInt(recent.size)]
                val lecture = samplers[semester]!!.sample(random)
                val createdAt = Timestamp.from(semesterStart(semester).toInstant().plusSeconds(random.nextLong(100L * 24 * 3600)))
                add(
                    listOf(
                        submission,
                        user,
                        semesters[semester].year,
                        semesters[semester].semester,
                        lecture.id,
                        "강의 ${lecture.id}",
                        "일기",
                        createdAt,
                        createdAt,
                    ),
                )
                repeat(3) {
                    answers +=
                        listOf(answers.size + 1L, submission, 1L + random.nextInt(16), random.nextInt(3), createdAt, createdAt)
                }
                repeat(
                    2,
                ) { types += listOf(types.size + 1L, submission, 1L + random.nextInt(DAILY_CLASS_TYPES.size), createdAt, createdAt) }
            }
            repeat(300) { submit(HEAVY_DIARY_USER) }
            (FIRST_REGULAR_USER..USERS).forEach { user ->
                if (random.nextInt(10) ==
                    0
                ) {
                    repeat(1 + random.nextInt(7)) { submit(user.toLong()) }
                }
            }
        }
        batch(
            "diary_submission_answer",
            "id",
            "submission_id",
            "question_id",
            "answer_index",
            "created_at",
            "updated_at",
        ) { add -> answers.forEach(add) }
        batch(
            "diary_submission_daily_class_type",
            "id",
            "submission_id",
            "daily_class_type_id",
            "created_at",
            "updated_at",
        ) { add -> types.forEach(add) }
    }

    // ---- 관심강좌·빈자리 알림 ----

    private fun insertBookmarksAndVacancies() {
        // 운영 집계가 없다. 관심강좌: 사용자 20%가 최근 세 학기 강의를 평균 5개. 빈자리 알림: 이번 학기 사용자 5%가 인기 강의 평균 3개.
        val current = semesters.lastIndex
        val recent = listOf(current, current - 2, current - 4)
        val bookmarks = LinkedHashSet<Pair<Long, Long>>()
        val vacancies = LinkedHashSet<Pair<Long, Long>>()
        (FIRST_REGULAR_USER..USERS).forEach { user ->
            if (random.nextInt(5) == 0) {
                repeat(1 + geometric(1.0 / 5)) { bookmarks += user.toLong() to samplers[recent[random.nextInt(3)]]!!.sample(random).id }
            }
            if (random.nextInt(20) ==
                0
            ) {
                repeat(1 + random.nextInt(5)) { vacancies += user.toLong() to samplers[current]!!.sample(random).id }
            }
        }
        batch("bookmark_lecture", "id", "user_id", "lecture_id", "created_at", "updated_at") { add ->
            bookmarks.forEachIndexed { i, (user, lecture) -> add(listOf(i + 1L, user, lecture, at(user.toInt()), at(user.toInt()))) }
        }
        batch("vacancy_notification", "id", "user_id", "lecture_id", "created_at", "updated_at") { add ->
            vacancies.forEachIndexed { i, (user, lecture) -> add(listOf(i + 1L, user, lecture, at(user.toInt()), at(user.toInt()))) }
        }
    }

    // ---- 수강편람 동기화 남은 일 ----

    /**
     * 운영 집계가 없다(v2에서 생긴 테이블이고 동기화 한 번 안에서만 차 있다). 학기 초처럼 이번 학기 강의 1/4이 한꺼번에 바뀐 동기화가
     * 시간표 반영 도중이라고 본다: 바뀐 강의를 담은 이번 학기 시간표마다 한 행, 그 절반은 반영을 마쳤다(processed).
     */
    private fun insertSugangSyncPending() {
        connection.createStatement().use { statement ->
            counts["sugang_sync_pending_lecture"] =
                statement.executeUpdate(
                    "INSERT INTO sugang_sync_pending_lecture " +
                        "(id, lecture_id, class_times_changed, changed_labels, changed_labels_en, notifiable, created_at) " +
                        "SELECT id, id, TRUE, '강의 시간/장소', 'Class time/place', TRUE, '2026-08-20 00:00:00' FROM lecture " +
                        "WHERE year = ${CURRENT.year} AND semester = ${CURRENT.semester} AND id % 4 = 0",
                )
            counts["sugang_sync_pending_timetable"] =
                statement.executeUpdate(
                    "INSERT IGNORE INTO sugang_sync_pending_timetable (pending_lecture_id, timetable_id, notify, processed) " +
                        "SELECT p.id, tl.timetable_id, TRUE, tl.timetable_id % 2 = 0 FROM sugang_sync_pending_lecture p " +
                        "JOIN timetable_lecture tl ON tl.lecture_id = p.lecture_id",
                )
        }
    }

    // ---- 테마·기기 ----

    private fun insertThemesAndDevices() {
        val palette =
            """[{"backgroundColor":"#E54459","foregroundColor":"#ffffff"},""" +
                """{"backgroundColor":"#F58D3D","foregroundColor":"#ffffff"}]"""
        var themeId = 6L
        val themes = mutableListOf<List<Any?>>()
        (FIRST_REGULAR_USER..USERS).forEach { user ->
            if (random.nextInt(20) ==
                0
            ) {
                repeat(1 + random.nextInt(3)) {
                    themes +=
                        listOf(++themeId, user.toLong(), "테마 $themeId", palette, null, at(user), at(user))
                }
            }
        }
        val published =
            (1..PUBLISHED_THEMES).map {
                listOf(
                    it.toLong(),
                    themes[random.nextInt(themes.size)][1],
                    "공유 테마 $it",
                    palette,
                    random.nextInt(10) != 0,
                    (10_000 / it).toLong(),
                    at(it),
                    at(it),
                )
            }
        val downloads = LinkedHashSet<Pair<Long, Long>>()
        while (downloads.size <
            5_000
        ) {
            downloads +=
                (FIRST_REGULAR_USER + random.nextLong((USERS - FIRST_REGULAR_USER).toLong())) to (1L + zipfIndex(PUBLISHED_THEMES, 1.0))
        }
        downloads.forEach { (user, publication) ->
            themes +=
                listOf(++themeId, user, "받은 테마 $publication", palette, publication, at(user.toInt()), at(user.toInt()))
        }
        batch(
            "published_theme",
            "id",
            "author_id",
            "name",
            "colors",
            "listed",
            "download_count",
            "created_at",
            "updated_at",
        ) { add -> published.forEach(add) }
        batch(
            "timetable_theme",
            "id",
            "user_id",
            "name",
            "colors",
            "publication_id",
            "created_at",
            "updated_at",
        ) { add -> themes.forEach(add) }
        batch("user_preference", "user_id", "default_theme_id") { add ->
            (FIRST_REGULAR_USER..USERS).forEach { if (random.nextInt(33) == 0) add(listOf(it.toLong(), 1L + random.nextInt(6))) }
        }
        batch("user_device", "id", "user_id", "os_type", "fcm_registration_id", "language", "created_at", "updated_at") { add ->
            (1..USERS).forEach {
                if (random.nextInt(10) <
                    6
                ) {
                    add(listOf(it.toLong(), it.toLong(), if (random.nextBoolean()) "ios" else "android", "fcm-$it", "KO", at(it), at(it)))
                }
            }
        }
        batch("push_preference", "id", "user_id", "type", "is_enabled", "created_at", "updated_at") { add ->
            (FIRST_REGULAR_USER..USERS).forEach {
                if (random.nextInt(20) ==
                    0
                ) {
                    add(listOf(it.toLong(), it.toLong(), "NORMAL", false, at(it), at(it)))
                }
            }
        }
    }

    // ---- 분포 ----

    private fun offeredSemesterCount(): Int =
        when (random.nextInt(100)) {
            in 0..34 -> 1
            in 35..54 -> 2
            in 55..74 -> 3 + random.nextInt(3)
            in 75..89 -> 6 + random.nextInt(5)
            else -> 11 + random.nextInt(semesters.size - 10)
        }

    private fun sectionCount(): Int =
        when (random.nextInt(100)) {
            in 0..89 -> 1
            in 90..97 -> 2 + random.nextInt(3)
            else -> 5 + random.nextInt(11)
        }

    /** 운영 시간표당 강의 수: p50 2, p90 8, p99 11, max 47. */
    private fun lecturesPerTimetable(): Int =
        when (random.nextInt(1000)) {
            in 0..79 -> 0
            in 80..229 -> 1
            in 230..499 -> 2
            in 500..749 -> 3 + random.nextInt(2)
            in 750..929 -> 5 + random.nextInt(4)
            in 930..989 -> 9 + random.nextInt(3)
            in 990..998 -> 12 + random.nextInt(9)
            else -> 47
        }

    private fun slots(): List<Slot> {
        val count =
            when (random.nextInt(100)) {
                in 0..14 -> 1
                in 15..84 -> 2
                else -> 3
            }
        val start = listOf(540, 630, 660, 720, 810, 840, 930, 960, 1020, 1110)[random.nextInt(10)]
        val length = listOf(50, 75, 110)[random.nextInt(3)]
        val first = random.nextInt(5)
        return (0 until count).map { Slot(day = (first + it * 2) % 6, start = start, end = start + length) }.distinctBy { it.day }
    }

    private fun remark(): String? =
        when (random.nextInt(100)) {
            in 0..91 -> null
            in 92..94 -> "ⓔ 영어강의"
            in 95..96 -> "ⓜⓞ 군휴학생 수강 가능"
            else -> "권장과목 안내"
        }

    private fun score(): Double = 1.0 + random.nextInt(9) * 0.5

    private fun geometric(p: Double): Int = (ln(1 - random.nextDouble()) / ln(1 - p)).toInt()

    private fun gaussian(): Double {
        val u = 1 - random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    private fun zipfIndex(
        n: Int,
        s: Double,
    ): Int = ZipfSampler((0 until n).toList(), s).sample(random)

    private fun <T> weighted(options: List<Pair<T, Int>>): T {
        var r = random.nextInt(options.sumOf { it.second })
        options.forEach { (value, weight) ->
            if (r < weight) return value
            r -= weight
        }
        return options.last().first
    }

    private fun weightedWithoutReplacement(
        weights: List<Double>,
        count: Int,
    ): List<Int> {
        val remaining = weights.indices.toMutableList()
        val chosen = mutableListOf<Int>()
        repeat(minOf(count, weights.size)) {
            val total = remaining.sumOf { weights[it] }
            var r = random.nextDouble() * total
            val pick = remaining.first { i -> (r < weights[i]).also { if (!it) r -= weights[i] } }
            chosen += pick
            remaining -= pick
        }
        return chosen
    }

    private fun semesterStart(index: Int): Timestamp {
        val s = semesters[index]
        val month =
            when (s.semester) {
                1 -> 3
                2 -> 6
                3 -> 9
                else -> 12
            }
        return Timestamp.from(LocalDate.of(s.year, month, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
    }

    private fun at(seed: Int): Timestamp = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(seed * 37L))

    private fun <T> List<T>.shuffled(): List<T> {
        val list = toMutableList()
        for (i in list.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
        return list
    }

    // ---- JDBC batch ----

    /** rewriteBatchedStatements로 다중 행 INSERT가 된다. [rows]가 add를 부를 때마다 행이 쌓이고 5,000행마다 실행한다. */
    private fun batch(
        table: String,
        vararg columns: String,
        rows: ((List<Any?>) -> Unit) -> Unit,
    ) {
        val sql = "INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})"
        var count = 0
        connection.prepareStatement(sql).use { statement ->
            rows { values ->
                bind(statement, values)
                statement.addBatch()
                if (++count % 5_000 == 0) statement.executeBatch()
            }
            statement.executeBatch()
        }
        counts[table.trim('`')] = count
    }

    private fun bind(
        statement: PreparedStatement,
        values: List<Any?>,
    ) {
        values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
    }

    private fun analyzeAll() {
        connection.createStatement().use { statement ->
            val tables = mutableListOf<String>()
            statement
                .executeQuery(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                ).use {
                    while (it.next()) tables += it.getString(1)
                }
            statement.execute("ANALYZE TABLE ${tables.joinToString { "`$it`" }}")
        }
    }

    /** 순위 r(1부터)의 가중치가 r^-s인 표본 추출기. 누적합을 미리 만들어 이분 탐색한다. */
    private class ZipfSampler<T>(
        private val items: List<T>,
        s: Double,
    ) {
        private val cumulative: DoubleArray =
            DoubleArray(items.size).also { acc ->
                var sum = 0.0
                items.indices.forEach { i ->
                    sum += (i + 1.0).pow(-s)
                    acc[i] = sum
                }
            }

        fun top(): T = items.first()

        fun sample(random: SplittableRandom): T {
            val r = random.nextDouble() * cumulative.last()
            var index = cumulative.binarySearch(r)
            if (index < 0) index = -index - 1
            return items[minOf(index, items.lastIndex)]
        }
    }
}

data class PerfSemester(
    val year: Int,
    val semester: Int,
)

private val DEPARTMENTS =
    (1..80).map {
        listOf("컴퓨터공학부", "경영학과", "경제학부", "기계공학부", "전기·정보공학부", "수리과학부", "물리천문학부", "화학부", "국어국문학과", "영어영문학과")[it % 10] +
            if (it >= 10) " ${it / 10}" else ""
    }

private val CLASSIFICATIONS = listOf("전선" to 35, "교양" to 25, "전필" to 15, "일선" to 10, "교직" to 3, "논문" to 2, "대학원" to 10)
private val CLASSIFICATION_EN =
    mapOf(
        "전선" to "Major Elective",
        "교양" to "General Education",
        "전필" to "Major Requirement",
        "일선" to "General Elective",
        "교직" to "Teacher Training",
        "논문" to "Thesis",
        "대학원" to "Graduate",
    )
private val LIBERAL_CATEGORIES = listOf("외국어", "과학적 사고와 실험", "체육", "인간과 사회", "문화와 예술", "역사와 철학")
private val ACADEMIC_YEARS = listOf("1학년" to 20, "2학년" to 20, "3학년" to 20, "4학년" to 15, "석사" to 15, "박사" to 5, "석박사통합" to 5)
private val CREDITS = listOf(3 to 60, 2 to 15, 1 to 10, 4 to 10, 0 to 3, 5 to 2)
private val SEMESTERS_PER_USER =
    listOf(
        1 to 62,
        2 to 11,
        3 to 6,
        4 to 4,
        5 to 4,
        6 to 3,
        7 to 3,
        8 to 2,
        10 to 2,
        12 to 1,
        14 to 1,
        18 to 1,
        23 to 1,
    )
private val TIMETABLES_PER_SEMESTER = listOf(1 to 82, 2 to 13, 3 to 3, 4 to 1, 5 to 1)
private val DAILY_CLASS_TYPES = listOf("수업듣기", "발표", "토론", "실습", "시험", "퀴즈", "팀플", "휴강")
private val SURNAMES = listOf("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권")
private val GIVEN_NAMES = listOf("민준", "서연", "도윤", "지우", "하준", "서윤", "현우", "지민", "건우", "수아", "우진", "예은", "선호", "하진", "태민")
private val TITLE_WORDS =
    listOf(
        "운영체제" to "Operating Systems",
        "자료구조" to "Data Structures",
        "알고리즘" to "Algorithms",
        "컴퓨터" to "Computer",
        "프로그래밍" to "Programming",
        "미적분학" to "Calculus",
        "선형대수" to "Linear Algebra",
        "통계학" to "Statistics",
        "경제학" to "Economics",
        "원론" to "Principles",
        "회계" to "Accounting",
        "마케팅" to "Marketing",
        "재무" to "Finance",
        "물리학" to "Physics",
        "화학" to "Chemistry",
        "생물학" to "Biology",
        "실험" to "Laboratory",
        "글쓰기" to "Writing",
        "대학영어" to "College English",
        "한국사" to "Korean History",
        "철학" to "Philosophy",
        "윤리" to "Ethics",
        "심리학" to "Psychology",
        "사회학" to "Sociology",
        "정치학" to "Political Science",
        "법학" to "Law",
        "기초" to "Introduction",
        "고급" to "Advanced",
        "세미나" to "Seminar",
        "특강" to "Special Topics",
        "설계" to "Design",
        "이론" to "Theory",
        "응용" to "Applied",
        "분석" to "Analysis",
        "시스템" to "Systems",
        "네트워크" to "Networks",
        "데이터베이스" to "Databases",
        "인공지능" to "Artificial Intelligence",
        "기계학습" to "Machine Learning",
        "전자기학" to "Electromagnetics",
        "열역학" to "Thermodynamics",
        "유체역학" to "Fluid Mechanics",
        "고체역학" to "Solid Mechanics",
        "회로" to "Circuits",
        "신호" to "Signals",
        "제어" to "Control",
        "미술" to "Art",
        "음악" to "Music",
        "체육" to "Physical Education",
        "문학" to "Literature",
    )
