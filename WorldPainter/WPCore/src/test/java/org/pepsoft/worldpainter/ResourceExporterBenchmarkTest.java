package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assume.assumeTrue;

/** Opt-in whole-world benchmark: run with -Dwelt.resource.benchmark=true. */
public final class ResourceExporterBenchmarkTest {
    @Test
    public void measureNativeWholeWorldExports() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.resource.benchmark"));
        final String oldUserDir = System.getProperty("user.dir");
        final String oldUserHome = System.getProperty("user.home");
        final String oldFlag = System.getProperty(Native.EXPORT_KEY);
        final String oldResourcesFlag = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        final Path root = Files.createTempDirectory("welt-resource-export-bench-");
        final File world = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
        try {
            System.setProperty("user.dir", root.resolve("native").toString());
            System.setProperty("user.home", root.resolve("native-home").toString());
            Files.createDirectories(root.resolve("native"));
            Files.createDirectories(root.resolve("native-home"));
            Native.setExportEnabled(true);
            System.setProperty(Native.RESOURCES_EXPORT_KEY, "true");
            System.out.println("Resource export native campaign; work root=" + root.resolve("native"));
            ExportPerformanceTester.main(new String[]{world.getAbsolutePath()});
        } finally {
            restore("user.dir", oldUserDir);
            restore("user.home", oldUserHome);
            restore(Native.EXPORT_KEY, oldFlag);
            restore(Native.RESOURCES_EXPORT_KEY, oldResourcesFlag);
        }
    }

    private static void restore(final String key, final String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
