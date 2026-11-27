package com.wafflestudio.snutt.parity.seed

import com.wafflestudio.snutt.parity.seed.SeedBuilder.Entry
import com.wafflestudio.snutt.parity.seed.SeedBuilder.Social
import com.wafflestudio.snutt.parity.seed.SeedBuilder.Time
import java.time.Instant

/**
 * parity 시드. 구 앱의 실제 사용 흐름을 덮도록 만들었고, 레거시의 이상한 모양 데이터(★)는
 * migration step의 예외 처리(MigrationSupport.ResolutionReasons)와 docs/v1-compat-audit.md에서 골랐다.
 *
 * 학기 표기는 v1 그대로 1=봄, 2=여름, 3=가을, 4=겨울이다.
 */
object ParitySeed {
    /** 시드 시각. 시각 필드는 형식만 비교하므로 값 자체는 결과에 영향을 주지 않는다. */
    val BASE_TIME: Instant = Instant.parse("2026-08-20T03:00:00Z")

    fun build(): LegacyData =
        SeedBuilder(BASE_TIME)
            .apply {
                catalog()
                val lectures = lectures()
                val users = users()
                val aliceMain = themesAndTimetables(users, lectures)
                social(users)
                userData(users, lectures, aliceMain)
                ev(users, lectures)
                listOf(lectures.arch, lectures.online, lectures.discrete).forEach(::mintedCourse)
            }.build()

    class Lectures(
        val os: SeedBuilder.SeedLecture,
        val ds: SeedBuilder.SeedLecture,
        val arch: SeedBuilder.SeedLecture,
        val writing: SeedBuilder.SeedLecture,
        val online: SeedBuilder.SeedLecture,
        val osSpring: SeedBuilder.SeedLecture,
        val discrete: SeedBuilder.SeedLecture,
    )

    class Users(
        val alice: SeedUser,
        val bob: SeedUser,
        val carol: SeedUser,
        val dave: SeedUser,
        val erin: SeedUser,
        val frankOld: SeedUser,
        val frankNew: SeedUser,
        val grace: SeedUser,
        val heidi: SeedUser,
    )

    private fun SeedBuilder.catalog() {
        coursebook(2025, 3, at(-300 * 24 * 60))
        coursebook(2026, 1, at(-150 * 24 * 60))
        coursebook(2026, 2, at(-60 * 24 * 60))
        coursebook(2026, 3, at(0))
        clientConfig("vacancy_notification", """{"visible":false}""", createdAt = at(-1000))
        // ★ 같은 이름의 설정이 여러 개(audit #36: v1은 첫 번째, v2는 최신)
        clientConfig(
            "vacancy_notification",
            """{"visible":true}""",
            minIosVersion = "3.0.0",
            minAndroidVersion = "3.0.0",
            createdAt = at(1),
        )
        clientConfig("theme_market", """{"enabled":true}""")
        building("301", "제1공학관", "College of Engineering Bldg.1", "GWANAK", 37.4500, 126.9520)
        building("302", "제2공학관", "College of Engineering Bldg.2", "GWANAK", 37.4488, 126.9525)
    }

