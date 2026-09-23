package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(
            CurrentUserId::class.java,
            CurrentClient::class.java,
            V1CurrentUser::class.java,
        )
    }
}
