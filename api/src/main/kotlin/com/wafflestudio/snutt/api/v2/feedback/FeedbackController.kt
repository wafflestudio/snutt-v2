package com.wafflestudio.snutt.api.v2.feedback

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.domain.feedback.service.FeedbackService
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class FeedbackPostRequest(
    val email: String?,
    @field:NotBlank val message: String,
)

@RestController
@RequestMapping("/v2/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
) {
    @PostMapping("")
    fun postFeedback(
        @RequestBody body: FeedbackPostRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ) {
        feedbackService.postFeedback(
            email = body.email.orEmpty(),
            message = body.message,
            osType = clientInfo.osType,
            osVersion = clientInfo.osVersion,
            appVersion = clientInfo.appVersion ?: "Unknown",
            deviceModel = clientInfo.deviceModel ?: "Unknown",
        )
    }
}
