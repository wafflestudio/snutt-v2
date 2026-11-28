package com.wafflestudio.snutt.core.common.model

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.bson.types.ObjectId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

enum class V1IdTable(
    val table: String,
    val notFound: ErrorType,
) {
    USER("user", ErrorType.USER_NOT_FOUND),
    LECTURE("lecture", ErrorType.LECTURE_NOT_FOUND),
    TIMETABLE("timetable", ErrorType.TIMETABLE_NOT_FOUND),
    TIMETABLE_LECTURE("timetable_lecture", ErrorType.TIMETABLE_LECTURE_NOT_FOUND),
    THEME("timetable_theme", ErrorType.THEME_NOT_FOUND),
    PUBLICATION("published_theme", ErrorType.NOT_PUBLISHED_THEME),
    FRIEND("friend", ErrorType.FRIEND_NOT_FOUND),
    DIARY_QUESTION("diary_question", ErrorType.DIARY_QUESTION_NOT_FOUND),
    DIARY_SUBMISSION("diary_submission", ErrorType.DIARY_SUBMISSION_NOT_FOUND),
}

@Repository
class V1IdRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun id(
        type: V1IdTable,
        idV1: String,
        notFound: ErrorType = type.notFound,
    ): Long {
        if (!ObjectId.isValid(idV1)) throw SnuttException(notFound)
        return jdbc
            .queryForList(
                "SELECT id FROM `${type.table}` WHERE idv1 = :idv1",
                mapOf("idv1" to idV1.lowercase()),
                Long::class.java,
            ).singleOrNull() ?: throw SnuttException(notFound)
    }

    fun timetableLectureId(
        timetableIdV1: String,
        lectureIdV1: String,
    ): Long {
        if (!ObjectId.isValid(timetableIdV1) || !ObjectId.isValid(lectureIdV1)) {
            throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
        }
        return jdbc
            .queryForList(
                "SELECT tl.id FROM timetable_lecture tl JOIN timetable t ON t.id = tl.timetable_id " +
                    "WHERE t.idv1 = :timetable AND tl.idv1 = :lecture",
                mapOf("timetable" to timetableIdV1.lowercase(), "lecture" to lectureIdV1.lowercase()),
                Long::class.java,
            ).singleOrNull() ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
    }

    fun v1Ids(
        type: V1IdTable,
        ids: Collection<Long>,
    ): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return ids
            .distinct()
            .chunked(1000)
            .flatMap { chunk ->
                jdbc.query(
                    "SELECT id, idv1 FROM `${type.table}` WHERE id IN (:ids)",
                    mapOf("ids" to chunk),
                ) { rs, _ -> rs.getLong("id") to rs.getString("idv1") }
            }.toMap()
    }

    fun v1Id(
        type: V1IdTable,
        id: Long,
    ): String = v1Ids(type, listOf(id)).getValue(id)
}
