package com.wafflestudio.snutt.core.common.model

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist

@MappedSuperclass
abstract class ExternalIdEntity(
    @Column(nullable = false, updatable = false, unique = true, columnDefinition = "varchar(32)")
    var externalId: String = "",
) : BaseEntity() {
    @PrePersist
    fun assignExternalId() {
        if (externalId.isEmpty()) {
            externalId = ExternalIdGenerator.generate()
        }
    }
}