    private fun SeedBuilder.lectures(): Lectures {
        val os =
            lecture(
                "os",
                2026,
                3,
                "M1522.000800",
                "001",
                "운영체제",
                "김운영",
                listOf(Time(0, "301-101", 600, 675), Time(2, "301-101", 600, 675)),
                classification = "전필",
                english =
                    mapOf(
                        "course_title_en" to "Operating Systems",
                        "department_en" to "Department of Computer Science and Engineering",
                        "classification_en" to "Major Requirement",
                        // ★ 수강편람 영문 학년이 숫자로만 온 경우(LectureStep.academicYearEn이 "Year N"으로 고친다)
                        "academic_year_en" to "3",
                    ),
            )
        val ds =
            lecture(
                "ds",
                2026,
                3,
                "4190.209",
                "001",
                "자료구조",
                "이자료",
                listOf(Time(1, "302-208", 540, 615), Time(3, "302-208", 540, 615)),
                academicYear = "2학년",
            )
        val arch =
            lecture(
                "arch",
                2026,
                3,
                "4190.308",
                "001",
                "컴퓨터구조",
                "박구조",
                listOf(Time(0, "301-102", 630, 705)),
                remark = "영어강의",
            )
        val writing =
            lecture(
                "writing",
                2026,
                3,
                "L0440.000600",
                "015",
                "대학 글쓰기 1",
                // ★ 강사 미정은 빈 문자열로 들어온다
                "",
                listOf(Time(4, "1-101", 780, 890)),
                credit = 2,
                classification = "교양",
                department = "기초교육원",
                academicYear = "1학년",
                category = "사고와 표현",
                categoryPre2025 = "학문의 기초",
                freshmanQuota = 10,
            )
        val online =
            lecture(
                "online",
                2026,
                3,
                "M3239.000100",
                "001",
                "인공지능 개론",
                "최인공",
                // 원격 강의: 수업 시간 없음
                emptyList(),
                quota = 120,
                registrationCount = 120,
                wasFull = true,
                // ★ 오래된 문서에 남은 필드(v1 데이터 클래스에 없음)
                extra = mapOf("class_time_mask" to listOf(0, 0, 0, 0, 0, 0, 0), "class_time" to ""),
            )
        val semester2026Autumn = listOf(os, ds, arch, writing, online)
        tagList(2026, 3, semester2026Autumn)

        val osSpring =
            lecture("os-spring", 2026, 1, "M1522.000800", "001", "운영체제", "김운영", listOf(Time(1, "301-101", 600, 675)))
        tagList(2026, 1, listOf(osSpring))

        val discrete = lecture("discrete", 2025, 3, "4190.101", "001", "이산수학", "정이산", listOf(Time(2, "302-105", 840, 915)))
        // ★ 같은 (연도, 학기, 교과목번호, 분반)이 두 번(LECTURE_DUPLICATE)
        lecture("discrete-dup", 2025, 3, "4190.101", "001", "이산수학", "정이산", listOf(Time(2, "302-105", 840, 915)))
        tagList(2025, 3, listOf(discrete))
        return Lectures(os, ds, arch, writing, online, osSpring, discrete)
    }

    private fun SeedBuilder.users(): Users {
        val alice =
            user(
                "alice",
                localId = "alice",
                email = "alice@snu.ac.kr",
                isEmailVerified = true,
                nickname = "앨리스#1234",
                regDate = at(-500 * 24 * 60),
            )
        val bob =
            user(
                "bob",
                localId = "bob",
                email = "bob@snu.ac.kr",
                isEmailVerified = true,
                nickname = "밥#0042",
                social = Social(fbId = "fb-bob", fbName = "김밥"),
            )
        // ★ isEmailVerified 필드가 없는 오래된 소셜 계정(audit 요약 4)
        val carol =
            user(
                "carol",
                localId = null,
                email = "carol@gmail.com",
                isEmailVerified = null,
                nickname = "캐롤#7777",
                social =
                    Social(
                        kakaoSub = "kakao-carol",
                        kakaoEmail = "carol@kakao.com",
                        googleSub = "google-carol",
                        googleEmail = "carol@gmail.com",
                    ),
            )
        // ★ 이메일이 없는 Apple 계정
        val dave =
            user(
                "dave",
                localId = null,
                email = null,
                isEmailVerified = false,
                nickname = "데이브#0001",
                social = Social(appleSub = "apple-dave", appleEmail = "dave@privaterelay.appleid.com"),
            )
        // ★ 탈퇴한 계정
        val erin = user("erin", localId = "erin", email = "erin@snu.ac.kr", isEmailVerified = true, nickname = "에린#5555", active = false)
        // ★ 같은 아이디를 쓰는 활성 계정 둘(LOCAL_ID_DUPLICATE). 최종 로그인이 최신인 쪽이 아이디를 갖는다.
        val frankOld =
            user(
                "frank-old",
                localId = "frank",
                email = "frank@snu.ac.kr",
                isEmailVerified = false,
                nickname = "프랭크#1000",
                lastLogin = at(-100),
            )
        val frankNew =
            user(
                "frank-new",
                localId = "frank",
                email = "frank2@snu.ac.kr",
                isEmailVerified = false,
                nickname = "프랭크#2000",
                lastLogin = at(100),
            )
        // ★ 같은 credentialHash를 가진 활성 계정 둘(LEGACY_TOKEN_AMBIGUOUS)
        val grace =
            user(
                "grace",
                localId = "grace",
                email = "grace@snu.ac.kr",
                isEmailVerified = true,
                nickname = "그레이스#3000",
                token = "parity-token-shared",
            )
        val heidi =
            user(
                "heidi",
                localId = "heidi",
                email = "heidi@snu.ac.kr",
                isEmailVerified = true,
                nickname = "하이디#4000",
                token = "parity-token-shared",
            )
        return Users(alice, bob, carol, dave, erin, frankOld, frankNew, grace, heidi)
    }

    private fun SeedBuilder.themesAndTimetables(
        users: Users,
        lectures: Lectures,
    ): SeedBuilder.SeedTimetable {
        val aliceTheme =
            theme(
                "alice-custom",
                users.alice,
                "내 테마",
                listOf("#1D99E8" to "#FFFFFF", "#F58D3D" to "#FFFFFF", "#7AC73F" to "#FFFFFF"),
                updatedAt = at(10),
            )
        val bobPublished =
            theme(
                "bob-published",
                users.bob,
                "밥 테마",
                listOf("#222222" to "#EEEEEE", "#444444" to "#DDDDDD"),
                status = "PUBLISHED",
                publishName = "밥의 공개 테마",
                downloads = 3,
            )
        theme(
            "alice-downloaded",
            users.alice,
            "밥의 공개 테마",
            listOf("#222222" to "#EEEEEE", "#444444" to "#DDDDDD"),
            status = "DOWNLOADED",
            origin = bobPublished to users.bob,
            updatedAt = at(5),
        )

        val aliceMain =
            timetable(
                "alice-main",
                users.alice,
                year = 2026,
                semester = 3,
                title = "나의 시간표",
                isPrimary = true,
                updatedAt = at(30),
                entries =
                    listOf(
                        Entry("os", lectures.os, colorIndex = 1),
                        Entry("ds", lectures.ds, colorIndex = 2),
                        Entry(
                            "club",
                            null,
                            colorIndex = 0,
                            color = "#123456" to "#FFFFFF",
                            overrides =
                                mapOf(
                                    "course_title" to "동아리 회의",
                                    "class_time_json" to listOf(Time(4, "학생회관", 1080, 1200).toDocument()),
                                    "credit" to 0L,
                                ),
                        ),
                    ),
            )
        reminder(aliceMain, "os", 10, listOf(0 to 590, 2 to 590))
        timetable(
            "alice-plan-b",
            users.alice,
            year = 2026,
            semester = 3,
            title = "플랜 B",
            isPrimary = false,
            themeId = aliceTheme.id,
            updatedAt = at(20),
            entries =
                listOf(
                    Entry("arch", lectures.arch, colorIndex = 3),
                    Entry("writing", lectures.writing, colorIndex = 1),
                    Entry(
                        "online",
                        lectures.online,
                        colorIndex = 2,
                        overrides =
                            mapOf(
                                "course_title" to "AI 개론(내가 바꿈)",
                                "instructor" to "최교수",
                            ),
                    ),
                ),
        )
        // ★ is_primary 필드가 없는 오래된 시간표
        timetable(
            "alice-spring",
            users.alice,
            year = 2026,
            semester = 1,
            title = "봄 학기",
            isPrimary = null,
            theme = 2,
            updatedAt = at(-100 * 24 * 60),
            entries = listOf(Entry("os", lectures.osSpring, colorIndex = 4)),
        )
        timetable(
            "bob-main",
            users.bob,
            year = 2026,
            semester = 3,
            title = "밥의 시간표",
            isPrimary = true,
            updatedAt = at(40),
            entries =
                listOf(
                    Entry("os", lectures.os, colorIndex = 1),
                    // ★ 범위 밖 팔레트 번호(PALETTE_INDEX_OUT_OF_RANGE)
                    Entry("writing", lectures.writing, colorIndex = 12),
                ),
        )
        timetable(
            "carol-main",
            users.carol,
            year = 2026,
            semester = 3,
            title = "캐롤",
            isPrimary = true,
            entries =
                listOf(
                    Entry("writing", lectures.writing, colorIndex = 5),
                    // ★ 한쪽 색만 있는 직접 만든 강의(INVALID_CUSTOM_COLOR)
                    Entry(
                        "custom",
                        null,
                        colorIndex = 0,
                        color = "#ABCDEF" to null,
                        overrides =
                            mapOf(
                                "course_title" to "스터디",
                                "class_time_json" to listOf(Time(5, "", 600, 720).toDocument()),
                            ),
                    ),
                ),
        )
        // ★ 같은 학기에 제목이 같고 둘 다 대표인 시간표(TIMETABLE_TITLE_DUPLICATE, PRIMARY_TIMETABLE_DUPLICATE)
        timetable("dave-a", users.dave, year = 2026, semester = 3, title = "시간표", isPrimary = true, updatedAt = at(1))
        timetable("dave-b", users.dave, year = 2026, semester = 3, title = "시간표", isPrimary = true, updatedAt = at(2))
        // ★ 탈퇴 계정의 시간표, 사용자가 없는 시간표(TIMETABLE_USER_MISSING)
        timetable("erin-main", users.erin, year = 2026, semester = 3, title = "에린", isPrimary = true)
        timetable("orphan", null, ownerId = objectId(), year = 2026, semester = 3, title = "주인 없음", isPrimary = true)
        timetable("grace-main", users.grace, year = 2026, semester = 3, title = "그레이스", isPrimary = true)
        // 색 자동 선택 비교용: 팔레트 1~8번을 하나씩 쓴 시간표. 다음 강의는 두 서버 모두 무작위 없이 9번을 받아야 한다.
        timetable(
            "frank-colors",
            users.frankNew,
            year = 2026,
            semester = 3,
            title = "색 시간표",
            isPrimary = true,
            entries =
                (1..8).map { index ->
                    Entry(
                        "memo$index",
                        null,
                        colorIndex = index,
                        overrides = mapOf("course_title" to "메모 $index", "class_time_json" to emptyList<Any>(), "credit" to 0L),
                    )
                },
        )
        return aliceMain
    }

