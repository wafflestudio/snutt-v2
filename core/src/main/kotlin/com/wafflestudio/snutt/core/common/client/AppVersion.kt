package com.wafflestudio.snutt.core.common.client

fun compareAppVersions(
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
