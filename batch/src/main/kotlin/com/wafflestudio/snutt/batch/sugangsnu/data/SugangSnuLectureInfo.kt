package com.wafflestudio.snutt.batch.sugangsnu.data

import com.fasterxml.jackson.annotation.JsonProperty

// 수강스누 강좌 상세 팝업 응답 (cc101ajax.action). xlsx에 없는 정확한 시간/강의실/교양분류를 채운다
data class SugangSnuLectureInfo(
    @JsonProperty("ltTime")
    val ltTime: List<String> = emptyList(),
    @JsonProperty("ltRoom")
    val ltRoom: List<String> = emptyList(),
    @JsonProperty("LISTTAB01")
    val subInfo: SugangSnuLectureSubInfo = SugangSnuLectureSubInfo(),
)

data class SugangSnuLectureSubInfo(
    @JsonProperty("sbjtNm")
    val courseName: String? = null,
    @JsonProperty("sbjtSubhNm")
    val courseSubName: String? = null,
    @JsonProperty("profNm")
    val professorName: String? = null,
    @JsonProperty("sbjtFldNm")
    val category: String? = null,
    @JsonProperty("departmentKorNm")
    val departmentKorNm: String? = null,
    @JsonProperty("majorKorNm")
    val majorKorNm: String? = null,
    // 학사/석사 등
    @JsonProperty("cptnCorsFgNm")
    val academicCourse: String? = null,
    // 학년
    @JsonProperty("openShyr")
    val academicYear: String? = null,
    @JsonProperty("tlsnAplyCapaCnt")
    val quota: Int? = null,
    @JsonProperty("openLtRemk")
    val remark: String? = null,
    // 영문 (i18n)
    @JsonProperty("sbjtEngNm")
    val courseNameEng: String? = null,
    @JsonProperty("sbjtSubhEngNm")
    val courseSubNameEng: String? = null,
    @JsonProperty("profEngNm")
    val professorNameEng: String? = null,
    @JsonProperty("sbjtFldEngNm")
    val categoryEng: String? = null,
    @JsonProperty("openLtEngRemk")
    val remarkEng: String? = null,
    @JsonProperty("cptnCorsFgEngNm")
    val academicCourseEng: String? = null,
    @JsonProperty("departmentEngNm")
    val departmentEngNm: String? = null,
    @JsonProperty("majorEngNm")
    val majorEngNm: String? = null,
)
