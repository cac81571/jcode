package com.jcode.ui.git;

import com.jcode.model.GitStashEntry;
import com.jcode.service.GitService;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stash list with push / apply / pop / drop.
 */
public final class GitStashPanel extends JPanel {

    private final DefaultListModel<GitStashEntry> model = new DefaultListModel<>();
    private final JList<GitStashEntry> list = new JList<>(model);
    private final JLabel countLabel = new JLabel(" ");
    private final JCheckBox includeUntracked = new JCheckBox("未追跡も", true);

    private GitService git;
    private Runnable onChanged = () -> {
    };
    private Consumer<String> onStatus = s -> {
    };

    public GitStashPanel() {
        super(new BorderLayout(0, 2));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof GitStashEntry entry) {
                    setText(entry.ref() + "  " + entry.shortId() + "  " + entry.message());
                    setToolTipText(entry.message());
                }
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                list.setSelectedIndex(index);
                showMenu(e.getX(), e.getY());
            }
        });

        JButton stash = new JButton("Stash");
        stash.setToolTipText("作業ツリーの変更をスタッシュ");
        stash.addActionListener(e -> stashPush());

        JPanel headerBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        headerBtns.add(stash);
        headerBtns.add(includeUntracked);
        headerBtns.add(countLabel);

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("STASH"), BorderLayout.WEST);
        header.add(headerBtns, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public void setOnStatus(Consumer<String> onStatus) {
        this.onStatus = onStatus;
    }

    public void bind(GitService git) {
        this.git = git;
        refresh();
    }

    public void clear() {
        git = null;
        model.clear();
        countLabel.setText(" ");
    }

    public void refresh() {
        if (git == null) {
            model.clear();
            countLabel.setText(" ");
            return;
        }
        new SwingWorker<List<GitStashEntry>, Void>() {
            @Override
            protected List<GitStashEntry> doInBackground() throws Exception {
                return git.listStashes();
            }

            @Override
            protected void done() {
                try {
                    List<GitStashEntry> entries = get();
                    model.clear();
                    for (GitStashEntry entry : entries) {
                        model.addElement(entry);
                    }
                    countLabel.setText(entries.size() + " 件");
                } catch (Exception ex) {
                    countLabel.setText("取得失敗");
                    onStatus.accept("Stash list: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void stashPush() {
        if (git == null) {
            return;
        }
        String message = JOptionPane.showInputDialog(
                this,
                "スタッシュメッセージ（省略可）:",
                "Stash",
                JOptionPane.PLAIN_MESSAGE
        );
        if (message == null) {
            return;
        }
        try {
            var created = git.stashPush(message, includeUntracked.isSelected());
            if (created == null) {
                onStatus.accept("スタッシュする変更がありません");
                return;
            }
            onStatus.accept("Stashed: " + created.getShortMessage());
            refresh();
            onChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Stash", JOptionPane.ERROR_MESSAGE);
            onStatus.accept("Stash 失敗: " + ex.getMessage());
        }
    }

    private void showMenu(int x, int y) {
        GitStashEntry entry = list.getSelectedValue();
        if (entry == null || git == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem apply = new JMenuItem("Apply（適用・残す）");
        JMenuItem pop = new JMenuItem("Pop（適用して削除）");
        JMenuItem drop = new JMenuItem("Drop（削除のみ）");
        apply.addActionListener(e -> applyStash(entry, false));
        pop.addActionListener(e -> applyStash(entry, true));
        drop.addActionListener(e -> dropStash(entry));
        menu.add(apply);
        menu.add(pop);
        menu.addSeparator();
        menu.add(drop);
        menu.show(list, x, y);
    }

    private void applyStash(GitStashEntry entry, boolean pop) {
        if (git == null || entry == null) {
            return;
        }
        try {
            if (pop) {
                git.stashPop(entry.index());
                onStatus.accept("Pop: " + entry.ref());
            } else {
                git.stashApply(entry.index());
                onStatus.accept("Apply: " + entry.ref());
            }
            refresh();
            onChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    pop ? "Stash Pop" : "Stash Apply", JOptionPane.ERROR_MESSAGE);
            onStatus.accept("Stash 失敗: " + ex.getMessage());
        }
    }

    private void dropStash(GitStashEntry entry) {
        if (git == null || entry == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(
                this,
                entry.ref() + "\n" + entry.message() + "\n\nを削除しますか？",
                "Stash Drop",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            git.stashDrop(entry.index());
            onStatus.accept("Drop: " + entry.ref());
            refresh();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Stash Drop", JOptionPane.ERROR_MESSAGE);
            onStatus.accept("Drop 失敗: " + ex.getMessage());
        }
    }
}
