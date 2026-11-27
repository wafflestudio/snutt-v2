package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.api.concurrency.ConcurrencyScenario
import com.wafflestudio.snutt.api.concurrency.ConcurrencyScenarios
import com.wafflestudio.snutt.core.test.StressTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/**
 * 작업 E의 race 시나리오([ConcurrencyScenarios])를 실제 HTTP 서버에 스레드 수만큼 동시에 보낸다. PR CI는 요청 5개를 락 순서까지 정해
 * 돌리고(runContended), 여기서는 요청 [com.wafflestudio.snutt.core.test.stress.StressSettings.threads]개를 latch 하나로 출발시켜
 * 반복마다 처음 상태부터 다시 돈다. Hikari 풀(10)보다 훨씬 많은 요청이 커넥션을 기다리며 겹친다.
 *
 * 반복마다 시나리오의 불변식(verifyUnordered)과 5xx·데드락·lock wait timeout·풀 타임아웃을 본다.
 */
@StressTest
class RaceScenarioStressTest : AbstractStressTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    fun race(name: String) =
        stress("race-$name") {
            runRaceIterations(this, name, RACE_TIMEOUT) { requests -> RACES.getValue(name)(httpScenarios, requests) }
        }

    companion object {
        /** stress에서 통과해야 하는 race. 재현된 버그는 [KnownFailureRaceStressTest]로 옮긴다. */
        val RACES: Map<String, (ConcurrencyScenarios, Int) -> ConcurrencyScenario> =
            linkedMapOf(
                "overlappingLecturesIntoOneTimetable" to { s, n -> s.overlappingLecturesIntoOneTimetable(n) },
                "sameLectureIntoOneTimetable" to { s, n -> s.sameLectureIntoOneTimetable(n) },
                "themePaletteShrinkWhileAddingLectures" to { s, n -> s.themePaletteShrinkWhileAddingLectures(n) },
                "firstTimetablesOfSemester" to { s, n -> s.firstTimetablesOfSemester(n) },
                "sameTitleTimetables" to { s, n -> s.sameTitleTimetables(n) },
                "deleteEveryTimetable" to { s, n -> s.deleteEveryTimetable(n) },
                "setPrimaryOnEveryTimetable" to { s, n -> s.setPrimaryOnEveryTimetable(n) },
                "sameUserEvaluatesTwice" to { s, n -> s.sameUserEvaluatesTwice(n) },
                "differentUsersEvaluateOneCourse" to { s, n -> s.differentUsersEvaluateOneCourse(n) },
                "evaluationsWrittenAndDeletedTogether" to { s, n -> s.evaluationsWrittenAndDeletedTogether(n) },
                "sameUserLikesTwice" to { s, n -> s.sameUserLikesTwice(n) },
                "likesAndCancelsTogether" to { s, n -> s.likesAndCancelsTogether(n) },
                "signUpWithSameLocalId" to { s, n -> s.signUpWithSameLocalId(n) },
                "bookmarkSameLecture" to { s, n -> s.bookmarkSameLecture(n) },
                "submitSameDiary" to { s, n -> s.submitSameDiary(n) },
                "setDefaultThemeTwice" to { s, n -> s.setDefaultThemeTwice(n) },
                "setAndUnsetDefaultThemeTogether" to { s, n -> s.setAndUnsetDefaultThemeTogether(n) },
                "setDefaultThemeWhileDeletingIt" to { s, n -> s.setDefaultThemeWhileDeletingIt(n) },
                "savePushPreferencesTwice" to { s, n -> s.savePushPreferencesTwice(n) },
                "registerSameDeviceTwice" to { s, n -> s.registerSameDeviceTwice(n) },
                "sameNicknameConcurrently" to { s, n -> s.sameNicknameConcurrently(n) },
            )

        @JvmStatic
        fun scenarioNames(): List<String> = RACES.keys.toList()

        /** 락 대기(innodb_lock_wait_timeout 50초)와 풀 대기(30초)를 넘겨도 결과를 받도록 넉넉히 둔다. */
        val RACE_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
