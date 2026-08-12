package com.wafflestudio.snutt.core.domain.building.model

// 수업 장소 문자열("302-101", "#43동" 등)에서 캠퍼스와 건물 번호를 해석한다.
// v1 PlaceInfo.kt의 로직을 그대로 이식 (../snutt/core/src/main/kotlin/lecturebuildings/data/PlaceInfo.kt)
data class PlaceInfo(
    val campus: Campus,
    val buildingNumber: String,
) {
    companion object {
        fun getValuesOf(places: String): List<PlaceInfo> = places.split("/").mapNotNull { of(it) }

        fun of(place: String): PlaceInfo? =
            runCatching {
                val campus: Campus =
                    when (place.first()) {
                        '#' -> Campus.YEONGEON
                        '*' -> Campus.PYEONGCHANG
                        else -> Campus.GWANAK
                    }

                val placeWithoutCampus = place.removePrefix("#").removePrefix("*")
                val splits = placeWithoutCampus.split("-").filter { !it.matches("^[A-Za-z]*$".toRegex()) }

                val buildingNumber =
                    when (splits.count()) {
                        3 -> if (splits[1].count() == 1) splits.dropLast(1).joinToString("-") else splits.first()
                        else -> splits.firstOrNull()
                    }?.let {
                        it.trimStart { firstChar -> firstChar == '0' }
                    } ?: return null

                PlaceInfo(campus, buildingNumber)
            }.getOrNull()
    }
}
