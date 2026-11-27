package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceService
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext

/**
 * 시나리오의 요청 하나(= HTTP 요청 하나). PR CI는 서비스를 바로 부르는 [ServiceScenarioActions]를 쓰고,
 * stress는 같은 요청을 실제 HTTP 서버로 보내는 구현을 쓴다. 실패는 모두 [com.wafflestudio.snutt.core.common.error.SnuttException]으로
 * 던져야 시나리오의 verify가 그대로 쓰인다(HTTP 구현은 4xx 응답의 problem type을 ErrorType으로 되돌린다).
 */
interface ScenarioActions {
    fun addLecture(
        userId: Long,
        timetableId: Long,
        lectureId: Long,
    )

    fun addCustomLecture(
        userId: Long,
        timetableId: Long,
        courseTitle: String,
        time: ClassPlaceAndTime,
    )

    fun modifyThemeColors(
        userId: Long,
        themeId: Long,
        colors: List<ColorSet>,
    )

    fun addTimetable(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    )

    fun deleteTimetable(
        userId: Long,
        timetableId: Long,
    )

    fun setPrimary(
        userId: Long,
        timetableId: Long,
    )

    fun createEvaluation(
        userId: Long,
        lectureId: Long,
        request: EvaluationWriteRequest,
    )

    fun deleteEvaluation(
        userId: Long,
        evaluationId: Long,
    )

    fun likeEvaluation(
        userId: Long,
        evaluationId: Long,
    )

    fun cancelLikeEvaluation(
        userId: Long,
        evaluationId: Long,
    )

    fun registerLocal(
        localId: String,
        password: String,
        email: String,
    )

    fun bookmarkLecture(
        userId: Long,
        lectureId: Long,
    )

    fun submitEmptyDiary(
        userId: Long,
        lectureId: Long,
    )

    fun setDefaultTheme(
        userId: Long,
        themeId: Long,
    )

    fun unsetDefaultTheme(
        userId: Long,
        themeId: Long,
    )

    fun deleteTheme(
        userId: Long,
        themeId: Long,
    )

    fun savePushPreferences(
        userId: Long,
        preferences: PushPreferenceDto,
    )

    fun addRegistrationId(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    )

    fun updateNickname(
        userId: Long,
        nickname: String,
    )

    fun modifyReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    )
}

/** 컨트롤러가 부르는 서비스 메서드를 그대로 부른다. */
class ServiceScenarioActions(
    beans: ApplicationContext,
) : ScenarioActions {
    private val timetableService: TimetableService = beans.getBean()
    private val timetableLectureService: TimetableLectureService = beans.getBean()
    private val themeService: TimetableThemeService = beans.getBean()
    private val evaluationService: EvaluationService = beans.getBean()
    private val authService: AuthService = beans.getBean()
    private val bookmarkService: BookmarkService = beans.getBean()
    private val diaryService: DiaryService = beans.getBean()
    private val pushPreferenceService: PushPreferenceService = beans.getBean()
    private val deviceService: DeviceService = beans.getBean()
    private val userService: UserService = beans.getBean()
    private val reminderService: TimetableLectureReminderService = beans.getBean()

    override fun addLecture(
        userId: Long,
        timetableId: Long,
        lectureId: Long,
    ) {
        timetableLectureService.addLecture(userId, timetableId, TimetableLectureAddRequest(lectureId))
    }

    override fun addCustomLecture(
        userId: Long,
        timetableId: Long,
        courseTitle: String,
        time: ClassPlaceAndTime,
    ) {
        timetableLectureService.addCustomLecture(
            userId,
            timetableId,
            CustomTimetableLectureAddRequest(courseTitle = courseTitle, classPlaceAndTimes = listOf(time)),
        )
    }

    override fun modifyThemeColors(
        userId: Long,
        themeId: Long,
        colors: List<ColorSet>,
    ) {
        themeService.modifyTheme(userId, themeId, name = null, colors = colors)
    }

    override fun addTimetable(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    ) {
        timetableService.addTimetable(userId, year, semester, title)
    }

    override fun deleteTimetable(
        userId: Long,
        timetableId: Long,
    ) = timetableService.deleteTimetable(userId, timetableId)

    override fun setPrimary(
        userId: Long,
        timetableId: Long,
    ) = timetableService.setPrimary(userId, timetableId)

    override fun createEvaluation(
        userId: Long,
        lectureId: Long,
        request: EvaluationWriteRequest,
    ) {
        evaluationService.createEvaluation(userId, lectureId, request)
    }

    override fun deleteEvaluation(
        userId: Long,
        evaluationId: Long,
    ) = evaluationService.deleteEvaluation(userId, evaluationId)

    override fun likeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        evaluationService.likeEvaluation(userId, evaluationId)
    }

    override fun cancelLikeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        evaluationService.cancelLikeEvaluation(userId, evaluationId)
    }

    override fun registerLocal(
        localId: String,
        password: String,
        email: String,
    ) {
        authService.registerLocal(localId, password, email)
    }

    override fun bookmarkLecture(
        userId: Long,
        lectureId: Long,
    ) {
        bookmarkService.addLecture(userId, lectureId)
    }

    override fun submitEmptyDiary(
        userId: Long,
        lectureId: Long,
    ) {
        diaryService.submitDiary(userId, DiarySubmissionRequest(lectureId, emptyList(), emptyList(), ""))
    }

    override fun setDefaultTheme(
        userId: Long,
        themeId: Long,
    ) {
        themeService.setDefault(userId, themeId)
    }

    override fun unsetDefaultTheme(
        userId: Long,
        themeId: Long,
    ) {
        themeService.unsetDefault(userId, themeId)
    }

    override fun deleteTheme(
        userId: Long,
        themeId: Long,
    ) = themeService.deleteTheme(userId, themeId)

    override fun savePushPreferences(
        userId: Long,
        preferences: PushPreferenceDto,
    ) {
        pushPreferenceService.savePushPreferences(userId, preferences)
    }

    override fun addRegistrationId(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    ) = deviceService.addRegistrationId(userId, registrationId, clientInfo)

    override fun updateNickname(
        userId: Long,
        nickname: String,
    ) {
        userService.updateNickname(userId, nickname)
    }

    override fun modifyReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    ) {
        reminderService.modifyReminder(userId, timetableId, timetableLectureId, option)
    }
}
