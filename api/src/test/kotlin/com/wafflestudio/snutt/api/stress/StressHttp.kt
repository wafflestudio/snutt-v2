package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.api.concurrency.ScenarioActions
import com.wafflestudio.snutt.api.error.problemType
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * stress용 HTTP 클라이언트. 테스트 베이스의 RestClient는 Apache HttpClient 기본 풀(경로당 5개)을 타서 동시 요청 수가 묶이므로
 * 연결 수 제한이 없는 JDK HttpClient를 쓴다. 요청 본문은 서버와 같은 JsonMapper(애플리케이션 빈)로 직렬화한다.
 *
 * 요청 타임아웃은 innodb_lock_wait_timeout(기본 50초)보다 길게 둬서, 락 대기로 늦어진 요청도 서버가 낸 결과를 받는다.
 */
class StressHttp(
    private val baseUrl: String,
    private val mapper: JsonMapper,
) {
    data class Response(
        val status: Int,
        val body: String,
        val errorType: ErrorType?,
    ) {
        val isSuccess: Boolean get() = status in 200..299

        /** 리포트에 남길 결과. 2xx는 "2xx", 정해진 오류는 ErrorType 이름, 나머지는 상태 코드. */
        val outcome: String get() = if (isSuccess) "2xx" else errorType?.name ?: status.toString()
    }

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun send(
        method: String,
        path: String,
        token: String? = null,
        body: Any? = null,
        osType: OsType = OsType.IOS,
        deviceId: String? = null,
    ): Response {
        val builder =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-os-type", osType.value)
                .header("x-client-key", "test-${osType.value}-key")
                .method(
                    method,
                    body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
                )
        token?.let { builder.header("Authorization", "Bearer $it") }
        deviceId?.let { builder.header("x-device-id", it) }
        val response =
            try {
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                return Response(TIMEOUT_STATUS, "요청이 $REQUEST_TIMEOUT 안에 끝나지 않았다", null)
            }
        val errorType = if (response.statusCode() in 400..499) errorTypeOf(response.body()) else null
        return Response(response.statusCode(), response.body(), errorType)
    }

    fun get(
        path: String,
        token: String,
    ): Response = send("GET", path, token)

    fun json(body: String): JsonNode = mapper.readTree(body)

    private fun errorTypeOf(body: String): ErrorType? {
        val type = runCatching { mapper.readTree(body)["type"]?.asString() }.getOrNull() ?: return null
        return ErrorType.entries.firstOrNull { it.problemType.toString() == type }
    }

    companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)
        const val TIMEOUT_STATUS = 599
    }
}

/** 서버가 5xx(또는 정해지지 않은 4xx)를 돌려줬다. 시나리오 verify는 이것을 정해지지 않은 예외로 보고 실패한다. */
class UnexpectedHttpResponse(
    val response: StressHttp.Response,
    operation: String,
) : RuntimeException("$operation -> ${response.status} ${response.body.take(300)}")

