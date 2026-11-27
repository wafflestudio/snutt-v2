package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 테마 팔레트 변경(`PATCH /v2/themes/{id}`)과 시간표 테마 변경이 겹치면 InnoDB 데드락이 나 한쪽이 500이 된다.
 * 팔레트 변경은 (user_id, theme_id)로 시간표를 FOR UPDATE(fk_timetable_theme 인덱스 next-key 락)한 뒤 theme 행을 X로 갱신하고,
 * 시간표 테마 변경은 theme 행 FK S락을 잡은 뒤 같은 인덱스에 쓴다. 자세한 재현은 작업 J 보고서 참고. 고치면 [MixedLoadStressTest]의 excludedOperations에서 뺀다.
 */
@StressKnownFailure
class ThemePaletteMixedLoadStressTest : AbstractMixedLoadStressTest("mixed-load-with-palette-change")
