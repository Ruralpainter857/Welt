package org.pepsoft.worldpainter.util;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultTreeSelectionModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;

/** Checked branches form an antichain: descendants of checked branches need no separate entry. */
public final class CheckBoxTreeSelectionModel extends DefaultTreeSelectionModel implements TreeModelListener {
    public CheckBoxTreeSelectionModel(TreeModel model) {
        setSelectionMode(DISCONTIGUOUS_TREE_SELECTION);
        setModel(model);
    }

    public TreeModel getModel() { return model; }
    public void setModel(TreeModel value) {
        if (model == value) return;
        if (model != null) model.removeTreeModelListener(this);
        model = value;
        clearSelection();
        if (model != null) model.addTreeModelListener(this);
    }

    public boolean isPathSelected(TreePath path, boolean includeAncestors) {
        if (!includeAncestors) return super.isPathSelected(path);
        if (path == null || selection == null) return false;
        for (TreePath entry : selection) if (entry.isDescendant(path)) return true;
        return false;
    }

    public boolean isPartiallySelected(TreePath path) {
        if (path == null || selection == null || isPathSelected(path, true)) return false;
        for (TreePath entry : selection) if (path.isDescendant(entry)) return true;
        return false;
    }
    @Override public void setSelectionPaths(TreePath[] paths) {
        List<TreePath> selected = new ArrayList<>();
        if (paths != null) for (TreePath path : paths) add(selected, path);
        super.setSelectionPaths(selected.toArray(new TreePath[0]));
    }

    @Override public void addSelectionPaths(TreePath[] paths) {
        if (paths == null) return;
        List<TreePath> selected = entries();
        for (TreePath path : paths) add(selected, path);
        super.setSelectionPaths(selected.toArray(new TreePath[0]));
    }

    @Override public void removeSelectionPaths(TreePath[] paths) {
        if (paths == null) return;
        List<TreePath> selected = entries();
        for (TreePath path : paths) {
            // A partial branch is not itself checked; retain the historical removal no-op.
            if (!valid(path) || !covered(selected, path)) continue;
            List<TreePath> remaining = new ArrayList<>();
            for (TreePath entry : selected) {
                if (path.isDescendant(entry)) continue;
                if (entry.isDescendant(path)) split(remaining, entry, path);
                else remaining.add(entry);
            }
            selected = remaining;
        }
        super.setSelectionPaths(selected.toArray(new TreePath[0]));
    }

    private void add(List<TreePath> selected, TreePath path) {
        if (!valid(path) || covered(selected, path)) return;
        TreePath candidate = path;
        while (true) {
            TreePath current = candidate;
            selected.removeIf(current::isDescendant);
            selected.add(current);
            TreePath parent = current.getParentPath();
            if (parent == null) return;
            Object node = parent.getLastPathComponent();
            int count = model.getChildCount(node);
            boolean all = count > 0;
            for (int i = 0; i < count && all; i++) {
                all = covered(selected, parent.pathByAddingChild(model.getChild(node, i)));
            }
            if (!all) return;
            candidate = parent;
        }
    }

    private void split(List<TreePath> remaining, TreePath branch, TreePath removed) {
        // Split only the ancestor chain; untouched sibling subtrees remain single entries.
        TreePath current = branch;
        while (!current.equals(removed)) {
            Object node = current.getLastPathComponent();
            Object next = removed.getPathComponent(current.getPathCount());
            for (int i = 0; i < model.getChildCount(node); i++) {
                Object child = model.getChild(node, i);
                if (!child.equals(next)) remaining.add(current.pathByAddingChild(child));
            }
            current = current.pathByAddingChild(next);
        }
    }

    private boolean valid(TreePath path) {
        if (model == null || path == null || !path.getPathComponent(0).equals(model.getRoot())) return false;
        for (int i = 1; i < path.getPathCount(); i++) {
            if (model.getIndexOfChild(path.getPathComponent(i - 1), path.getPathComponent(i)) < 0) return false;
        }
        return true;
    }

    private static boolean covered(List<TreePath> selected, TreePath path) {
        if (path == null) return false;
        for (TreePath entry : selected) if (entry.isDescendant(path)) return true;
        return false;
    }

    private List<TreePath> entries() {
        TreePath[] paths = super.getSelectionPaths();
        return paths == null ? new ArrayList<>() : new ArrayList<>(List.of(paths));
    }

    private void prune() {
        List<TreePath> retained = entries();
        retained.removeIf(path -> !valid(path));
        setSelectionPaths(retained.toArray(new TreePath[0]));
    }
    @Override public void treeNodesChanged(TreeModelEvent event) { prune(); }
    @Override public void treeNodesInserted(TreeModelEvent event) { prune(); }
    @Override public void treeNodesRemoved(TreeModelEvent event) { prune(); }
    @Override public void treeStructureChanged(TreeModelEvent event) { prune(); }

    private TreeModel model;
    private static final long serialVersionUID = 1L;
}
