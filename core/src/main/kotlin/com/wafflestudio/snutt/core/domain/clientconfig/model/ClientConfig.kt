package com.wafflestudio.snutt.core.domain.clientconfig.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "client_config")
class ClientConfig(
    var name: String,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var value: String,
    var minIosVersion: String? = null,
    var maxIosVersion: String? = null,
    var minAndroidVersion: String? = null,
    var maxAndroidVersion: String? = null,
) : BaseEntity() {
    fun isAdaptable(
        osType: String,
        appVersion: String,
    ): Boolean {
        val (minVersion, maxVersion) =
            when (osType) {
                "ios" -> minIosVersion to maxIosVersion
                "android" -> minAndroidVersion to maxAndroidVersion
                else -> return false
            }
        return (minVersion == null || compareVersions(appVersion, minVersion) >= 0) &&
            (maxVersion == null || compareVersions(appVersion, maxVersion) <= 0)
    }

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
