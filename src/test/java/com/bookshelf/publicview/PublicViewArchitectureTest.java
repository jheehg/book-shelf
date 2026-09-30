package com.bookshelf.publicview;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 설계 문서 7-2의 규칙을 사람의 기억이 아니라 빌드로 지킨다 */
@AnalyzeClasses(packages = "com.bookshelf", importOptions = ImportOption.DoNotIncludeTests.class)
class PublicViewArchitectureTest {

    @ArchTest
    static final ArchRule 공개_조회는_JPA_엔티티를_쓰지_않는다 = noClasses()
            .that().resideInAPackage("..publicview..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .because("엔티티를 그대로 내보내면 한줄평이 딸려 나간다 (설계 7-3)");

    @ArchTest
    static final ArchRule 공개_조회는_인증된_조회_코드를_재사용하지_않는다 = noClasses()
            .that().resideInAPackage("..publicview..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..record.application..", "..record.infrastructure..",
                    "..member.application..", "..member.infrastructure..")
            .because("기존 조회에 공개 필터 하나 추가하다 조건을 빠뜨리는 것이 가장 흔한 사고 경로다 (설계 7-2)");

    @ArchTest
    static final ArchRule 공개_조회는_제공자를_직접_부르지_않는다 = noClasses()
            .that().resideInAPackage("..publicview..")
            .should().dependOnClassesThat().resideInAnyPackage("..catalog..", "..provider..")
            .because("공개 페이지는 어떤 제공자도 동기로 부르지 않는다 (설계 2-1). 판매 칸은 SaleService의 캐시만 본다");
}
