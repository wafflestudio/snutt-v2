package com.wafflestudio.snutt.core.domain.popup.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "popup")
class Popup(
    var popupKey: String,
    var imageOriginUri: String,
    var linkUrl: String? = null,
    var hiddenDays: Int? = null,
) : ExternalIdEntity()
