package org.pepsoft.worldpainter.util;

import org.junit.Test;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CheckBoxTreeTest {
    private static DefaultTreeModel fixture() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        for (int i = 0; i < 3; i++) {
            DefaultMutableTreeNode branch = new DefaultMutableTreeNode("branch" + i);
            root.add(branch);
            for (int j = 0; j < 4; j++) branch.add(new DefaultMutableTreeNode("leaf" + i + j));
        }
        return new DefaultTreeModel(root);
    }
    private static List<TreePath> paths(DefaultTreeModel model) {
        List<TreePath> result = new ArrayList<>();
        var nodes = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) result.add(new TreePath(((DefaultMutableTreeNode) nodes.nextElement()).getPath()));
        return result;
    }

    @Test public void preservesJideCheckedCoverageAndPartialBranches() {
        DefaultTreeModel tree = fixture();
        var reference = new com.jidesoft.swing.CheckBoxTree(tree).getCheckBoxTreeSelectionModel();
        var model = new CheckBoxTreeSelectionModel(tree);
        List<TreePath> paths = paths(tree);
        Random random = new Random(8456);
        for (int i = 0; i < 2000; i++) {
            TreePath path = paths.get(random.nextInt(paths.size()));
            int operation = random.nextInt(4);
            switch (operation) {
                case 0 -> { reference.addSelectionPath(path); model.addSelectionPath(path); }
                case 1 -> { reference.removeSelectionPath(path); model.removeSelectionPath(path); }
                case 2 -> { reference.setSelectionPaths(new TreePath[] {path}); model.setSelectionPaths(new TreePath[] {path}); }
                case 3 -> { reference.clearSelection(); model.clearSelection(); }
            }
            for (TreePath query : paths) {
                assertEquals("coverage after " + i + " at " + query, reference.isPathSelected(query, true), model.isPathSelected(query, true));
                assertEquals("partial after " + i + " operation=" + operation + " path=" + path + " old=" + java.util.Arrays.toString(reference.getSelectionPaths()) + " new=" + java.util.Arrays.toString(model.getSelectionPaths()) + " at " + query, reference.isPartiallySelected(query), model.isPartiallySelected(query));
            }
            TreePath[] selected = model.getSelectionPaths();
            if (selected != null) for (TreePath first : selected) for (TreePath second : selected) {
                if (!first.equals(second)) assertFalse("Redundant descendant", first.isDescendant(second));
            }
        }
    }

    @Test public void splitsBranchesAndTracksModelChanges() {
        DefaultTreeModel tree = fixture();
        CheckBoxTreeSelectionModel model = new CheckBoxTreeSelectionModel(tree);
        List<TreePath> paths = paths(tree);
        TreePath root = paths.get(0), leaf = paths.get(3);
        model.addSelectionPath(root);
        model.removeSelectionPath(leaf);
        assertTrue(model.isPartiallySelected(root));
        int count = model.getSelectionCount(); model.removeSelectionPath(root);
        assertEquals(count, model.getSelectionCount());
        assertFalse(model.isPathSelected(leaf, true));
        assertTrue(model.isPathSelected(paths.get(2), true));
        model.addSelectionPath(leaf);
        assertEquals(1, model.getSelectionCount()); assertTrue(model.isPathSelected(root));
        model.clearSelection(); model.addSelectionPath(leaf);
        tree.removeNodeFromParent((DefaultMutableTreeNode) leaf.getLastPathComponent());
        assertEquals(0, model.getSelectionCount());
        model.setModel(fixture()); assertEquals(0, model.getSelectionCount());
        model.addSelectionPath(root); assertEquals(0, model.getSelectionCount());
    }

    @Test public void handlesCheckboxMouseKeyboardAndLookAndFeelUpdates() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                CheckBoxTree tree = new CheckBoxTree(fixture());
                for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
                tree.setSize(400, 400);
                TreePath root = tree.getPathForRow(0), leaf = tree.getPathForRow(2);
                Rectangle bounds = tree.getPathBounds(root);
                tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, 1, 0, bounds.x + 2, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1));
                assertTrue(tree.getCheckBoxTreeSelectionModel().isPathSelected(root));
                tree.setSelectionPath(leaf);
                tree.getActionMap().get("toggle-check").actionPerformed(new java.awt.event.ActionEvent(tree, 0, ""));
                assertFalse(tree.getCheckBoxTreeSelectionModel().isPathSelected(leaf, true));
                assertTrue(tree.getCheckBoxTreeSelectionModel().isPartiallySelected(root));
                tree.updateUI();
                assertNotNull(tree.getActionMap().get("toggle-check"));
                assertTrue(tree.getCheckBoxTreeSelectionModel().isPartiallySelected(root));
                tree.setEnabled(false);
                tree.getActionMap().get("toggle-check").actionPerformed(new java.awt.event.ActionEvent(tree, 0, ""));
                assertFalse(tree.getCheckBoxTreeSelectionModel().isPathSelected(leaf, true));
                Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(tree, root.getLastPathComponent(), false, true, false, 0, false);
                assertTrue(rendered.getPreferredSize().width > 10);
                tree.setModel(fixture()); assertEquals(0, tree.getCheckBoxTreeSelectionModel().getSelectionCount());
            } catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
