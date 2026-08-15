package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.common.client.ClientInfo
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
import com.wafflestudio.snutt.core.domain.coursebook.service.SemesterService
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.service.LectureVocabularyService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/v1/tags")
class V1CompatTagController(
    private val lectureVocabularyService: LectureVocabularyService,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @V1CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): Map<String, Any?> {
        val parsedSemester = Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val vocabulary = lectureVocabularyService.getVocabulary(year, parsedSemester, clientInfo.language)
        return linkedMapOf(
            "classification" to vocabulary.classification,
            "department" to vocabulary.department,
            "academic_year" to vocabulary.academicYear,
            "credit" to vocabulary.credit.map { "${it}학점" },
            "instructor" to vocabulary.instructor,
            "category" to vocabulary.category,
            "sortCriteria" to LectureSort.entries.filter { it != LectureSort.DEFAULT }.map { it.fullName },
            "updated_at" to vocabulary.updatedAt?.toEpochMilli(),
            "categoryPre2025" to vocabulary.categoryPre2025,
        )
    }
}

@RestController
@V1Public
@RequestMapping("/v1/course_books")
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

@RestController
@V1Public
@RequestMapping("/v1/buildings")
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

@RestController
@RequestMapping("/v1/user/device")
class V1CompatDeviceController(
    private val deviceService: DeviceService,
) {
    @PostMapping("/{registrationId}")
    fun addRegistrationId(
        @V1CurrentUser user: User,
        @PathVariable registrationId: String,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ) {
        if (registrationId.isBlank()) throw SnuttException(ErrorType.INVALID_PARAMETER)
        deviceService.addRegistrationId(user, registrationId, clientInfo)
    }

    @DeleteMapping("/{registrationId}")
    fun removeRegistrationId(
        @V1CurrentUser user: User,
        @PathVariable registrationId: String,
    ) {
        if (registrationId.isBlank()) throw SnuttException(ErrorType.INVALID_PARAMETER)
        deviceService.removeRegistrationId(user, registrationId)
    }
}

@RestController
@V1Public
@RequestMapping("/v1/semesters")
class V1CompatSemesterController(
    private val semesterService: SemesterService,
) {
    @GetMapping("/status")
    fun getSemesterStatus(): Map<String, Any?> {
        val now = Instant.now()
        return linkedMapOf(
            "current" to
                semesterService.getCurrentYearAndSemester(now)?.let {
                    linkedMapOf("year" to it.year, "semester" to it.semester)
                },
            "next" to
                semesterService.getNextYearAndSemester(now).let {
                    linkedMapOf("year" to it.year, "semester" to it.semester)
                },
        )
    }
}
