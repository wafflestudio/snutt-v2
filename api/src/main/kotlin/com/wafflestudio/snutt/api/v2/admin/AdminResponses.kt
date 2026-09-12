package com.wafflestudio.snutt.api.v2.admin

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class AdminConfigResponse(
    val id: Long,
    val name: String,
    val value: JsonNode,
    val minIosVersion: String?,
    val maxIosVersion: String?,
    val minAndroidVersion: String?,
    val maxAndroidVersion: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AdminPopupResponse(
    val id: Long,
    val popupKey: String,
    val imageOriginUri: String,
    val linkUrl: String?,
    val hiddenDays: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AdminRegistrationPeriodResponse(
    val id: Long,
    val year: Int,
    val semester: Semester,
    val registrationPeriods: List<RegistrationDate>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AdminDiaryDailyClassTypeResponse(
    val id: Long,
    val name: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AdminDiaryQuestionResponse(
    val id: Long,
    val question: String,
    val shortQuestion: String,
    val answers: List<String>,
    val shortAnswers: List<String>,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

internal fun ClientConfig.toResponse(mapper: JsonMapper) =
    AdminConfigResponse(
        id = checkNotNull(id),
        name = name,
        value = mapper.readTree(value),
        minIosVersion = minIosVersion,
        maxIosVersion = maxIosVersion,
        minAndroidVersion = minAndroidVersion,
        maxAndroidVersion = maxAndroidVersion,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )

internal fun Popup.toResponse() =
    AdminPopupResponse(
        id = checkNotNull(id),
        popupKey = popupKey,
        imageOriginUri = imageOriginUri,
        linkUrl = linkUrl,
        hiddenDays = hiddenDays,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )

internal fun SemesterRegistrationPeriod.toResponse() =
    AdminRegistrationPeriodResponse(
        id = checkNotNull(id),
        year = year,
        semester = semester,
        registrationPeriods = registrationPeriodList,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )

internal fun DiaryDailyClassType.toResponse() =
    AdminDiaryDailyClassTypeResponse(
        id = checkNotNull(id),
        name = name,
        active = active,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )

internal fun DiaryQuestion.toResponse() =
    AdminDiaryQuestionResponse(
        id = checkNotNull(id),
        question = question,
        shortQuestion = shortQuestion,
        answers = answerList,
        shortAnswers = shortAnswerList,
        active = active,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )
