package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.scheduler.DiaryScheduler
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.net.URLEncoder
import java.time.LocalDateTime

/**
 * 구앱 기기와 신앱 기기를 함께 가진 사용자에게 푸시가 나갈 때.
 * 두 앱은 같은 딥링크 스킴을 해석하므로(v1 DeeplinkType, phase가 prod가 아니면 snutt-dev) 딥링크는 기기와 상관없이 v1 형식이어야 하고,
 * 문구는 기기에 저장된 언어(구앱은 x-language를 보내지 않아 KO)를 따른다. FCM payload 키(url_scheme)는 FcmPushClient가 v1과 같게 쓴다.
 */
class PushDeeplinkScenarioTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var diaryScheduler: DiaryScheduler

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    private fun sentMessages(): Map<String, TargetedPushMessage> {
        val captor = argumentCaptor<List<TargetedPushMessage>>()
        verify(pushClient, atLeastOnce()).sendMessages(captor.capture())
        return captor.allValues.flatten().associateBy { it.fcmRegistrationId }
    }

    /** 구앱 기기(Android 구버전, 언어 헤더 없음)와 신앱 기기(iOS, 영어)를 등록한 사용자. */
    private fun userWithBothApps(localId: String): Pair<String, String> {
        val (legacyToken, _) = v1Register(localId)
        val oldDevice =
            v1Send(
                HttpMethod.POST,
                "/v1/user/device/fcm-$localId-old",
                legacyToken = legacyToken,
                headers = mapOf("x-os-type" to "android", "x-app-version" to "3.12.4"),
            )
        assertEquals(200, oldDevice.statusCode.value(), "body=${oldDevice.body}")
        val v2Token = v2Login(localId)
        val newDevice = post("/v2/users/me/devices/fcm-$localId-new", token = v2Token, language = Language.EN)
        assertEquals(200, newDevice.statusCode.value(), "body=${newDevice.body}")
        return legacyToken to v2Token
    }

    private fun nicknameOf(legacyToken: String): String =
        body(v1Get("/v1/users/me", legacyToken))["nickname"].let { "${it["nickname"].asString()}#${it["tag"].asString()}" }

    @Test
    fun `친구 요청 푸시는 두 기기에 같은 v1 딥링크와 기기 언어별 문구로 나간다`() {
        val (targetLegacy, targetV2) = userWithBothApps("pushtarget")
        val (requesterLegacy, _) = v1Register("pushrequester")

        val requested = v1Post("/v1/friends", """{"nickname":"${nicknameOf(targetLegacy)}"}""", requesterLegacy)
        assertEquals(200, requested.statusCode.value(), "body=${requested.body}")

        val sent = sentMessages()
        assertEquals(setOf("fcm-pushtarget-old", "fcm-pushtarget-new"), sent.keys)
        val old = sent.getValue("fcm-pushtarget-old").message
        val new = sent.getValue("fcm-pushtarget-new").message
        assertEquals("snutt-dev://friends?openDrawer=true", old.urlScheme)
        assertEquals(old.urlScheme, new.urlScheme)
        assertEquals("친구 요청", old.title)
        assertEquals("Friend request", new.title)

        // 알림함에도 같은 딥링크로 남아 구앱·신앱이 같은 화면을 연다
        val legacyNotification = body(v1Get("/v1/notification", targetLegacy))[0]
        assertEquals(5, legacyNotification["type"].asInt())
        assertEquals("snutt-dev://friends?openDrawer=true", legacyNotification["deeplink"].asString())
        assertEquals("snutt-dev://friends?openDrawer=true", body(get("/v2/notifications", targetV2))["content"][0]["deeplink"].asString())
    }

    @Test
    fun `강의 일기장 푸시 딥링크는 v1과 같은 인코딩으로 두 기기에 나가고 문구만 언어별로 다르다`() {
        userWithBothApps("pushdiary")
        val user = userRepository.findByLocalIdAndActiveTrue("pushdiary")!!
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture =
            fixtures.lecture(
                autumn,
                courseTitle = "공학연구의 실습 1",
                times = listOf(ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-118", 900, 975)),
            ) { courseTitleEn = "Engineering Research Practice 1" }
        fixtures.addLecture(fixtures.timetable(user, autumn), lecture)

        clock.setKst(LocalDateTime.of(2026, 11, 4, 6, 0))
        diaryScheduler.planDiaryNotificationsScheduled()
        clock.setKst(LocalDateTime.of(2026, 11, 4, 17, 45))
        diaryScheduler.sendDueDiaryNotificationsScheduled()

        val sent = sentMessages()
        assertEquals(setOf("fcm-pushdiary-old", "fcm-pushdiary-new"), sent.keys)
        // v1 DiaryNotifierService: URLEncoder.encode(courseTitle, "UTF-8").replace("+", "%20"), 강의명은 항상 한국어
        val encodedTitle = URLEncoder.encode("공학연구의 실습 1", Charsets.UTF_8).replace("+", "%20")
        val expected = "snutt-dev://diary?lectureId=${lecture.id}&courseTitle=$encodedTitle"
        assertEquals(expected, sent.getValue("fcm-pushdiary-old").message.urlScheme)
        assertEquals(expected, sent.getValue("fcm-pushdiary-new").message.urlScheme)
        assertEquals(true, sent.getValue("fcm-pushdiary-old").message.body.contains("공학연구의 실습 1"))
        assertEquals(true, sent.getValue("fcm-pushdiary-new").message.body.contains("Engineering Research Practice 1"))
    }

    @Test
    fun `같은 기기에서 신앱으로 업그레이드하면 등록 토큰 하나로 합쳐지고 신앱 언어로 한 번만 받는다`() {
        val (legacyToken, _) = v1Register("upgradedevice")
        v1Send(HttpMethod.POST, "/v1/user/device/fcm-same-device", legacyToken = legacyToken, headers = mapOf("x-os-type" to "ios"))
        val v2Token = v2Login("upgradedevice")
        post("/v2/users/me/devices/fcm-same-device", token = v2Token, language = Language.EN)
        val (requesterLegacy, _) = v1Register("upgraderequester")

        v1Post("/v1/friends", """{"nickname":"${nicknameOf(legacyToken)}"}""", requesterLegacy)

        val sent = sentMessages()
        assertEquals(setOf("fcm-same-device"), sent.keys)
        assertEquals("Friend request", sent.getValue("fcm-same-device").message.title)
        val userId = userRepository.findByLocalIdAndActiveTrue("upgradedevice")!!.id!!
        assertEquals(1, userDeviceRepository.findPushTargets(listOf(userId)).size)
    }

    @Test
    fun `구앱에서 로그아웃한 기기는 빠지고 신앱 기기만 받는다`() {
        val (legacyToken, _) = userWithBothApps("logoutdevice")
        val logout = v1Post("/v1/auth/logout", """{"registration_id":"fcm-logoutdevice-old"}""", legacyToken)
        assertEquals(200, logout.statusCode.value(), "body=${logout.body}")
        val (requesterLegacy, _) = v1Register("logoutrequester")

        v1Post("/v1/friends", """{"nickname":"${nicknameOf(legacyToken)}"}""", requesterLegacy)

        assertEquals(setOf("fcm-logoutdevice-new"), sentMessages().keys)
    }
}
