package com.wafflestudio.snutt.core.domain.popup.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.repository.PopupRepository
import org.springframework.dao.DataIntegrityViolationException
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
        return try {
            popupRepository.save(popup)
        } catch (e: DataIntegrityViolationException) {
            throw SnuttException(ErrorType.DUPLICATE_POPUP_KEY)
        }
    }

    @Transactional
    fun deletePopup(popupExternalId: String) {
        popupRepository.delete(popupRepository.findByExternalId(popupExternalId) ?: return)
    }
}
