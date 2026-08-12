package com.wafflestudio.snutt.api.auth

// 이메일 인증이 완료된 사용자만 접근 가능한 v2 엔드포인트 (v1 ev-service 게이트 이식, PLAN.md §3)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EmailVerifiedRequired
