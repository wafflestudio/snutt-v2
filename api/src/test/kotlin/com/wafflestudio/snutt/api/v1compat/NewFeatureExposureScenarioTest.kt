package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestionTarget
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionTargetRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode

/**
 * 신앱(v2)에서만 만들 수 있는 데이터가 생긴 뒤 같은 계정이나 친구가 구앱으로 볼 때.
 * iOS 구앱은 openapi 생성 enum이 닫혀 있어 모르는 값이 오면 디코딩이 실패하고(테마 status, 알림 type 0-7, theme 0-5),
 * 구앱은 x-language를 보내지 않으므로 영문 필드가 섞여 나가면 안 된다.
 */
class NewFeatureExposureScenarioTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var diaryDailyClassTypeRepository: DiaryDailyClassTypeRepository

    @Autowired
    lateinit var diaryQuestionRepository: DiaryQuestionRepository

    @Autowired
    lateinit var diaryQuestionTargetRepository: DiaryQuestionTargetRepository

    private val legacyThemeStatuses = setOf("BASIC", "DOWNLOADED", "PUBLISHED", "PRIVATE")

    private fun JsonNode.fieldNamesDeep(): Set<String> =
        when {
            isObject -> properties().flatMap { (key, value) -> setOf(key) + value.fieldNamesDeep() }.toSet()
            isArray -> items().flatMap { it.fieldNamesDeep() }.toSet()
            else -> emptySet()
        }

    private fun assertLegacyThemes(themes: JsonNode) {
        themes.items().forEach {
            assertTrue(it["status"].asString() in legacyThemeStatuses, "모르는 status: $it")
            assertTrue(it["theme"].asInt() in 0..5, "모르는 theme: $it")
        }
    }

    @Test
    fun `신앱에서 만들고 게시하고 받은 테마는 구앱이 아는 status와 theme 값으로만 보인다`() {
        val authorToken = register("themeauthor")
        val (authorLegacy, _) = v1Register("themeauthor2")
        val downloaderToken = register("themedownloader")
        val downloaderLegacy = body(v1Post("/v1/auth/login_local", """{"id":"themedownloader","password":"password1"}"""))["token"].asString()

        val themeId =
            body(post("/v2/themes", """{"name":"새벽","colors":[{"backgroundColor":"#101820","foregroundColor":"#f2aa4c"}]}""", authorToken))
                .items()
                .single { it["name"].asString() == "새벽" }["id"]
                .asLong()
        val publication = post("/v2/themes/$themeId/publish", """{"name":"새벽 테마","authorAnonymous":true}""", authorToken)
        assertEquals(200, publication.statusCode.value(), "body=${publication.body}")
        val publicationId = body(publication)["id"].asLong()
        assertEquals(200, post("/v2/theme-publications/$publicationId/download", "{}", downloaderToken).statusCode.value())

        // 구앱 테마 마켓: 익명 게시자는 "익명"으로, 게시본 id로 보인다
        val market = body(v1Get("/v1/themes/best?page=1", authorLegacy))
        assertLegacyThemes(market["content"])
        val listed = market["content"].items().single { it["id"].asString() == publicationId.toString() }
        assertEquals("PUBLISHED", listed["status"].asString())
        assertEquals("익명", listed["publishInfo"]["authorName"].asString())
        assertEquals("새벽 테마", listed["publishInfo"]["publishName"].asString())

        // 구앱 내 테마 목록: 받은 테마는 DOWNLOADED + 원본(게시본) id
        val downloaderThemes = body(v1Get("/v1/themes", downloaderLegacy))
        assertLegacyThemes(downloaderThemes)
        val downloaded = downloaderThemes.items().single { it["status"].asString() == "DOWNLOADED" }
        assertEquals(publicationId.toString(), downloaded["origin"]["originId"].asString())
        assertEquals("#101820", downloaded["colors"][0]["bg"].asString())
        assertEquals(6, downloaderThemes.items().count { it["status"].asString() == "BASIC" })
    }

    @Test
    fun `신앱의 팔레트 지정과 직접 지정한 색은 구앱의 colorIndex와 color로 바뀐다`() {
        val v2Token = register("colorer")
        val legacyToken = body(v1Post("/v1/auth/login_local", """{"id":"colorer","password":"password1"}"""))["token"].asString()
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val palette = fixtures.lecture(autumn, courseTitle = "팔레트 강의", times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "", 600, 660)))
        val custom = fixtures.lecture(autumn, courseTitle = "직접 색 강의", times = listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "", 600, 660)))
        val timetableId =
            body(post("/v2/timetables", """{"year":2026,"semester":3,"title":"색 시간표"}""", v2Token))
                .items()
                .single { it["title"].asString() == "색 시간표" }["id"]
                .asLong()
        val lectures =
            listOf(palette, custom).associate { lecture ->
                val added = body(post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lecture.id}}""", v2Token))
                lecture.id to added["lectures"].items().single { it["lectureId"].asLong() == lecture.id }["id"].asLong()
            }
        patch("/v2/timetables/$timetableId/lectures/${lectures[palette.id]}", """{"paletteIndex":4}""", v2Token)
        patch(
            "/v2/timetables/$timetableId/lectures/${lectures[custom.id]}",
            """{"customColor":{"backgroundColor":"#000000","foregroundColor":"#ffffff"}}""",
            v2Token,
        )

        val legacy = body(v1Get("/v1/tables/$timetableId", legacyToken))
        assertTrue(legacy["theme"].asInt() in 0..5)
        val byTitle = legacy["lecture_list"].items().associateBy { it["course_title"].asString() }
        // 빌트인 테마 + 팔레트 색: v1 colorIndex는 1부터, color는 생략
        assertEquals(5, byTitle.getValue("팔레트 강의")["colorIndex"].asInt())
        assertFalse(byTitle.getValue("팔레트 강의").has("color"))
        // 직접 지정한 색: colorIndex 0 + color
        assertEquals(0, byTitle.getValue("직접 색 강의")["colorIndex"].asInt())
        assertEquals("#000000", byTitle.getValue("직접 색 강의")["color"]["bg"].asString())
        assertEquals("#ffffff", byTitle.getValue("직접 색 강의")["color"]["fg"].asString())
    }

    @Test
    fun `영문 데이터가 있어도 언어를 보내지 않는 구앱에는 한국어 값만 v1 필드로 간다`() {
        val (legacyToken, userId) = v1Register("koreanonly")
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture =
            fixtures.lecture(autumn, courseTitle = "자료구조", times = listOf(ClassPlaceAndTime(DayOfWeek.FRIDAY, "301-101", 600, 660))) {
                courseTitleEn = "Data Structures"
                instructor = "김교수"
                instructorEn = "Kim"
                department = "컴퓨터공학부"
                departmentEn = "Dept. of Computer Science and Engineering"
                classification = "전필"
                classificationEn = "Major Requirement"
            }
        val timetableId =
            body(v1Post("/v1/tables", """{"year":2026,"semester":3,"title":"언어"}""", legacyToken)).items().single()["_id"].asString()
        v1Post("/v1/tables/$timetableId/lecture/${lecture.id}", "{}", legacyToken)
        notificationRepository.save(
            Notification(
                userId = userId.toLong(),
                title = "빈자리 알림",
                titleEn = "Vacancy alert",
                message = "\"자료구조\" 강의에 빈자리가 생겼습니다.",
                messageEn = "A seat has opened up in \"Data Structures\".",
                type = NotificationType.LECTURE_VACANCY,
            ),
        )

        val timetable = body(v1Get("/v1/tables/$timetableId", legacyToken))
        val item = timetable["lecture_list"][0]
        assertEquals("자료구조", item["course_title"].asString())
        assertEquals("김교수", item["instructor"].asString())
        assertEquals("컴퓨터공학부", item["department"].asString())
        assertEquals("전필", item["classification"].asString())
        val notification = body(v1Get("/v1/notification", legacyToken))
        assertEquals("빈자리 알림", notification[0]["title"].asString())
        assertEquals("\"자료구조\" 강의에 빈자리가 생겼습니다.", notification[0]["message"].asString())

        val leaked = (timetable.fieldNamesDeep() + notification.fieldNamesDeep()).filter { it.endsWith("En") || it.endsWith("_en") }
        assertEquals(emptyList<String>(), leaked)
    }

    @Test
    fun `v2가 만드는 알림 종류는 모두 구앱이 아는 정수 type으로 내려간다`() {
        val (legacyToken, userId) = v1Register("notitypes")
        NotificationType.entries.forEach { type ->
            notificationRepository.save(Notification(userId = userId.toLong(), title = type.name, message = "본문", type = type))
        }

        val types =
            body(v1Get("/v1/notification?limit=50", legacyToken)).items().associate { it["title"].asString() to it["type"].asInt() }

        // v1 NotificationType(snutt/core/.../NotificationType.kt)과 같은 값이어야 한다. iOS openapi는 0..7만 안다.
        assertEquals(
            mapOf(
                "NORMAL" to 0,
                "COURSEBOOK" to 1,
                "LECTURE_UPDATE" to 2,
                "LECTURE_REMOVE" to 3,
                "LECTURE_VACANCY" to 4,
                "FRIEND" to 5,
                "FEATURE_NEW" to 6,
                "DIARY" to 7,
            ),
            types,
        )
    }

    @Test
    fun `신앱에서 쓴 강의 일기장을 구앱이 보고 지우면 신앱에서도 사라진다`() {
        val v2Token = register("diarycross")
        val legacyToken = body(v1Post("/v1/auth/login_local", """{"id":"diarycross","password":"password1"}"""))["token"].asString()
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture = fixtures.lecture(autumn, courseTitle = "알고리즘", times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "", 600, 675)))
        val user = userRepository.findByLocalIdAndActiveTrue("diarycross")!!
        fixtures.addLecture(fixtures.timetable(user, autumn, title = "대표"), lecture)
        val classType = diaryDailyClassTypeRepository.save(DiaryDailyClassType(name = "수업듣기"))
        val question =
            diaryQuestionRepository.save(
                DiaryQuestion(question = "오늘 수업은 어땠나요?", shortQuestion = "수업", answerList = listOf("좋아요", "싫어요"), shortAnswerList = listOf("좋음", "싫음")),
            )
        diaryQuestionTargetRepository.save(DiaryQuestionTarget(question.id!!, classType.id!!))

        val submitted =
            post(
                "/v2/diaries",
                """{"lectureId":${lecture.id},"dailyClassTypes":["수업듣기"],"questionAnswers":[{"questionId":${question.id},"answerIndex":0}],"comment":"신앱에서 쓴 일기"}""",
                v2Token,
            )
        assertEquals(200, submitted.statusCode.value(), "body=${submitted.body}")

        val legacy = body(v1Get("/v1/diary/my", legacyToken))
        assertEquals(1, legacy.size())
        assertEquals(3, legacy[0]["semester"].asInt())
        val submission = legacy[0]["submissions"].items().single()
        assertTrue(submission["id"].isString)
        assertEquals(lecture.id.toString(), submission["lectureId"].asString())
        assertEquals("알고리즘", submission["courseTitle"].asString())
        assertEquals("신앱에서 쓴 일기", submission["comment"].asString())
        assertEquals("수업", submission["shortQuestionReplies"][0]["question"].asString())
        assertEquals("좋음", submission["shortQuestionReplies"][0]["answer"].asString())

        assertEquals(200, v1Delete("/v1/diary/${submission["id"].asString()}", legacyToken).statusCode.value())
        assertEquals(0, body(get("/v2/diaries/me", v2Token)).size())
    }
}
