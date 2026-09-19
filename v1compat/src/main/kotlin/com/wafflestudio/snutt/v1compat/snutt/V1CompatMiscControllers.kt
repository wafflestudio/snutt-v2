package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
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
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyOkResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyPageResponse
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

data class LegacyTagListResponse(
    val classification: List<String>,
    val department: List<String>,
    @param:JsonProperty("academic_year")
    val academicYear: List<String>,
    val credit: List<String>,
    val instructor: List<String>,
    val category: List<String>,
    val sortCriteria: List<String>,
    @param:JsonProperty("updated_at")
    val updatedAt: Long?,
    val categoryPre2025: List<String>,
)

@RestController
@RequestMapping("/v1/tags")
class V1CompatTagController(
    private val lectureVocabularyService: LectureVocabularyService,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @V1CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Semester,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTagListResponse {
        val vocabulary = lectureVocabularyService.getVocabulary(year, semester, clientInfo.language)
        return LegacyTagListResponse(
            classification = vocabulary.classification,
            department = vocabulary.department,
            academicYear = vocabulary.academicYear,
            credit =
                vocabulary.credit.map {
                    if (clientInfo.language == Language.EN) {
                        if (it == 1) "1 credit" else "$it credits"
                    } else {
                        "${it}학점"
                    }
                },
            instructor = vocabulary.instructor,
            category = vocabulary.category,
            sortCriteria =
                LectureSort.entries
                    .filter { it != LectureSort.DEFAULT }
                    .map { clientInfo.language.select(it.fullName, it.fullNameEn) },
            updatedAt = vocabulary.updatedAt?.toEpochMilli(),
            categoryPre2025 =
                vocabulary.categoryPre2025.map {
                    LectureCategoryPre2025.localize(it, clientInfo.language)
                },
        )
    }
}

data class LegacyCoursebookDto(
    val year: Int,
    val semester: Int,
    @param:JsonProperty("updated_at")
    val updatedAt: Instant,
)

data class LegacyCoursebookOfficialResponse(
    val noProxyUrl: String,
    val proxyUrl: String?,
    val url: String,
)

@RestController
@V1Public
@RequestMapping("/v1/course_books")
class V1CompatCoursebookController(
    private val coursebookService: CoursebookService,
    @param:Value("\${snutt.syllabus-proxy.base-url:}") private val syllabusProxyBaseUrl: String,
) {
    @GetMapping("")
    fun getCoursebooks(): List<LegacyCoursebookDto> = coursebookService.getCoursebooks().map { it.toLegacy() }

    @GetMapping("/recent")
    fun getLatestCoursebook(): LegacyCoursebookDto = coursebookService.getLatestCoursebook().toLegacy()

    @GetMapping("/official")
    fun getCoursebookOfficial(
        @RequestParam year: Int,
        @RequestParam semester: Semester,
        @RequestParam("course_number") courseNumber: String,
        @RequestParam("lecture_number") lectureNumber: String,
    ): LegacyCoursebookOfficialResponse {
        val syllabusPath = SugangSnuUrlUtils.parseSyllabusPath(year, semester, courseNumber, lectureNumber)
        val proxyUrl = syllabusProxyBaseUrl.takeIf { it.isNotBlank() }?.plus(syllabusPath)
        return LegacyCoursebookOfficialResponse(
            noProxyUrl = SugangSnuUrlUtils.SUGANG_SNU_BASE_URL + syllabusPath,
            proxyUrl = proxyUrl,
            url = proxyUrl ?: (SugangSnuUrlUtils.SUGANG_SNU_BASE_URL + syllabusPath),
        )
    }

    private fun Coursebook.toLegacy() =
        LegacyCoursebookDto(
            year = year,
            semester = semester.value,
            updatedAt = checkNotNull(updatedAt),
        )
}

data class LegacyLectureBuildingDto(
    val id: String,
    val buildingNumber: String,
    val buildingNameKor: String,
    val buildingNameEng: String,
    val campus: String,
    @param:JsonProperty("locationInDMS")
    val locationInDms: LegacyGeoCoordinateDto?,
    val locationInDecimal: LegacyGeoCoordinateDto?,
)

data class LegacyGeoCoordinateDto(
    val latitude: Double,
    val longitude: Double,
)

@RestController
@V1Public
@RequestMapping("/v1/buildings")
class V1CompatBuildingController(
    private val lectureBuildingService: LectureBuildingService,
) {
    @GetMapping("")
    fun searchBuildings(
        @RequestParam places: String,
    ): LegacyPageResponse<LegacyLectureBuildingDto> {
        val placeQuery = places.split(",").flatMap { PlaceInfo.getValuesOf(it) }.distinct()
        val content = lectureBuildingService.getLectureBuildings(placeQuery).map { it.toLegacy() }
        return LegacyPageResponse(content = content, totalCount = content.size)
    }

    private fun LectureBuilding.toLegacy() =
        LegacyLectureBuildingDto(
            id = id!!.toString(),
            buildingNumber = buildingNumber,
            buildingNameKor = buildingNameKor,
            buildingNameEng = buildingNameEng,
            campus = campus.name,
            locationInDms = locationInDms?.toLegacy(),
            locationInDecimal = locationInDecimal?.toLegacy(),
        )

    private fun GeoCoordinate.toLegacy() = LegacyGeoCoordinateDto(latitude = latitude, longitude = longitude)
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
    ): LegacyOkResponse {
        deviceService.addRegistrationId(user.id!!, registrationId, clientInfo)
        return LegacyOkResponse()
    }

    @DeleteMapping("/{registrationId}")
    fun removeRegistrationId(
        @V1CurrentUser user: User,
        @PathVariable registrationId: String,
    ): LegacyOkResponse {
        deviceService.removeRegistrationId(user.id!!, registrationId)
        return LegacyOkResponse()
    }
}

data class LegacySemesterStatusResponse(
    val current: LegacyYearAndSemesterDto?,
    val next: LegacyYearAndSemesterDto,
)

data class LegacyYearAndSemesterDto(
    val year: Int,
    val semester: Semester,
)

@RestController
@RequestMapping("/v1/semesters")
class V1CompatSemesterController(
    private val semesterService: SemesterService,
) {
    @GetMapping("/status")
    fun getSemesterStatus(): LegacySemesterStatusResponse {
        val now = Instant.now()
        return LegacySemesterStatusResponse(
            current =
                semesterService.getCurrentYearAndSemester(now)?.let {
                    LegacyYearAndSemesterDto(year = it.year, semester = it.semester)
                },
            next =
                semesterService.getNextYearAndSemester(now).let {
                    LegacyYearAndSemesterDto(year = it.year, semester = it.semester)
                },
        )
    }
}
