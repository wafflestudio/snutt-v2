package com.wafflestudio.snutt.parity

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/**
 * parity 테스트가 띄우는 v2 api. 운영 `SnuttApplication`과 같은 패키지를 스캔하되, 같은 classpath에 있는
 * migration 클래스(이관 도구, MigrationRunner가 ApplicationRunner)와 다른 앱의 @SpringBootApplication, 이 하네스 자체를 뺀다.
 * JPA 엔티티·리포지토리 스캔 기준도 운영과 같은 `com.wafflestudio.snutt`로 둔다(기본값은 이 클래스의 패키지).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.wafflestudio.snutt"])
@ComponentScan(
    basePackages = ["com.wafflestudio.snutt"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["""com\.wafflestudio\.snutt\.(Snutt\w*Application|migration\..*|parity\..*)"""],
        ),
    ],
)
class ParityV2Application
