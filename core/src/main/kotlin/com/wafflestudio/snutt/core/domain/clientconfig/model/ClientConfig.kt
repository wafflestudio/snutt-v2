package com.wafflestudio.snutt.core.domain.clientconfig.model

import com.wafflestudio.snutt.core.common.client.OsType
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
            (minVersion == null || compareVersions(appVersion, minVersion!!) >= 0) &&
            (maxVersion == null || compareVersions(appVersion, maxVersion!!) <= 0)

    companion object {
        private fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val aParts = a.split('.').map { it.toIntOrNull() ?: 0 }
            val bParts = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val diff = (aParts.getOrNull(i) ?: 0) - (bParts.getOrNull(i) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
