package com.wafflestudio.snutt.api.v2.deeplink

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.v1compat.id.V1IdRepository
import com.wafflestudio.snutt.v1compat.id.V1IdTable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DeeplinkResolveRequest(
    @field:Schema(description = "딥링크의 시간표 ObjectId", pattern = "[0-9a-fA-F]{24}")
    val timetableId: String? = null,
    @field:Schema(description = "딥링크의 수강편람 강의 ObjectId", pattern = "[0-9a-fA-F]{24}")
    val lectureId: String? = null,
)

data class DeeplinkResolveResponse(
    val timetableId: Long?,
    val lectureId: Long?,
)

@RestController
@RequestMapping("/v2/deeplinks")
class DeeplinkController(
    private val v1Ids: V1IdRepository,
    private val timetableService: TimetableService,
) {
    @Operation(summary = "딥링크의 ObjectId를 v2 id로 변환", description = "timetableId와 lectureId 중 하나 이상을 보낸다. 시간표는 요청한 사용자의 소유여야 한다.")
    @Transactional(readOnly = true)
    @PostMapping("/resolve")
    fun resolve(
        @Parameter(hidden = true) @RequestAttribute("userId") userId: Long,
        @RequestBody request: DeeplinkResolveRequest,
    ): DeeplinkResolveResponse {
        if (request.timetableId == null && request.lectureId == null) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val timetableId = request.timetableId?.let { v1Ids.id(V1IdTable.TIMETABLE, it) }
        if (timetableId != null) timetableService.getTimetable(userId, timetableId)
        val lectureId = request.lectureId?.let { v1Ids.id(V1IdTable.LECTURE, it) }
        return DeeplinkResolveResponse(timetableId, lectureId)
    }
}
