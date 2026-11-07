package com.jcode.ui.explorer;

import com.jcode.service.ExternalOpener;
import com.jcode.service.SakuraLauncher;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class FileTreePanel extends JPanel {

    private static final Set<String> HIDDEN = Set.of(
            ".git", "node_modules", "target", ".idea", "out", "dist"
    );

    private final JTree tree = new JTree();
    private Path root;
    private Path pendingReveal;
    private Consumer<Path> onOpenFile = p -> {
    };

    public FileTreePanel() {
        super(new BorderLayout());
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(
                    JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus
            ) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode node
                        && node.getUserObject() instanceof PathNode pn) {
                    setText(pn.label());
                }
                return this;
            }
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    Path file = selectedFile();
                    if (file != null && Files.isRegularFile(file)) {
                        onOpenFile.accept(file);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    tree.setSelectionPath(path);
                }
                Path selected = selectedFile();
                if (selected == null) {
                    return;
                }
                showContextMenu(e.getX(), e.getY(), selected);
            }
        });

        add(new JScrollPane(tree), BorderLayout.CENTER);
        setEmpty();
    }

    private void showContextMenu(int x, int y, Path selected) {
        JPopupMenu menu = new JPopupMenu();
        boolean isFile = Files.isRegularFile(selected);

        if (isFile) {
            JMenuItem open = new JMenuItem("開く");
            open.addActionListener(e -> onOpenFile.accept(selected));
            menu.add(open);
        }

        JMenuItem external = new JMenuItem(isFile ? "外部エディタで開く" : "エクスプローラーで開く");
        external.addActionListener(e -> openExternally(selected));
        menu.add(external);

        if (isFile) {
            JMenuItem sakura = new JMenuItem("サクラエディタで開く");
            sakura.addActionListener(e -> openInSakura(selected));
            menu.add(sakura);
        }

        menu.show(tree, x, y);
    }

    private void openExternally(Path path) {
        try {
            ExternalOpener.open(path);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "外部アプリで開けませんでした:\n" + ex.getMessage(),
                    "外部エディタで開く", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInSakura(Path path) {
        try {
            SakuraLauncher.open(path);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "サクラエディタで開く", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void setOnOpenFile(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile;
    }

    public void setRoot(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        refresh();
    }

    public Path getRoot() {
        return root;
    }

    public void refresh() {
        if (root == null || !Files.isDirectory(root)) {
            setEmpty();
            return;
        }
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(new PathNode(root, root.getFileName().toString()));
        tree.setModel(new DefaultTreeModel(rootNode));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                populate(rootNode, root);
                return null;
            }

            @Override
            protected void done() {
                ((DefaultTreeModel) tree.getModel()).reload(rootNode);
                tree.expandPath(new TreePath(rootNode.getPath()));
                if (pendingReveal != null) {
                    reveal(pendingReveal);
                }
            }
        }.execute();
    }

    /**
     * Select and scroll to {@code file} in the tree (VS Code-style explorer sync).
     */
    public void reveal(Path file) {
        if (file == null || root == null) {
            return;
        }
        Path target = file.toAbsolutePath().normalize();
        pendingReveal = target;
        if (!target.startsWith(root)) {
            return;
        }
        Object modelRoot = tree.getModel().getRoot();
        if (!(modelRoot instanceof DefaultMutableTreeNode rootNode)) {
            return;
        }
        DefaultMutableTreeNode found = findNode(rootNode, target);
        if (found == null) {
            return;
        }
        TreePath path = new TreePath(found.getPath());
        tree.expandPath(path.getParentPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private static DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, Path target) {
        Object user = node.getUserObject();
        if (user instanceof PathNode pn && pn.path().toAbsolutePath().normalize().equals(target)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode hit = findNode(child, target);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private void setEmpty() {
        DefaultMutableTreeNode empty = new DefaultMutableTreeNode("Open a folder…");
        tree.setModel(new DefaultTreeModel(empty));
        pendingReveal = null;
    }

    private Path selectedFile() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof PathNode pn) {
            return pn.path();
        }
        return null;
    }

    private void populate(DefaultMutableTreeNode parent, Path dir) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (HIDDEN.contains(name)) {
                    continue;
                }
                if (name.startsWith(".") && !name.equals(".gitignore")) {
                    continue;
                }
                children.add(child);
            }
        } catch (IOException ignored) {
            return;
        }

        children.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        for (Path child : children) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                    new PathNode(child, child.getFileName().toString())
            );
            parent.add(node);
            if (Files.isDirectory(child)) {
                populate(node, child);
            }
        }
    }

    private record PathNode(Path path, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
