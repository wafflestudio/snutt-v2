package com.wafflestudio.snutt.core.config

import org.hibernate.boot.model.FunctionContributions
import org.hibernate.boot.model.FunctionContributor

class RegexpFunctionContributor : FunctionContributor {
    override fun contributeFunctions(functionContributions: FunctionContributions) {
        val booleanType = functionContributions.typeConfiguration.getBasicTypeForJavaType(Boolean::class.java)
        // MySQL 8은 REGEXP(expr, pat) 함수 형태가 없어 연산자 형태로 렌더링한다
        functionContributions.functionRegistry.registerPattern("regexp", "?1 regexp ?2", booleanType)
        // v1 isEqualTo는 대소문자를 구분한다. MySQL 기본 collation은 _ci이므로 binary 비교로 재현
        functionContributions.functionRegistry.registerPattern("bineq", "binary ?1 = binary ?2", booleanType)
    }
}
