package com.inventoryplatform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.inventoryplatform.common.archunit.ArchitectureRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Every service carries this (BUILD_PROMPT.md §4). The rules live in platform-common so services
 * cannot drift into their own reading of "pure domain"; their self-test proves they genuinely fail
 * on violations rather than passing because they match nothing.
 *
 * <p><strong>allowEmptyShould is set deliberately and temporarily.</strong> This service is a Phase
 * 0 skeleton with no domain package yet, and ArchUnit rightly refuses to pass a rule that checked
 * nothing. Rather than delete the rules and forget to restore them, they are relaxed here — locally
 * and visibly, never in the shared rules — and {@link #domainPackageIsStillEmpty()} below fails the
 * moment a domain class appears, forcing the relaxation to be removed at that point.
 */
@AnalyzeClasses(packages = "com.inventoryplatform.catalog")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            ArchitectureRules.domainIsFrameworkFree().allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainUsesMoneyTypes =
            ArchitectureRules.domainUsesMoneyTypes().allowEmptyShould(true);

    /** Applies already: the api package exists in Phase 0. */
    @ArchTest
    static final ArchRule apiDoesNotReachIntoPersistence =
            ArchitectureRules.apiDoesNotReachIntoPersistence();

    @Test
    @DisplayName("tripwire: remove allowEmptyShould above once this service has a domain")
    void domainPackageIsStillEmpty() {
        var domainClasses =
                new ClassFileImporter().importPackages("com.inventoryplatform.catalog.domain");

        assertThat(domainClasses)
                .as(
                        "A domain package now exists, so the domain rules are no longer vacuous. "
                                + "Delete the allowEmptyShould(true) calls in this class — they were only "
                                + "ever a Phase 0 concession.")
                .isEmpty();
    }
}
