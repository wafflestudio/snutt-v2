package com.wafflestudio.snutt.core.domain.evaluation.model

enum class EvaluationTag(
    val title: String,
    val description: String,
) {
    RECENT("최신", "최근 등록된 강의평"),
    LIBERAL_EDUCATION("교양", "교양 과목의 강의평"),
    RECOMMENDED("추천", "평점 평균 4.0 이상"),
    WELL_TAUGHT("명강", "강의력과 얻어가는 것 평균 4.0 이상"),
    SWEET("꿀강", "학점 만족도와 워라밸 평균 4.0 이상"),
    HARD_BUT_WORTH("고진감래", "워라밸 평균 2.0 미만, 얻어가는 것 평균 4.0 이상"),
}
