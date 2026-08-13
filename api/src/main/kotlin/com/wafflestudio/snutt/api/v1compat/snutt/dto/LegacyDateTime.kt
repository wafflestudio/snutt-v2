package com.wafflestudio.snutt.api.v1compat.snutt.dto

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// v1은 시각을 KST 기준으로 직렬화한다
internal val KST: ZoneId = ZoneId.of("Asia/Seoul")

internal fun Instant.toLegacyLocalDateTime(): LocalDateTime = atZone(KST).toLocalDateTime()

internal fun Long.toLegacyLocalDateTimeString(): String =
    Instant
        .ofEpochMilli(this)
        .atZone(KST)
        .toLocalDateTime()
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

// v1 NotificationResponse.createdAt는 오프셋 포함 ZonedDateTime이다
internal fun Long.toLegacyZonedDateTimeString(): String = Instant.ofEpochMilli(this).atZone(KST).toString()
