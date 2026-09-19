package com.wafflestudio.snutt.core.common.json

import tools.jackson.databind.json.JsonMapper

object Json {
    val mapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()
}
