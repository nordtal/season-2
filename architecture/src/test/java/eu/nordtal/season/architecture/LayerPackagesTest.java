package eu.nordtal.season.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Shows the layer rule fail on a package named by a layer and pass on one named by a feature. */
class LayerPackagesTest {

    private static final List<String> LAYERS =
            List.of("db", "dao", "listener", "service", "model", "impl", "api", "web");

    private static JavaClasses fixtures(final String name) {
        return new ClassFileImporter().importPackages("eu.nordtal.season.architecture.fixture." + name);
    }

    @Test
    void aPackageNamedByALayerIsRefused() {
        assertThrows(AssertionError.class, () -> LayerPackages.rule().check(fixtures("layered")));
    }

    @Test
    void aPackageNamedByAFeatureIsAccepted() {
        assertDoesNotThrow(() -> LayerPackages.rule().check(fixtures("feature")));
    }

    @Test
    void everyLayerNameIsListed() {
        assertTrue(LayerPackages.NAMES.containsAll(LAYERS) && LAYERS.containsAll(LayerPackages.NAMES));
    }

    @Test
    void onlyTheLastSegmentCounts() {
        for (final String name : LAYERS) {
            assertTrue(PackageMatcher.of(".." + name).matches("eu.nordtal.season.x." + name), name);
            assertFalse(PackageMatcher.of(".." + name).matches("eu.nordtal.season." + name + ".x"), name);
        }
        assertFalse(PackageMatcher.of("..db").matches("eu.nordtal.season.config"));
    }
}
