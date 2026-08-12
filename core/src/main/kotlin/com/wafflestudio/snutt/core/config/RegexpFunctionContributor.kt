package com.wafflestudio.snutt.core.config

import org.hibernate.boot.model.FunctionContributions
import org.hibernate.boot.model.FunctionContributor

// MySQL 전용 함수를 boolean 반환으로 등록한다. QueryDSL 템플릿이 predicate 컨텍스트에서
// 쓰므로 타입 등록이 필요하다 (PLAN.md §7 M2).
class RegexpFunctionContributor : FunctionContributor {
    override fun contributeFunctions(functionContributions: FunctionContributions) {
        val booleanType = functionContributions.typeConfiguration.getBasicTypeForJavaType(Boolean::class.java)
        // MySQL 8은 REGEXP(expr, pat) 함수 형태가 없어 연산자 형태로 렌더링한다
        functionContributions.functionRegistry.registerPattern("regexp", "?1 regexp ?2", booleanType)
        // v1 isEqualTo는 대소문자를 구분한다. MySQL 기본 collation은 _ci이므로 binary 비교로 재현
        functionContributions.functionRegistry.registerPattern("bineq", "binary ?1 = binary ?2", booleanType)
    }
}
