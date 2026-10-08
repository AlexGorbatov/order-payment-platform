package com.altronixsoft.opp.payment;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Enforces the layering rules of ADR-0003 and the external-call rules of ADR-0008. Every rule allows empty matches so the suite passes
 * while packages are still empty; rules start biting as soon as code lands in them.
 */
@AnalyzeClasses(packages = "com.altronixsoft.opp.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    /** Packages that must never leak into the domain. */
    private static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework..",
        "jakarta.persistence..",
        "org.hibernate..",
        "com.fasterxml.jackson..",
        "tools.jackson..",
        "org.apache.kafka..",
        "com.stripe.."
    };

    @ArchTest
    static final ArchRule domain_has_no_framework_dependencies = noClasses()
            .that()
            .resideInAPackage("..payment.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FRAMEWORK_PACKAGES)
            .because("the domain is plain Java: no Spring, JPA, Jackson, Kafka or Stripe")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_depends_on_domain_and_ports_only = classes()
            .that()
            .resideInAPackage("..payment.application..")
            .should()
            .onlyDependOnClassesThat(resideInAnyPackage(
                    "..payment.domain..",
                    "..payment.application..",
                    "java..",
                    "org.slf4j..",
                    "org.springframework.transaction..",
                    "org.springframework.stereotype.."))
            .because("use cases talk to the domain and to port interfaces; Spring transaction annotations are allowed")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule layers_depend_inward_only = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain")
            .definedBy("..payment.domain..")
            .layer("Application")
            .definedBy("..payment.application..")
            .layer("Adapters")
            .definedBy("..payment.adapter..")
            .layer("Config")
            .definedBy("..payment.config..")
            .whereLayer("Config")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapters")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapters", "Config")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other = slices().matching("..payment.adapter.(*).(*)..")
            .should()
            .notDependOnEachOther()
            .because("adapters communicate only through application ports")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule jpa_entities_live_in_persistence_adapter = classes()
            .that()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .or()
            .areAnnotatedWith("jakarta.persistence.MappedSuperclass")
            .or()
            .areAnnotatedWith("jakarta.persistence.Embeddable")
            .should()
            .resideInAPackage("..payment.adapter.out.persistence..")
            .because("domain objects are separate types; JPA is a persistence detail")
            .allowEmptyShould(true);

    /**
     * Static approximation of "no outbound network calls inside a DB transaction": transactional code must not touch
     * Stripe or Kafka types directly. Calls hidden behind a port are covered by design (architecture §7.6) and by
     * review. The only sanctioned exception, the outbox relay (ADR-0004), lives in platform-messaging-starter and is
     * therefore outside the scope of this service-level rule.
     */
    @ArchTest
    static final ArchRule transactional_methods_do_not_call_stripe_or_kafka = noMethods()
            .that()
            .areAnnotatedWith(TRANSACTIONAL)
            .or()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(TRANSACTIONAL)
            .should(accessStripeOrKafka())
            .because("no outbound network calls inside a DB transaction")
            .allowEmptyShould(true);

    private static ArchCondition<JavaMethod> accessStripeOrKafka() {
        DescribedPredicate<JavaClass> networkTypes =
                resideInAnyPackage("com.stripe..", "org.apache.kafka..", "org.springframework.kafka..");
        return new ArchCondition<>("access Stripe or Kafka types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getAccessesFromSelf().stream()
                        .filter(access -> networkTypes.test(access.getTargetOwner()))
                        .forEach(access -> events.add(SimpleConditionEvent.satisfied(access, access.getDescription())));
            }
        };
    }

    @ArchTest
    static final ArchRule kafka_consumers_never_call_stripe = noClasses()
            .that()
            .resideInAPackage("..payment.adapter.in.kafka..")
            .should()
            .dependOnClassesThat(resideInAnyPackage("com.stripe..", "..payment.adapter.out.stripe..")
                    .or(describe(
                            "is the PaymentGateway port", c -> c.getSimpleName().equals("PaymentGateway"))))
            .because("Kafka consumers only change DB state; Stripe calls run in DB-backed workers")
            .allowEmptyShould(true);
}
