package com.wafflestudio.snutt.api.v2.popup

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.storage.StorageUriResolver
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.service.PopupService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PopupResponse(
    val id: String,
    val popupKey: String,
    val imageUri: String,
    val linkUrl: String?,
    val hiddenDays: Int?,
)

private fun Popup.toResponse(storageUriResolver: StorageUriResolver) =
    PopupResponse(
        id = externalId,
        popupKey = popupKey,
        imageUri = storageUriResolver.resolve(imageOriginUri),
        linkUrl = linkUrl,
        hiddenDays = hiddenDays,
    )

@RestController
@Public
@RequestMapping("/v2/popups")
class PopupController(
    private val popupService: PopupService,
    private val storageUriResolver: StorageUriResolver,
) {
    @GetMapping("")
    fun getPopups(
        @RequestAttribute clientInfo: ClientInfo,
    ): List<PopupResponse> = popupService.getPopups().map { it.toResponse(storageUriResolver) }
}