    private fun SeedBuilder.social(users: Users) {
        friend("alice-bob", users.alice, users.bob, accepted = true, toDisplayName = "밥 친구", createdAt = at(-10))
        friend("carol-alice", users.carol, users.alice, accepted = false, createdAt = at(-5))
        friend("dave-alice", users.dave, users.alice, accepted = true, createdAt = at(-3))
        // ★ 탈퇴 계정과의 친구 관계(FRIEND_USER_INACTIVE)
        friend("alice-erin", users.alice, users.erin, accepted = true, createdAt = at(-20))
    }

    private fun SeedBuilder.userData(
        users: Users,
        lectures: Lectures,
        aliceMain: SeedBuilder.SeedTimetable,
    ) {
        // ★ 수강편람에서 사라진 강의를 가리키는 북마크(BOOKMARK_LECTURE_MISSING)
        bookmark(users.alice, 2026, 3, listOf(lectures.writing, lectures.ds))
        bookmark(users.bob, 2026, 3, listOf(lectures.arch, ghostLecture("gone", 2026, 3, "4190.999", "폐강된 강의")))
        vacancyNotification(users.alice, lectures.online)
        pushPreference(users.alice, "NORMAL" to true, "LECTURE_UPDATE" to false, "VACANCY_NOTIFICATION" to true, "DIARY" to true)
        device(users.alice, "IOS", "fcm-alice")
        device(users.bob, "ANDROID", "fcm-bob")

        notification("global", null, "공지", "SNUTT 점검 안내", type = 0, createdAt = at(1))
        notification(
            "alice-update",
            users.alice,
            "강의 변경",
            "운영체제 강의실이 바뀌었습니다",
            type = 2,
            createdAt = at(2),
            // ★ ObjectId가 든 deeplink(NotificationStep이 새 id로 바꾼다)
            deeplink = "snutt://timetable-lecture?timetableId=${aliceMain.id.toHexString()}&lectureId=${lectures.os.id.toHexString()}",
        )
        notification("alice-friend", users.alice, "친구", "밥님과 친구가 되었습니다", type = 5, createdAt = at(3))
        notification("alice-vacancy", users.alice, "빈자리", "인공지능 개론에 빈자리가 생겼습니다", type = 4, createdAt = at(4))
        notification("bob-diary", users.bob, "강의 일기장", "오늘 수업은 어땠나요?", type = 7, createdAt = at(5))
    }

