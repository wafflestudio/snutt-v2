package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 시간표 테마 변경이 시간표 조회와 겹치면 조회가 IndexOutOfBounds 500이 된다. 조회(`TimetableService.displaysOf`)가 트랜잭션 없이
 * timetable(테마 id)과 timetable_lecture(palette_index)를 따로 읽어, 그 사이에 커밋된 테마 변경의 절반만 본다(예: 3색 테마 + 9색 기준 index).
 * DB에는 남지 않는 일시적 500이다. 고치면 [MixedLoadStressTest]에서 이 연산을 다시 넣는다.
 */
@StressKnownFailure
class TimetableThemeChangeMixedLoadStressTest :
    AbstractMixedLoadStressTest("mixed-load-with-timetable-theme-change", excludedOperations = setOf("modifyThemeColors"))

/**
 * 재현된 버그: 테마 팔레트 변경(`PATCH /v2/themes/{id}`)과 시간표 테마 변경이 겹치면 InnoDB 데드락이 나 한쪽이 500이 된다.
 * 팔레트 변경은 (user_id, theme_id)로 시간표를 FOR UPDATE(fk_timetable_theme 인덱스 next-key 락)한 뒤 theme 행을 X로 갱신하고,
 * 시간표 테마 변경은 theme 행 FK S락을 잡은 뒤 같은 인덱스에 쓴다. 위의 일시적 500도 함께 난다. 자세한 재현은 작업 J 보고서 참고.
 */
@StressKnownFailure
class ThemePaletteMixedLoadStressTest : AbstractMixedLoadStressTest("mixed-load-with-palette-change")
