package com.wafflestudio.snutt.core.common.model

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist

@MappedSuperclass
abstract class ExternalIdEntity(
    // 24-hex 공개 id. 이관 행은 원본 Mongo ObjectId, 신규 행은 생성
    @Column(nullable = false, updatable = false, unique = true, columnDefinition = "char(24)")
    var externalId: String = "",
) : BaseEntity() {
    @PrePersist
    fun assignExternalId() {
        if (externalId.isEmpty()) {
            externalId = ExternalIdGenerator.generate()
        }
    }
}
