package com.wafflestudio.snutt.core.domain.popup.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.repository.PopupRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PopupWriteRequest(
    val popupKey: String,
    val imageOriginUri: String,
    val linkUrl: String? = null,
    val hiddenDays: Int? = null,
)

@Service
class PopupService(
    private val popupRepository: PopupRepository,
) {
    fun getPopups(): List<Popup> = popupRepository.findAll().sortedBy { it.createdAt }

    @Transactional
    fun postPopup(request: PopupWriteRequest): Popup {
        val popup =
            Popup(
                popupKey = request.popupKey,
                imageOriginUri = request.imageOriginUri,
                linkUrl = request.linkUrl,
                hiddenDays = request.hiddenDays,
            )
        return conflictAs(ErrorType.DUPLICATE_POPUP_KEY) { popupRepository.save(popup) }
    }

    @Transactional
    fun deletePopup(popupId: Long) {
        popupRepository.delete(popupRepository.findByIdOrNull(popupId) ?: return)
    }

    @Transactional
    fun deletePopup(popupExternalId: String) {
        deletePopup(popupExternalId.toLong())
    }
}
