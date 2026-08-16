package com.wafflestudio.snutt.api.v2.diary

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmission
import com.wafflestudio.snutt.core.domain.diary.model.QuestionAnswer
import com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireDisplay
import com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireRequest
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class DiaryQuestionnaireRequestDto(
    @field:NotBlank val lectureId: String,
    val dailyClassTypes: List<String>,
)

data class DiarySubmissionRequestDto(
    @field:NotBlank val lectureId: String,
    val dailyClassTypes: List<String>,
    val questionAnswers: List<QuestionAnswer>,
    val comment: String,
)

data class DiaryQuestionnaireResponse(
    val courseTitle: String,
    val questions: List<DiaryQuestionResponse>,
    val nextLecture: DiaryTargetLectureResponse?,
)

data class DiaryQuestionResponse(
    val id: Long,
    val question: String,
    val shortQuestion: String,
    val answers: List<String>,
    val shortAnswers: List<String>,
)

data class DiaryTargetLectureResponse(
    val id: String,
    val courseTitle: String,
    val instructor: String?,
    val credit: Int?,
    val classPlaceAndTimes: List<ClassPlaceAndTime>,
)

data class DiaryDailyClassTypeResponse(
    val id: String,
    val name: String,
)

data class DiarySubmissionSummaryResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val courseTitle: String,
    val comment: String,
    val createdAt: Long,
    val shortQuestionReplies: List<DiaryShortQuestionReplyResponse>,
)

data class DiaryShortQuestionReplyResponse(
    val questionId: Long,
    val shortQuestion: String,
    val shortAnswer: String,
)

data class DiarySubmissionsOfYearSemesterResponse(
    val year: Int,
    val semester: Semester,
    val submissions: List<DiarySubmissionSummaryResponse>,
)

private fun DiaryQuestionnaireDisplay.toResponse(language: Language) =
    DiaryQuestionnaireResponse(
        courseTitle = language.select(courseTitle, courseTitleEn),
        questions =
            questions.map {
                DiaryQuestionResponse(
                    id = checkNotNull(it.id),
                    question = it.question,
                    shortQuestion = it.shortQuestion,
                    answers = it.answerList,
                    shortAnswers = it.shortAnswerList,
                )
            },
        nextLecture = nextLecture?.toResponse(language),
    )

private fun TimetableLectureDisplay.toResponse(language: Language) =
    DiaryTargetLectureResponse(
        id = id,
        courseTitle = language.select(courseTitle, courseTitleEn),
        instructor = language.select(instructor, instructorEn),
        credit = credit,
        classPlaceAndTimes = classPlaceAndTimes,
    )

private fun DiaryDailyClassType.toResponse() = DiaryDailyClassTypeResponse(id = externalId, name = name)

@RestController
@RequestMapping("/v2/diary")
class DiaryController(
    private val diaryService: DiaryService,
) {
    @PostMapping("/questionnaire")
    fun getQuestionnaire(
        @CurrentUser user: User,
        @RequestBody body: DiaryQuestionnaireRequestDto,
        @RequestAttribute clientInfo: ClientInfo,
    ): DiaryQuestionnaireResponse =
        diaryService
            .generateQuestionnaire(
                user.id!!,
                DiaryQuestionnaireRequest(lectureId = body.lectureId, dailyClassTypes = body.dailyClassTypes),
            ).toResponse(clientInfo.language)

    @GetMapping("/target")
    fun getRandomTargetLecture(
        @CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): DiaryTargetLectureResponse {
        val target =
            diaryService.getDiaryTargetLecture(
                user.id!!,
                year,
                Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
                emptyList(),
            ) ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        return target.toResponse(clientInfo.language)
    }

    @GetMapping("/daily-class-types")
    fun getDailyClassTypes(
        @CurrentUser user: User,
    ): List<DiaryDailyClassTypeResponse> = diaryService.getActiveDailyClassTypes().map { it.toResponse() }

    @GetMapping("/my")
    fun getMySubmissions(
        @CurrentUser user: User,
    ): List<DiarySubmissionsOfYearSemesterResponse> {
        val submissions = diaryService.getMySubmissions(user.id!!)
        val repliesMap = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        return submissions
            .groupBy { it.year to it.semester }
            .map { (key, list) ->
                DiarySubmissionsOfYearSemesterResponse(
                    year = key.first,
                    semester = key.second,
                    submissions =
                        list.map {
                            it.toSummary(
                                repliesMap[it.id].orEmpty().map { reply ->
                                    DiaryShortQuestionReplyResponse(reply.questionId, reply.shortQuestion, reply.shortAnswer)
                                },
                            )
                        },
                )
            }.sortedWith(compareByDescending<DiarySubmissionsOfYearSemesterResponse> { it.year }.thenByDescending { it.semester.value })
    }

    @PostMapping("")
    fun submitDiary(
        @CurrentUser user: User,
        @RequestBody body: DiarySubmissionRequestDto,
    ) {
        diaryService.submitDiary(
            user.id!!,
            DiarySubmissionRequest(
                lectureId = body.lectureId,
                dailyClassTypes = body.dailyClassTypes,
                questionAnswers = body.questionAnswers,
                comment = body.comment,
            ),
        )
    }

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @CurrentUser user: User,
        @PathVariable submissionId: String,
    ) {
        diaryService.removeSubmission(submissionId, user.id!!)
    }
}

private fun DiarySubmission.toSummary(replies: List<DiaryShortQuestionReplyResponse>): DiarySubmissionSummaryResponse =
    DiarySubmissionSummaryResponse(
        id = externalId,
        year = year,
        semester = semester,
        courseTitle = courseTitle,
        comment = comment,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
        shortQuestionReplies = replies,
    )
