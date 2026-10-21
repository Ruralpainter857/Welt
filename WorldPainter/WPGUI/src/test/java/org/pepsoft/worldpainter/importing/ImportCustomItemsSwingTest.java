package org.pepsoft.worldpainter.importing;

import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.util.CheckBoxTree;
import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

public class ImportCustomItemsSwingTest {
    @Test public void importsOnlyCheckedTerrainAndUpdatesConfirmation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String root = System.getProperty("welt.docking.configRoot");
        assumeNotNull(root);
        assertTrue(Configuration.getConfigDir().toPath().toAbsolutePath().normalize()
                .startsWith(new java.io.File(root).toPath().toAbsolutePath().normalize()));
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Configuration previous = Configuration.getInstance();
            ImportCustomItemsDialog dialog = null;
            try {
                Configuration.setInstance(new Configuration());
                World2 world = new World2(DefaultPlugin.JAVA_ANVIL, 0, 256);
                world.setName("Import fixture");
                MixedMaterial first = new MixedMaterial("Stone", new MixedMaterial.Row(Material.STONE, 1, 1), -1, null);
                MixedMaterial second = new MixedMaterial("Dirt", new MixedMaterial.Row(Material.DIRT, 1, 1), -1, null);
                world.setMixedMaterial(0, first); world.setMixedMaterial(2, second);
                dialog = new ImportCustomItemsDialog(null, world, ColourScheme.DEFAULT, CustomItemsTreeModel.ItemType.TERRAIN);
                assertEquals(List.of(first, second), dialog.getSelectedItems());
                Field field = ImportCustomItemsDialog.class.getDeclaredField("treeCustomItems");
                field.setAccessible(true);
                CheckBoxTree tree = (CheckBoxTree) field.get(dialog);
                TreePath rootPath = new TreePath(tree.getModel().getRoot());
                Object branch = tree.getModel().getChild(tree.getModel().getRoot(), 0);
                TreePath firstPath = rootPath.pathByAddingChild(branch).pathByAddingChild(first);
                tree.getCheckBoxTreeSelectionModel().removeSelectionPath(firstPath);
                assertEquals(List.of(second), dialog.getSelectedItems());
                tree.getCheckBoxTreeSelectionModel().clearSelection();
                assertTrue(dialog.getSelectedItems().isEmpty());
                Field button = ImportCustomItemsDialog.class.getDeclaredField("buttonOK");
                button.setAccessible(true); assertFalse(((JButton) button.get(dialog)).isEnabled());
                tree.getCheckBoxTreeSelectionModel().addSelectionPath(rootPath);
                assertEquals(List.of(first, second), dialog.getSelectedItems());
                assertTrue(((JButton) button.get(dialog)).isEnabled());
                tree.updateUI(); assertEquals(List.of(first, second), dialog.getSelectedItems());
            } catch (Throwable e) { error.set(e); }
            finally { if (dialog != null) dialog.dispose(); Configuration.setInstance(previous); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
