package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.enums.Semester

data class YearSemesterArgs(
    val year: Int?,
    val semester: Semester?,
)

interface BatchJob {
    val name: String

    fun run(args: YearSemesterArgs)
}
