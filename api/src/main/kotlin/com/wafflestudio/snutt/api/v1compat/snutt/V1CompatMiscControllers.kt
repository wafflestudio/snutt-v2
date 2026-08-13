package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.util.SugangSnuUrlUtils
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.service.LectureBuildingService
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.tag.service.TagListService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// v1 태그 목록 (tag_list)
@RestController
@RequestMapping("/v1/tags", "/tags")
class V1CompatTagController(
    private val tagListService: TagListService,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): Map<String, Any?> {
        val tagList =
            tagListService.getTagList(
                year,
                Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
            )
        val collection = tagList.tagCollection

        fun localize(
            ko: List<String>,
            en: List<String>,
        ): List<String> = if (clientInfo.language == Language.EN) en.ifEmpty { ko } else ko
        return linkedMapOf(
            "classification" to localize(collection.classification, collection.classificationEn),
            "department" to localize(collection.department, collection.departmentEn),
            "academic_year" to localize(collection.academicYear, collection.academicYearEn),
            "credit" to collection.credit,
            "instructor" to localize(collection.instructor, collection.instructorEn),
            "category" to localize(collection.category, collection.categoryEn),
            "sortCriteria" to LectureSort.entries.filter { it != LectureSort.DEFAULT }.map { it.fullName },
            "updated_at" to checkNotNull(tagList.updatedAt).toEpochMilli(),
            "categoryPre2025" to collection.categoryPre2025,
        )
    }
}

// v1 수강편람
@RestController
@Public
@RequestMapping("/v1/course_books", "/course_books")
class V1CompatCoursebookController(
    private val coursebookService: CoursebookService,
    @Value("\${snutt.syllabus-proxy.base-url}") private val syllabusProxyBaseUrl: String,
) {
    @GetMapping("")
    fun getCoursebooks(): List<Map<String, Any?>> = coursebookService.getCoursebooks().map { it.toLegacy() }

    @GetMapping("/recent")
    fun getLatestCoursebook(): Map<String, Any?> = coursebookService.getLatestCoursebook().toLegacy()

    @GetMapping("/official")
    fun getCoursebookOfficial(
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestParam("course_number") courseNumber: String,
        @RequestParam("lecture_number") lectureNumber: String,
    ): Map<String, Any?> {
        val semesterValue =
            Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val syllabusPath = SugangSnuUrlUtils.parseSyllabusPath(year, semesterValue, courseNumber, lectureNumber)
        val proxyUrl = syllabusProxyBaseUrl.takeIf { it.isNotBlank() }?.plus(syllabusPath)
        return linkedMapOf(
            "noProxyUrl" to SugangSnuUrlUtils.SUGANG_SNU_BASE_URL + syllabusPath,
            "proxyUrl" to proxyUrl,
            "url" to (proxyUrl ?: SugangSnuUrlUtils.SUGANG_SNU_BASE_URL + syllabusPath),
        )
    }

    private fun Coursebook.toLegacy() =
        linkedMapOf(
            "year" to year,
            "semester" to semester.value,
            "updated_at" to checkNotNull(updatedAt),
        )
}

// v1 건물 검색
@RestController
@Public
@RequestMapping("/v1/buildings", "/buildings")
class V1CompatBuildingController(
    private val lectureBuildingService: LectureBuildingService,
) {
    @GetMapping("")
    fun searchBuildings(
        @RequestParam places: String,
    ): Map<String, Any?> {
        val placeQuery = places.split(",").flatMap { PlaceInfo.getValuesOf(it) }.distinct()
        val content = lectureBuildingService.getLectureBuildings(placeQuery).map { it.toLegacy() }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    private fun LectureBuilding.toLegacy() =
        linkedMapOf(
            "id" to externalId,
            "buildingNumber" to buildingNumber,
            "buildingNameKor" to buildingNameKor,
            "buildingNameEng" to buildingNameEng,
            "campus" to campus.name,
            "locationInDMS" to locationInDms?.toLegacy(),
            "locationInDecimal" to locationInDecimal?.toLegacy(),
        )

    private fun GeoCoordinate.toLegacy() = linkedMapOf("latitude" to latitude, "longitude" to longitude)
}
