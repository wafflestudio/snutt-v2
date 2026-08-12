package com.wafflestudio.snutt.core.domain.tag.repository

import com.wafflestudio.snutt.core.domain.tag.model.TagGroup
import org.springframework.data.jpa.repository.JpaRepository

interface TagGroupRepository : JpaRepository<TagGroup, Long> {
    fun findByName(name: String): TagGroup?

    fun findAllByNameNot(name: String): List<TagGroup>

    fun findAllByNameNotOrderByOrderingAsc(name: String): List<TagGroup>
}
