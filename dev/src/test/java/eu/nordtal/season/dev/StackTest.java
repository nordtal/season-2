package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StackTest {

    @Test
    void aJarIsInstalledAsRootThroughTheServersImageWithOnlyTheCapabilitiesTheCopyNeeds() {
        final List<String> arguments = Stack.installArguments("smp", Path.of("/checkout/smp/build/libs/smp-1.0.jar"));
        assertEquals(List.of("run", "--rm", "--no-deps", "-T", "--pull", "never"), arguments.subList(0, 6));
        assertEquals(List.of("--user", "0:0"), arguments.subList(6, 8));
        final List<String> capabilities = new ArrayList<>();
        for (int index = 0; index < arguments.size() - 1; index++) {
            if (arguments.get(index).equals("--cap-add")) {
                capabilities.add(arguments.get(index + 1));
            }
        }
        assertEquals(List.of("DAC_OVERRIDE", "CHOWN"), capabilities);
        assertTrue(arguments.contains("/checkout/smp/build/libs/smp-1.0.jar:/tmp/nordtal-install.jar:ro"));
        final int service = arguments.indexOf("smp");
        assertEquals(List.of("--entrypoint", "sh"), arguments.subList(service - 2, service));
        assertEquals(List.of("sh", "smp-1.0.jar", "smp"), arguments.subList(arguments.size() - 3, arguments.size()));
    }
}
