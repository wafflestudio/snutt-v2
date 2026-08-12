package com.wafflestudio.snutt.core.domain.tag.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.tag.model.Tag
import com.wafflestudio.snutt.core.domain.tag.model.TagGroup
import com.wafflestudio.snutt.core.domain.tag.repository.TagGroupRepository
import com.wafflestudio.snutt.core.domain.tag.repository.TagRepository
import org.springframework.stereotype.Service

data class TagGroupDisplay(
    val id: Long,
    val name: String,
    val ordering: Int,
    val color: String?,
    val tags: List<TagDisplay>,
)

data class TagDisplay(
    val id: Long,
    val name: String,
    val description: String?,
    val ordering: Int,
)

@Service
class TagService(
    private val tagGroupRepository: TagGroupRepository,
    private val tagRepository: TagRepository,
) {
    fun getMainTags(): TagGroupDisplay = getTagGroup("main") ?: throw SnuttException(ErrorType.TAG_GROUP_NOT_FOUND)

    fun getSearchTags(): List<TagGroupDisplay> =
        tagGroupRepository
            .findAllByNameNotOrderByOrderingAsc("main")
            .map { it.toDisplay() }

    fun getTag(tagId: Long): Tag = tagRepository.findById(tagId).orElse(null) ?: throw SnuttException(ErrorType.TAG_NOT_FOUND)

    private fun getTagGroup(name: String): TagGroupDisplay? = tagGroupRepository.findByName(name)?.toDisplay()

    private fun TagGroup.toDisplay() =
        TagGroupDisplay(
            id = id!!,
            name = name,
            ordering = ordering,
            color = color,
            tags = tagRepository.findByTagGroupIdOrderByOrderingAsc(id!!).map { it.toDisplay() },
        )

    private fun Tag.toDisplay() =
        TagDisplay(
            id = id!!,
            name = name,
            description = description,
            ordering = ordering,
        )
}
