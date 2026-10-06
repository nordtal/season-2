package eu.nordtal.season.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** The names of layers, which a package never carries as its last segment, since a package is named by its feature. */
final class LayerPackages {

    /** The forbidden last segments; {@code config} stays allowed. */
    static final List<String> NAMES = List.of("db", "dao", "listener", "service", "model", "impl", "api", "web");

    private LayerPackages() {}

    /** Returns the rule that no class sits in a package whose last segment is one of {@link #NAMES}. */
    static ArchRule rule() {
        return noClasses()
                .should()
                .resideInAnyPackage(NAMES.stream().map(name -> ".." + name).toArray(String[]::new))
                .because("a package is named by its feature, never by its layer");
    }
}
