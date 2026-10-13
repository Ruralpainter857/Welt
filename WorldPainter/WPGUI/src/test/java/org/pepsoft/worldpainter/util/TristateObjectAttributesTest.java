package org.pepsoft.worldpainter.util;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.pepsoft.worldpainter.App;
import org.pepsoft.worldpainter.WorldFactory;
import org.pepsoft.worldpainter.WPContext;
import org.pepsoft.worldpainter.plugins.WPPluginManager;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.layers.bo2.EditObjectAttributes;
import org.pepsoft.worldpainter.objects.GenericObject;
import org.pepsoft.worldpainter.objects.WPObject;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static org.pepsoft.worldpainter.objects.WPObject.*;

/** Checks the actual multi-object dialog without showing or saving a world. */
public class TristateObjectAttributesTest {
    private static final class Dialog extends EditObjectAttributes {
        Dialog(List<WPObject> objects) { super(null, objects, ColourScheme.DEFAULT); }
        void accept() { super.ok(); }
        TristateCheckBox checkbox(String name) {
            try {
                Field field = EditObjectAttributes.class.getDeclaredField(name);
                field.setAccessible(true);
                return (TristateCheckBox) field.get(this);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }

    private static void onEdt(Runnable check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        // Require the same isolated APPDATA gate as the real-application integration test.
        assumeNotNull(System.getProperty("welt.docking.configRoot"));
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static App application;
    private static Configuration previous;

    @BeforeClass public static void startIsolatedApplication() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String root = System.getProperty("welt.docking.configRoot");
        assumeNotNull(root);
        assertTrue(Configuration.getConfigDir().toPath().toAbsolutePath().normalize()
                .startsWith(new java.io.File(root).toPath().toAbsolutePath().normalize()));
        previous = Configuration.getInstance();
        Configuration config = new Configuration();
        config.setDefaultWidth(2); config.setDefaultHeight(2); config.setAutosaveEnabled(false);
        Configuration.setInstance(config);
        WPPluginManager.initialise(config.getUuid(), WPContext.INSTANCE);
        var world = WorldFactory.createDefaultWorld(config, 1234L);
        onEdt(() -> { application = App.getInstance(); application.setWorld(world, true); });
    }

    @AfterClass public static void stopIsolatedApplication() throws Exception {
        if (application != null) {
            SwingUtilities.invokeAndWait(() -> {
                application.clearWorld(); application.getDockingManager().close(); application.dispose();
            });
            SwingUtilities.invokeAndWait(() -> { });
            Configuration.setInstance(previous);
        }
    }
    private static GenericObject object(String name, boolean foundation) {
        GenericObject object = new GenericObject(name, 1, 1, 1, new Material[] {Material.STONE});
        object.setAttribute(ATTRIBUTE_NEEDS_FOUNDATION, foundation);
        return object;
    }

    @Test public void mixedSelectionPreservesIndividualValuesUntilChanged() throws Exception {
        onEdt(() -> {
            GenericObject first = object("First", true), second = object("Second", false);
            Dialog dialog = new Dialog(List.of(first, second));
            try {
                assertTrue(dialog.checkbox("checkBoxOnAir").isMixed());
                dialog.accept();
                assertTrue(first.getAttribute(ATTRIBUTE_NEEDS_FOUNDATION));
                assertFalse(second.getAttribute(ATTRIBUTE_NEEDS_FOUNDATION));
            } finally { dialog.dispose(); }
            for (boolean allowAir : new boolean[] {true, false}) {
                dialog = new Dialog(List.of(first, second));
                try {
                    dialog.checkbox("checkBoxOnAir").setSelected(allowAir);
                    assertFalse(dialog.checkbox("checkBoxOnAir").isMixed());
                    dialog.accept();
                    assertEquals(!allowAir, first.getAttribute(ATTRIBUTE_NEEDS_FOUNDATION));
                    assertEquals(!allowAir, second.getAttribute(ATTRIBUTE_NEEDS_FOUNDATION));
                } finally { dialog.dispose(); }
            }
        });
    }

    @Test public void singleSelectionRetainsTwoStateEditing() throws Exception {
        onEdt(() -> {
            GenericObject object = object("Single", true);
            Dialog dialog = new Dialog(List.of(object));
            try {
                TristateCheckBox box = dialog.checkbox("checkBoxOnAir");
                assertFalse(box.isTristateMode());
                box.doClick(0); assertTrue(box.isSelected()); assertFalse(box.isMixed());
                dialog.accept(); assertFalse(object.getAttribute(ATTRIBUTE_NEEDS_FOUNDATION));
            } finally { dialog.dispose(); }
        });
    }
}