    private fun SeedBuilder.ev(
        users: Users,
        lectures: Lectures,
    ) {
        val tagGroups = resource("parity/ev-seed/tag-group.csv")
        val tags = resource("parity/ev-seed/tag.csv")
        evTagsFromCsv(tagGroups, tags)

        val osCourse = evCourse("os", "M1522.000800", "김운영", "운영체제", classification = "전필")
        val dsCourse = evCourse("ds", "4190.209", "이자료", "자료구조", academicYear = "2학년")
        val writingCourse =
            evCourse(
                "writing",
                "L0440.000600",
                "",
                "대학 글쓰기 1",
                credit = 2,
                department = "기초교육원",
                classification = "교양",
                academicYear = "1학년",
                // EV batch가 SNUTT 강의의 교양 영역을 과목에 복사한다.
                category = "사고와 표현",
            )
        // SNUTT 강의가 없는 학기(2019년 봄)에만 있는 EV 과목. EvLectureStep이 v2 강의를 새로 만든다.
        val linearCourse = evCourse("linear", "300.201", "한선형", "선형대수학", department = "수리과학부", academicYear = "2학년")

        val osAutumn = evSemesterLecture(osCourse, 2026, 3, listOf(lectures.os))
        val osSpring = evSemesterLecture(osCourse, 2026, 1, listOf(lectures.osSpring))
        val dsAutumn = evSemesterLecture(dsCourse, 2026, 3, listOf(lectures.ds))
        evSemesterLecture(writingCourse, 2026, 3, listOf(lectures.writing))
        val linear2019 = evSemesterLecture(linearCourse, 2019, 1)

        val bobOnOs =
            evaluation(
                "bob-os",
                osSpring,
                users.bob.id.toHexString(),
                "과제가 많지만 운영체제를 제대로 배울 수 있는 강의입니다.",
                rating = 4.5,
                createdAt = at(-90 * 24 * 60),
                likeCount = 2,
            )
        val carolOnOs =
            evaluation(
                "carol-os",
                osSpring,
                users.carol.id.toHexString(),
                "시험이 어렵지만 교수님 설명이 친절해서 따라갈 만했습니다.",
                rating = 3.0,
                scores = null,
                createdAt = at(-80 * 24 * 60),
            )
        val hidden =
            evaluation(
                "hidden-os",
                osSpring,
                users.dave.id.toHexString(),
                "신고 누적으로 숨겨진 강의평입니다. 신고 누적으로 숨겨진 강의평입니다.",
                rating = 1.0,
                hidden = true,
                reported = true,
                createdAt = at(-70 * 24 * 60),
            )
        // ★ 사용자가 없는 강의평(작성자 탈퇴 후 문서 삭제)
        val ghost =
            evaluation(
                "ghost-os",
                osAutumn,
                "000000000000000000000000",
                "작성자가 사라진 강의평이지만 내용은 남아 있습니다. 내용은 남아 있습니다.",
                rating = 5.0,
                createdAt = at(-1 * 24 * 60),
            )
        val aliceOnDs =
            evaluation(
                "alice-ds",
                dsAutumn,
                users.alice.id.toHexString(),
                "자료구조 기초를 꼼꼼하게 다루는 강의라 추천합니다. 과제 난이도도 적당합니다.",
                rating = 4.0,
                createdAt = at(-2 * 24 * 60),
            )
        // snuev에서 넘어온 오래된 강의평
        val snuev =
            evaluation(
                "snuev-linear",
                linear2019,
                "snuev-legacy-user",
                "예전 snuev에서 넘어온 강의평입니다. 선형대수 기본기를 다지기 좋습니다.",
                rating = 4.0,
                scores = null,
                fromSnuev = true,
                createdAt = at(-2000 * 24 * 60),
            )
        evaluationLike(bobOnOs, users.alice.id.toHexString())
        // ★ 사용자가 없는 공감(EVALUATION_LIKE_USER_MISSING)
        evaluationLike(bobOnOs, "없는-사용자")

        val evaluations = listOf(bobOnOs, carolOnOs, hidden, ghost, aliceOnDs, snuev)
        applyEvInfo(
            evaluations,
            mapOf(
                osCourse to listOf(lectures.os, lectures.osSpring),
                dsCourse to listOf(lectures.ds),
                writingCourse to listOf(lectures.writing),
            ),
        )
    }

    private fun resource(path: String): List<String> =
        checkNotNull(ParitySeed::class.java.classLoader.getResourceAsStream(path)) { "$path 없음" }
            .bufferedReader()
            .readLines()
}
