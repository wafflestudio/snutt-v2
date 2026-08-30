package com.wafflestudio.snutt.core.domain.diary.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestionTarget
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmission
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmissionAnswer
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmissionDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.QuestionAnswer
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionTargetRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionAnswerRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

data class DiaryQuestionnaireRequest(
    val lectureId: Long,
    val dailyClassTypes: List<String>,
)

data class DiarySubmissionRequest(
    val lectureId: Long,
    val dailyClassTypes: List<String>,
    val questionAnswers: List<QuestionAnswer>,
    val comment: String,
)

data class DiaryQuestionnaireDisplay(
    val courseTitle: String,
    val courseTitleEn: String?,
    val questions: List<DiaryQuestion>,
    val nextLecture: TimetableLectureDisplay?,
)

@Service
class DiaryService(
    private val diaryDailyClassTypeRepository: DiaryDailyClassTypeRepository,
    private val diaryQuestionRepository: DiaryQuestionRepository,
    private val diarySubmissionRepository: DiarySubmissionRepository,
    private val diaryQuestionTargetRepository: DiaryQuestionTargetRepository,
    private val diarySubmissionDailyClassTypeRepository: DiarySubmissionDailyClassTypeRepository,
    private val diarySubmissionAnswerRepository: DiarySubmissionAnswerRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val timetableService: TimetableService,
) {
    companion object {
        const val COMMENT_MAX_LENGTH = 1000
        private const val QUESTION_COUNT = 3
    }

    fun generateQuestionnaire(
        userId: Long,
        request: DiaryQuestionnaireRequest,
    ): DiaryQuestionnaireDisplay {
        val dailyClassTypeIds = diaryDailyClassTypeRepository.findAllByNameIn(request.dailyClassTypes).mapNotNull { it.id }
        val targetedQuestionIds =
            diaryQuestionTargetRepository
                .findByDailyClassTypeIdIn(dailyClassTypeIds)
                .map { it.questionId }
                .toSet()
        val questions =
            diaryQuestionRepository
                .findAllByActiveTrue()
                .filter { it.id in targetedQuestionIds }
                .shuffled()
                .take(QUESTION_COUNT)
        val lecture =
            lectureRepository.findByIdOrNull(request.lectureId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val nextLecture = getDiaryTargetLecture(userId, lecture.year, lecture.semester, listOf(lecture.id!!))
        return DiaryQuestionnaireDisplay(
            courseTitle = lecture.courseTitle,
            courseTitleEn = lecture.courseTitleEn,
            questions = questions,
            nextLecture = nextLecture,
        )
    }

    fun getDiaryTargetLecture(
        userId: Long,
        year: Int,
        semester: Semester,
        lectureIdsToExclude: List<Long>,
    ): TimetableLectureDisplay? {
        val timetable =
            timetableRepository.findByUserIdAndYearAndSemesterAndIsPrimaryTrue(userId, year, semester)
                ?: throw SnuttException(ErrorType.PRIMARY_TIMETABLE_NOT_FOUND)
        val recentlySubmittedIds =
            diarySubmissionRepository
                .findByUserIdAndCreatedAtAfter(userId, Instant.now().minus(1, ChronoUnit.DAYS))
                .mapNotNull { it.lectureId }
        val timetableLectures =
            timetableLectureRepository.findByTimetableId(timetable.id!!).filter { it.lectureId != null }
        val eligible = timetableLectures.filter { it.lectureId !in lectureIdsToExclude }
        val candidates = eligible.filter { it.lectureId !in recentlySubmittedIds }.ifEmpty { eligible }
        if (candidates.isEmpty()) return null
        val picked = candidates.random()
        return timetableService
            .displaysOf(listOf(timetable))[timetable.id]
            ?.first { it.id == picked.id }
    }

    fun getActiveDailyClassTypes(): List<DiaryDailyClassType> = diaryDailyClassTypeRepository.findAllByActiveTrueOrderByNameAsc()

    fun getAllDailyClassTypes(): List<DiaryDailyClassType> = diaryDailyClassTypeRepository.findAll()

    fun getActiveQuestions(): List<DiaryQuestion> = diaryQuestionRepository.findAllByActiveTrue()

    @Transactional
    fun submitDiary(
        userId: Long,
        request: DiarySubmissionRequest,
    ) {
        if (request.comment.length > COMMENT_MAX_LENGTH) throw SnuttException(ErrorType.DIARY_COMMENT_TOO_LONG)
        val lecture =
            lectureRepository.findByIdOrNull(request.lectureId)
                ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        val questionIds = request.questionAnswers.map { it.questionId }
        if (questionIds.size != questionIds.toSet().size) throw SnuttException(ErrorType.DIARY_QUESTION_INVALID)
        val questionsById = diaryQuestionRepository.findAllById(questionIds).associateBy { it.id }
        if (questionsById.size != questionIds.size) throw SnuttException(ErrorType.DIARY_QUESTION_NOT_FOUND)
        if (questionsById.values.any { !it.active }) throw SnuttException(ErrorType.DIARY_QUESTION_INVALID)
        request.questionAnswers.forEach { answer ->
            val question = questionsById[answer.questionId] ?: throw SnuttException(ErrorType.DIARY_QUESTION_NOT_FOUND)
            if (answer.answerIndex !in question.answerList.indices) {
                throw SnuttException(ErrorType.DIARY_QUESTION_INVALID)
            }
        }
        if (request.dailyClassTypes.size != request.dailyClassTypes.toSet().size) {
            throw SnuttException(ErrorType.DIARY_DAILY_CLASS_TYPE_NOT_FOUND)
        }
        val dailyClassTypeIds = diaryDailyClassTypeRepository.findAllByNameIn(request.dailyClassTypes).mapNotNull { it.id }
        if (dailyClassTypeIds.size != request.dailyClassTypes.size) {
            throw SnuttException(ErrorType.DIARY_DAILY_CLASS_TYPE_NOT_FOUND)
        }
        val submission =
            diarySubmissionRepository.save(
                DiarySubmission(
                    userId = userId,
                    year = lecture.year,
                    semester = lecture.semester,
                    lectureId = lecture.id,
                    courseTitle = lecture.courseTitle,
                    comment = request.comment,
                ),
            )
        diarySubmissionDailyClassTypeRepository.saveAll(
            dailyClassTypeIds.map { DiarySubmissionDailyClassType(submission.id!!, it) },
        )
        diarySubmissionAnswerRepository.saveAll(
            request.questionAnswers.map { DiarySubmissionAnswer(submission.id!!, it.questionId, it.answerIndex) },
        )
    }

    fun getMySubmissions(userId: Long): List<DiarySubmission> = diarySubmissionRepository.findByUserIdOrderByCreatedAtDesc(userId)

    data class DiaryShortQuestionReply(
        val questionId: Long,
        val shortQuestion: String,
        val shortAnswer: String,
    )

    fun getSubmissionIdShortQuestionRepliesMap(submissions: List<DiarySubmission>): Map<Long, List<DiaryShortQuestionReply>> {
        val answersBySubmissionId =
            diarySubmissionAnswerRepository
                .findBySubmissionIdIn(submissions.mapNotNull { it.id })
                .groupBy { it.submissionId }
        val questions =
            diaryQuestionRepository
                .findAllById(
                    answersBySubmissionId.values
                        .flatten()
                        .map { it.questionId }
                        .distinct(),
                ).associateBy { it.id!! }
        return submissions.associate { submission ->
            submission.id!! to
                answersBySubmissionId[submission.id].orEmpty().mapNotNull { answer ->
                    val question = questions[answer.questionId] ?: return@mapNotNull null
                    DiaryShortQuestionReply(
                        questionId = answer.questionId,
                        shortQuestion = question.shortQuestion,
                        shortAnswer = question.shortAnswerList.getOrNull(answer.answerIndex).orEmpty(),
                    )
                }
        }
    }

    @Transactional
    fun removeSubmission(
        submissionId: Long,
        userId: Long,
    ) {
        val submission =
            diarySubmissionRepository.findByIdOrNull(submissionId)
                ?: throw SnuttException(ErrorType.DIARY_SUBMISSION_NOT_FOUND)
        if (submission.userId != userId) throw SnuttException(ErrorType.DIARY_SUBMISSION_NOT_FOUND)
        diarySubmissionAnswerRepository.deleteBySubmissionId(submissionId)
        diarySubmissionDailyClassTypeRepository.deleteBySubmissionId(submissionId)
        diarySubmissionRepository.delete(submission)
    }

    @Transactional
    fun addOrEnableDailyClassType(name: String) {
        val existing = diaryDailyClassTypeRepository.findAll().firstOrNull { it.name == name }
        if (existing != null) {
            existing.active = true
        } else {
            diaryDailyClassTypeRepository.save(DiaryDailyClassType(name = name))
        }
    }

    @Transactional
    fun disableDailyClassType(name: String) {
        diaryDailyClassTypeRepository.findAll().firstOrNull { it.name == name }?.let { it.active = false }
    }

    @Transactional
    fun addQuestion(
        question: String,
        shortQuestion: String,
        answers: List<String>,
        shortAnswers: List<String>,
        targetDailyClassTypes: List<String>,
        active: Boolean = true,
    ) {
        val targetIds = diaryDailyClassTypeRepository.findAllByNameIn(targetDailyClassTypes).mapNotNull { it.id }
        val questionEntity =
            diaryQuestionRepository.save(
                DiaryQuestion(
                    question = question,
                    shortQuestion = shortQuestion,
                    answerList = answers,
                    shortAnswerList = shortAnswers,
                    active = active,
                ),
            )
        diaryQuestionTargetRepository.saveAll(
            targetIds.map { DiaryQuestionTarget(questionEntity.id!!, it) },
        )
    }

    @Transactional
    fun removeQuestion(questionId: Long) {
        diaryQuestionRepository.findByIdOrNull(questionId)?.let { it.active = false }
    }
}
