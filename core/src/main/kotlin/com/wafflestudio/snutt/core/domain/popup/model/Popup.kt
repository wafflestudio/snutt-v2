package com.wafflestudio.snutt.core.domain.popup.model

import com.wafflestudio.snutt.core.common.compat.V1Entity
import jakarta.persistence.Entity

@Entity
class Popup(
    var popupKey: String,
    var imageOriginUri: String,
    var linkUrl: String? = null,
    var hiddenDays: Int? = null,
) : V1Entity()
