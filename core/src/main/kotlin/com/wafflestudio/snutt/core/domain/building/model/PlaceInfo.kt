package com.wafflestudio.snutt.core.domain.building.model

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
