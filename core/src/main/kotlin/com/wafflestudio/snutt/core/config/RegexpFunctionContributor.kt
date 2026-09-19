package com.wafflestudio.snutt.core.config

import org.hibernate.boot.model.FunctionContributions
import org.hibernate.boot.model.FunctionContributor

class RegexpFunctionContributor : FunctionContributor {
    override fun contributeFunctions(functionContributions: FunctionContributions) {
        val booleanType = functionContributions.typeConfiguration.getBasicTypeForJavaType(Boolean::class.java)
        functionContributions.functionRegistry.registerPattern("regexp", "?1 regexp ?2", booleanType)
        functionContributions.functionRegistry.registerPattern("bineq", "binary ?1 = binary ?2", booleanType)
    }
}
