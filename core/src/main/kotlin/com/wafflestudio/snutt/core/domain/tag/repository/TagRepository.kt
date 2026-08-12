package com.wafflestudio.snutt.core.domain.tag.repository

import com.wafflestudio.snutt.core.domain.tag.model.Tag
import org.springframework.data.jpa.repository.JpaRepository

interface TagRepository : JpaRepository<Tag, Long> {
    fun findByTagGroupIdOrderByOrderingAsc(tagGroupId: Long): List<Tag>

    fun findAllByTagGroupIdInOrderByOrderingAsc(tagGroupIds: Collection<Long>): List<Tag>
}
