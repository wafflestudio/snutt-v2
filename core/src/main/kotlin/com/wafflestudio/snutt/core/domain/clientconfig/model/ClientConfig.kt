package com.wafflestudio.snutt.core.domain.clientconfig.model

import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.client.compareAppVersions
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode

@Entity
class ClientConfig(
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var osType: OsType,
    var minVersion: String? = null,
    var maxVersion: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var value: JsonNode,
) : BaseEntity() {
    fun isAdaptable(
        osType: OsType,
        appVersion: String,
    ): Boolean =
        this.osType == osType &&
            (minVersion == null || compareAppVersions(appVersion, minVersion!!) >= 0) &&
            (maxVersion == null || compareAppVersions(appVersion, maxVersion!!) <= 0)
}
