package com.wafflestudio.snutt.core.domain.tag.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.tag.model.TagList
import com.wafflestudio.snutt.core.domain.tag.repository.TagListRepository
import org.springframework.stereotype.Service

@Service
class TagListService(
    private val tagListRepository: TagListRepository,
) {
    fun getTagList(
        year: Int,
        semester: Semester,
    ): TagList =
        tagListRepository.findByYearAndSemester(year, semester)
            ?: throw SnuttException(ErrorType.TAG_LIST_NOT_FOUND)
}
