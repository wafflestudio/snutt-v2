package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.client.LocalizedMessage

enum class EvaluationTag(
    val title: LocalizedMessage,
    val description: LocalizedMessage,
) {
    RECENT(
        LocalizedMessage(ko = "최신", en = "Recent"),
        LocalizedMessage(ko = "최근 등록된 강의평", en = "Recently posted reviews"),
    ),
    LIBERAL_EDUCATION(
        LocalizedMessage(ko = "교양", en = "Liberal Arts"),
        LocalizedMessage(ko = "교양 과목의 강의평", en = "Reviews of liberal arts courses"),
    ),
    RECOMMENDED(
        LocalizedMessage(ko = "추천", en = "Recommended"),
        LocalizedMessage(ko = "평점 평균 4.0 이상", en = "Average rating of 4.0 or higher"),
    ),
    WELL_TAUGHT(
        LocalizedMessage(ko = "명강", en = "Well Taught"),
        LocalizedMessage(ko = "강의력과 얻어가는 것 평균 4.0 이상", en = "Average teaching skill and gains of 4.0 or higher"),
    ),
    SWEET(
        LocalizedMessage(ko = "꿀강", en = "Easy A"),
        LocalizedMessage(ko = "학점 만족도와 워라밸 평균 4.0 이상", en = "Average grade satisfaction and work-life balance of 4.0 or higher"),
    ),
    HARD_BUT_WORTH(
        LocalizedMessage(ko = "고진감래", en = "Worth the Effort"),
        LocalizedMessage(ko = "워라밸 평균 2.0 미만, 얻어가는 것 평균 4.0 이상", en = "Average work-life balance below 2.0, gains of 4.0 or higher"),
    ),
}
