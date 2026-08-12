package com.wafflestudio.snutt.core.common.model

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@MappedSuperclass
abstract class BaseEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    val createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    val updatedAt: Instant? = null,
)
