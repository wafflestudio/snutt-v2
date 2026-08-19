package com.wafflestudio.snutt.core.domain.popup.repository

import com.wafflestudio.snutt.core.domain.popup.model.Popup
import org.springframework.data.jpa.repository.JpaRepository

interface PopupRepository : JpaRepository<Popup, Long>
