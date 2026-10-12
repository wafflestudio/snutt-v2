package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.model.YearAndSemester

interface BatchJob {
    val name: String

    fun run(target: YearAndSemester?)
}
