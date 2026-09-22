package com.wafflestudio.snutt.core.common.client

const val CLIENT_INFO_ATTRIBUTE = "clientInfo"

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentClient
