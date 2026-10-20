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
    @Test public void schematicDialogUsesNativeClassificationAndKeepsMaterialChoices() throws Exception {
        String gen=System.getProperty("wp.native.gen"), snapshots=System.getProperty("welt.native.objectSnapshots");
        try {
            System.setProperty("wp.native.gen","true");System.setProperty("welt.native.objectSnapshots","true");
            boolean available=org.pepsoft.worldpainter.nativeapi.NativeSlices.collectLegacyMaterialStates(
                    new byte[]{1},new byte[]{0},null,new int[]{1,1,1})!=null;
            available=available && org.pepsoft.worldpainter.nativeapi.NativeSlices.collectIndexedMaterialStates(null,new int[]{0},0,1,new int[]{1,1,1})!=null;
            if(Boolean.getBoolean("welt.test.requireLegacySnapshots") || Boolean.getBoolean("welt.test.requireIndexedClassification"))assertTrue("Native classifier required",available);
            else assumeTrue("Native classifier unavailable",available);
            onEdt(()->{
                java.util.Map<String,org.jnbt.Tag> tags=new java.util.HashMap<>();
                tags.put("Materials",new org.jnbt.StringTag("Materials","Alpha"));
                tags.put("Width",new org.jnbt.ShortTag("Width",(short)4));
                tags.put("Length",new org.jnbt.ShortTag("Length",(short)1));
                tags.put("Height",new org.jnbt.ShortTag("Height",(short)1));
                tags.put("Blocks",new org.jnbt.ByteArrayTag("Blocks",new byte[]{1,53,54,1}));
                tags.put("Data",new org.jnbt.ByteArrayTag("Data",new byte[]{0,1,2,0}));
                var legacySource=new org.pepsoft.worldpainter.layers.bo2.Schematic("Native classification",new org.jnbt.CompoundTag("Schematic",tags),null);
                WPObject modern;
                try { modern=org.pepsoft.worldpainter.layers.bo2.Schem.load(new java.io.ByteArrayInputStream(
                        org.pepsoft.worldpainter.layers.bo2.SchemLoadBenchmark.fixture(4,3)),"Native modern classification"); }
                catch(java.io.IOException error){throw new AssertionError(error);}
                for(WPObject source:List.of(legacySource,modern)) {
                java.util.SortedSet<Material> expected=new java.util.TreeSet<>(java.util.Comparator.comparing(Material::toString));
                source.visitBlocks((object,x,y,z,material)->{if(material!=Material.AIR)expected.add(material);return true;});
                Dialog dialog=null;
                try {
                    var counter=org.pepsoft.worldpainter.objects.CompactMaterialVolume.class.getDeclaredMethod(source instanceof org.pepsoft.worldpainter.layers.bo2.Schem?"completedIndexedClassifications":"completedLegacyClassifications");
                    counter.setAccessible(true);long before=(Long)counter.invoke(null);
                    dialog=new Dialog(List.of(source));assertEquals(before+1,(long)(Long)counter.invoke(null));
                    Field field=EditObjectAttributes.class.getDeclaredField("comboBoxReplacedMaterial");field.setAccessible(true);
                    ComboBoxModel<?> model=((JComboBox<?>)field.get(dialog)).getModel();assertEquals(expected.size(),model.getSize());
                    int index=0;for(Material material:expected)assertSame(material,model.getElementAt(index++));
                } catch(ReflectiveOperationException error){throw new AssertionError(error);}
                finally {if(dialog!=null)dialog.dispose();}
                }
            });
        } finally {
            if(gen==null)System.clearProperty("wp.native.gen");else System.setProperty("wp.native.gen",gen);
            if(snapshots==null)System.clearProperty("welt.native.objectSnapshots");else System.setProperty("welt.native.objectSnapshots",snapshots);
        }
    }
}
