package com.wafflestudio.snutt.api.concurrency

import org.junit.jupiter.api.Test

/** 사용자 단위 요청의 race. unique 제약 또는 사용자 행 잠금이 막는다. */
class AccountConcurrencyTest : AbstractConcurrencyTest() {
    @Test
    fun `같은 localId로 동시에 가입하면 하나만 가입되고 나머지는 DUPLICATE_LOCAL_ID다`() {
        scenarios.signUpWithSameLocalId(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 강의를 동시에 관심강좌에 넣어도 오류 없이 하나만 저장된다`() {
        scenarios.bookmarkSameLecture(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 강의의 강의 일기를 동시에 제출하면 하나만 저장된다`() {
        scenarios.submitSameDiary(REQUESTS).runContended(contention)
    }

    @Test
    fun `기본 테마 지정을 동시에 보내도 오류 없이 설정 행은 하나다`() {
        scenarios.setDefaultThemeTwice(REQUESTS).runContended(contention)
    }

    @Test
    fun `기본 테마 지정과 해제가 겹치면 해제는 지정된 결과를 보고 거절된다`() {
        scenarios.setAndUnsetDefaultThemeTogether(REQUESTS).runContended(contention)
    }

    @Test
    fun `테마를 기본으로 지정하는 동안 지우면 기본 설정이 기본 테마로 돌아간다`() {
        scenarios.setDefaultThemeWhileDeletingIt(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 푸시 설정 저장을 동시에 보내도 오류 없이 종류마다 행은 하나다`() {
        scenarios.savePushPreferencesTwice(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 FCM 토큰 등록을 동시에 보내도 오류 없이 활성 기기는 하나다`() {
        scenarios.registerSameDeviceTwice(REQUESTS).runContended(contention)
    }

    @Test
    fun `다른 사용자들이 같은 FCM 토큰을 동시에 등록해도 오류 없이 활성 기기는 하나다`() {
        scenarios.registerSameTokenFromDifferentUsers(REQUESTS).runContended(contention)
    }
}
