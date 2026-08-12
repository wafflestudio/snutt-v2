package com.wafflestudio.snutt.core.common.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExternalIdGeneratorTest :
    StringSpec({
        "24자리 소문자 hex를 생성한다" {
            val id = ExternalIdGenerator.generate()
            id.length shouldBe 24
            id.matches(Regex("^[0-9a-f]{24}$")) shouldBe true
        }

        "연속 생성 시 중복이 없다" {
            val ids = (1..10_000).map { ExternalIdGenerator.generate() }
            ids.toSet().size shouldBe ids.size
        }
    })
