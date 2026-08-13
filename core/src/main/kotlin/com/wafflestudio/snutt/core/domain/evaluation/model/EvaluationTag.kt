package com.wafflestudio.snutt.core.domain.evaluation.model

/**
 * 강의평 홈의 큐레이션 태그. 태그는 저장된 데이터가 아니라 course 단위 평균에 대한 질의 조건이므로
 * 테이블 없이 enum으로 둔다. 판정 단위는 course이며 이는 course.avg_rating의 집계 단위와 같다.
 */
enum class EvaluationTag(
    val key: String,
    val title: String,
    val description: String,
) {
    RECENT("recent", "최신", "최근 등록된 강의평"),
    LIBERAL_EDUCATION("liberal-education", "교양", "교양 과목의 강의평"),
    RECOMMENDED("recommended", "추천", "평점 평균 4.0 이상"),
    WELL_TAUGHT("well-taught", "명강", "강의력과 얻어가는 것 평균 4.0 이상"),
    SWEET("sweet", "꿀강", "학점 만족도와 워라밸 평균 4.0 이상"),
    HARD_BUT_WORTH("hard-but-worth", "고진감래", "워라밸 평균 2.0 미만, 얻어가는 것 평균 4.0 이상"),
    ;

    companion object {
        fun fromKey(key: String): EvaluationTag? = entries.firstOrNull { it.key == key }
    }
}
