package com.aea.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundaries from docs/architecture/03-repository-structure.md, enforced
 * by the build rather than by review discipline.
 *
 * The rule that matters most is symbolicEngineStaysDeterministic: it is what
 * turns "our policy engine is deterministic and LLM-independent" from a claim
 * in a sales deck into a property the compiler checks.
 *
 * allowEmptyShould is set because these packages arrive over M1.3 and V1.
 * The rules are inert until then and begin enforcing the moment the packages
 * appear -- which is the point of writing them now rather than later.
 */
@AnalyzeClasses(packages = "com.aea", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String ACCESS_CONSTRAINT = "com.aea.pdp.AccessConstraint";

    @ArchTest
    static final ArchRule symbolicEngineStaysDeterministic =
            noClasses().that().resideInAnyPackage("com.aea.reasoning.rules..",
                                                  "com.aea.reasoning.inference..")
                       .should().dependOnClassesThat()
                       .resideInAnyPackage("com.aea.reasoning.model..",
                                           "com.aea.knowledge.retrieval..")
                       .because("the rule engine must be provably independent of any LLM "
                                + "or retrieval component, so it can be tested exhaustively "
                                + "and audited by a customer's compliance team")
                       .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotReachIntoPersistence =
            noClasses().that().resideInAPackage("com.aea.api..")
                       .should().dependOnClassesThat().resideInAPackage("com.aea.persistence..")
                       .because("controllers must go through a service boundary, so tenant "
                                + "scoping cannot be bypassed at the edge")
                       .allowEmptyShould(true);

    /**
     * Using an AccessConstraint is fine and expected -- retrieval and the SQL
     * path both consume one. Constructing your own is not: a component that can
     * mint an AccessConstraint can grant itself any access it likes.
     */
    @ArchTest
    static final ArchRule accessConstraintsOriginateOnlyInThePdp =
            noClasses().that().resideOutsideOfPackage("com.aea.pdp..")
                       .should().callConstructorWhere(constructorOf(ACCESS_CONSTRAINT))
                       .because("only the Policy Decision Point may decide what access exists")
                       .allowEmptyShould(true);

    private static DescribedPredicate<JavaConstructorCall> constructorOf(String fqcn) {
        return DescribedPredicate.describe(
                "a constructor of " + fqcn,
                call -> call.getTargetOwner().getName().equals(fqcn));
    }
}
