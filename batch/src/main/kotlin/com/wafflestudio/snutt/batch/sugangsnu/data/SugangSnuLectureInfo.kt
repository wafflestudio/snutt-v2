package com.wafflestudio.snutt.batch.sugangsnu.data

import com.fasterxml.jackson.annotation.JsonProperty

data class SugangSnuLectureInfo(
    @param:JsonProperty("ltTime")
    val ltTime: List<String> = emptyList(),
    @param:JsonProperty("ltRoom")
    val ltRoom: List<String> = emptyList(),
    @param:JsonProperty("LISTTAB01")
    val subInfo: SugangSnuLectureSubInfo = SugangSnuLectureSubInfo(),
)

data class SugangSnuLectureSubInfo(
    @param:JsonProperty("sbjtNm")
    val courseName: String? = null,
    @param:JsonProperty("sbjtSubhNm")
    val courseSubName: String? = null,
    @param:JsonProperty("profNm")
    val professorName: String? = null,
    @param:JsonProperty("sbjtFldNm")
    val category: String? = null,
    @param:JsonProperty("departmentKorNm")
    val departmentKorNm: String? = null,
    @param:JsonProperty("majorKorNm")
    val majorKorNm: String? = null,
    @param:JsonProperty("cptnCorsFgNm")
    val academicCourse: String? = null,
    @param:JsonProperty("openShyr")
    val academicYear: String? = null,
    @param:JsonProperty("tlsnAplyCapaCnt")
    val quota: Int? = null,
    @param:JsonProperty("openLtRemk")
    val remark: String? = null,
    @param:JsonProperty("sbjtEngNm")
    val courseNameEng: String? = null,
    @param:JsonProperty("sbjtSubhEngNm")
    val courseSubNameEng: String? = null,
    @param:JsonProperty("profEngNm")
    val professorNameEng: String? = null,
    @param:JsonProperty("sbjtFldEngNm")
    val categoryEng: String? = null,
    @param:JsonProperty("openLtEngRemk")
    val remarkEng: String? = null,
    @param:JsonProperty("cptnCorsFgEngNm")
    val academicCourseEng: String? = null,
    @param:JsonProperty("departmentEngNm")
    val departmentEngNm: String? = null,
    @param:JsonProperty("majorEngNm")
    val majorEngNm: String? = null,
)
