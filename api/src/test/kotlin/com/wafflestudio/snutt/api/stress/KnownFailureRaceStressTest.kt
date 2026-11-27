package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.test.StressKnownFailure
import org.junit.jupiter.api.Test

/**
 * stress가 재현한 race 버그. 지금은 실패하는 것이 정상이며 `./gradlew :api:stressKnownFailureTest`로만 돈다. 고치면 [RaceScenarioStressTest.RACES]로 옮긴다.
 */
@StressKnownFailure
class KnownFailureRaceStressTest : AbstractStressTest() {
    /**
     * 서로 다른 사용자가 같은 FCM 토큰을 동시에 등록하면(한 기기에서 계정 전환) 두 트랜잭션이 모두 "이 토큰의 활성 기기 없음"을 보고 insert해
     * `uk_user_device_active_fcm_registration_id` 위반이 500으로 나간다. `DeviceService.addRegistrationId`는 자기 user 행만 잠그므로
     * 토큰 단위로는 직렬화되지 않는다.
     */
    @Test
    fun `서로 다른 사용자가 같은 FCM 토큰을 동시에 등록한다`() =
        stress("race-registerSameTokenFromDifferentUsers") {
            runRaceIterations(this, "registerSameTokenFromDifferentUsers", RaceScenarioStressTest.RACE_TIMEOUT) { requests ->
                httpScenarios.registerSameTokenFromDifferentUsers(requests)
            }
        }
}
