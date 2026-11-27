package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParitySnapshot
import com.wafflestudio.snutt.parity.AbstractParityTest
import com.wafflestudio.snutt.parity.env.ParityDataSource
import com.wafflestudio.snutt.parity.env.SnapshotDataSource
import com.wafflestudio.snutt.parity.scenario.ParityScenario
import org.junit.jupiter.api.Test

/**
 * 운영 스냅샷(외부 덤프)으로 도는 parity. 시드 별칭에 기대지 않고 roles 파일의 사용자와 응답에서 잡은 id로만 요청한다.
 * 쓰기 요청은 하지 않는다. 실행법은 docs/parity.md "외부 데이터로 돌리기".
 */
@ParitySnapshot
class SnapshotParityTest : AbstractParityTest() {
    override val source: ParityDataSource by lazy { SnapshotDataSource.fromSystemProperties() }

    @Test
    fun `역할 사용자마다 구 앱의 읽기 흐름을 비교한다`() {
        val failures =
            data.users.keys.mapNotNull { user ->
                runCatching { parity("스냅샷: $user") { readFlow(user) } }.exceptionOrNull()
            }
        if (failures.isNotEmpty()) {
            throw AssertionError("스냅샷 사용자 ${data.users.size}명 중 ${failures.size}명에서 차이").apply { failures.forEach(::addSuppressed) }
        }
    }

    private fun ParityScenario.readFlow(user: String) {
        get("/v1/users/me", `as` = user)
        get("/v1/users/me/social_providers", `as` = user)
        val tables = get("/v1/tables", `as` = user)
        get("/v1/tables/recent", `as` = user)
        if ((tables.bodies.values.first() as? List<*>)?.isNotEmpty() == true) {
            tables.capture("tt:first", "/0/_id")
            get("/v1/tables/@{tt:first}", `as` = user)
        }
        get("/v1/notification?offset=0&limit=20", `as` = user)
        get("/v1/notification/count", `as` = user)
        get("/v1/friends?state=ACTIVE", `as` = user)
        get("/v1/push/preferences", `as` = user)
        get("/v1/themes", `as` = user)
        get("/v1/course_books/recent", `as` = user).capture("cb:year", "/year").capture("cb:semester", "/semester")
        get("/v1/bookmarks?year=@{cb:year}&semester=@{cb:semester}", `as` = user)
        get("/v1/vacancy-notifications/lectures", `as` = user)
        get("/ev-service/v1/evaluations/users/me", `as` = user)
        get("/ev-service/v1/users/me/lectures/latest", `as` = user)
    }
}
