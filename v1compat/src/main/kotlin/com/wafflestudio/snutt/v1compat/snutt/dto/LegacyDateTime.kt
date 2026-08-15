package com.wafflestudio.snutt.v1compat.snutt.dto

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val KST: ZoneId = ZoneId.of("Asia/Seoul")

internal fun Instant.toLegacyLocalDateTime(): LocalDateTime = atZone(KST).toLocalDateTime()

internal fun Long.toLegacyLocalDateTimeString(): String =
    Instant
        .ofEpochMilli(this)
        .atZone(KST)
        .toLocalDateTime()
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

internal fun Long.toLegacyZonedDateTimeString(): String = Instant.ofEpochMilli(this).atZone(KST).toString()