/** [ScenarioActions]를 실제 HTTP 요청으로 보낸다. 4xx는 problem type을 ErrorType으로 되돌려 SnuttException으로 던진다. */
class HttpScenarioActions(
    private val http: StressHttp,
    private val token: (userId: Long) -> String,
) : ScenarioActions {
    private fun call(
        operation: String,
        method: String,
        path: String,
        userId: Long?,
        body: Any? = null,
        osType: OsType = OsType.IOS,
        deviceId: String? = null,
    ) {
        val response = http.send(method, path, userId?.let(token), body, osType, deviceId)
        if (response.isSuccess) return
        response.errorType?.let { throw SnuttException(it, *MESSAGE_ARGS) }
        throw UnexpectedHttpResponse(response, operation)
    }

    private companion object {
        /** SnuttException은 생성할 때 메시지를 포맷하므로 인자가 있는 오류(시간 겹침 등)는 인자가 없으면 던질 수 없다. 빈 값으로 채운다. */
        val MESSAGE_ARGS: Array<Any> = Array(8) { "" }
    }

    override fun addLecture(
        userId: Long,
        timetableId: Long,
        lectureId: Long,
    ) = call("addLecture", "POST", "/v2/timetables/$timetableId/lectures", userId, mapOf("lectureId" to lectureId))

    override fun addCustomLecture(
        userId: Long,
        timetableId: Long,
        courseTitle: String,
        time: ClassPlaceAndTime,
    ) = call(
        "addCustomLecture",
        "POST",
        "/v2/timetables/$timetableId/lectures/custom",
        userId,
        mapOf("courseTitle" to courseTitle, "classPlaceAndTimes" to listOf(time)),
    )

    override fun modifyThemeColors(
        userId: Long,
        themeId: Long,
        colors: List<ColorSet>,
    ) = call("modifyTheme", "PATCH", "/v2/themes/$themeId", userId, mapOf("colors" to colors))

    override fun addTimetable(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    ) = call("addTimetable", "POST", "/v2/timetables", userId, mapOf("year" to year, "semester" to semester, "title" to title))

    override fun deleteTimetable(
        userId: Long,
        timetableId: Long,
    ) = call("deleteTimetable", "DELETE", "/v2/timetables/$timetableId", userId)

    override fun setPrimary(
        userId: Long,
        timetableId: Long,
    ) = call("setPrimary", "PUT", "/v2/timetables/$timetableId/primary", userId)

    override fun createEvaluation(
        userId: Long,
        lectureId: Long,
        request: EvaluationWriteRequest,
    ) = call("createEvaluation", "POST", "/v2/lectures/$lectureId/evaluations", userId, request)

    override fun deleteEvaluation(
        userId: Long,
        evaluationId: Long,
    ) = call("deleteEvaluation", "DELETE", "/v2/evaluations/$evaluationId", userId)

    override fun likeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) = call("likeEvaluation", "POST", "/v2/evaluations/$evaluationId/like", userId)

    override fun cancelLikeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) = call("cancelLikeEvaluation", "DELETE", "/v2/evaluations/$evaluationId/like", userId)

    override fun registerLocal(
        localId: String,
        password: String,
        email: String,
    ) = call(
        "register",
        "POST",
        "/v2/auth/register",
        null,
        mapOf("localId" to localId, "password" to password, "email" to email),
    )

    override fun bookmarkLecture(
        userId: Long,
        lectureId: Long,
    ) = call("bookmark", "POST", "/v2/bookmarks/lectures/$lectureId", userId)

    override fun submitEmptyDiary(
        userId: Long,
        lectureId: Long,
    ) = call(
        "submitDiary",
        "POST",
        "/v2/diaries",
        userId,
        mapOf("lectureId" to lectureId, "dailyClassTypes" to emptyList<String>(), "questionAnswers" to emptyList<Any>(), "comment" to ""),
    )

    override fun setDefaultTheme(
        userId: Long,
        themeId: Long,
    ) = call("setDefaultTheme", "POST", "/v2/themes/$themeId/default", userId)

    override fun unsetDefaultTheme(
        userId: Long,
        themeId: Long,
    ) = call("unsetDefaultTheme", "DELETE", "/v2/themes/$themeId/default", userId)

    override fun deleteTheme(
        userId: Long,
        themeId: Long,
    ) = call("deleteTheme", "DELETE", "/v2/themes/$themeId", userId)

    override fun savePushPreferences(
        userId: Long,
        preferences: PushPreferenceDto,
    ) = call("savePushPreferences", "POST", "/v2/users/me/push-preferences", userId, preferences)

    override fun addRegistrationId(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    ) = call(
        "addRegistrationId",
        "POST",
        "/v2/users/me/devices/$registrationId",
        userId,
        osType = clientInfo.osType,
        deviceId = clientInfo.deviceId,
    )

    override fun updateNickname(
        userId: Long,
        nickname: String,
    ) = call("updateNickname", "PATCH", "/v2/users/me", userId, mapOf("nickname" to nickname))

    override fun modifyReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    ) = call(
        "modifyReminder",
        "PUT",
        "/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder",
        userId,
        mapOf("option" to option),
    )
}
