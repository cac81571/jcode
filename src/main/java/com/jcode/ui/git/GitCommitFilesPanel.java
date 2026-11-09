package com.jcode.ui.git;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitChange;
import com.jcode.service.GitService;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * Independent FILES column: changes in the selected HISTORY commit.
 */
public final class GitCommitFilesPanel extends JPanel {

    private final DefaultListModel<GitChange> model = new DefaultListModel<>();
    private final JList<GitChange> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");

    private GitService git;
    private String selectedCommitId;
    private Consumer<DiffTexts> onShowDiff = d -> {
    };
    private Consumer<String> onStatus = s -> {
    };

    public GitCommitFilesPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof GitChange change) {
                    setText(change.statusLabel() + "  " + change.path());
                    setToolTipText(change.path());
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedDiff();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1 && !e.isPopupTrigger()) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        list.setSelectedIndex(index);
                        showSelectedDiff();
                    }
                }
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("FILES"), BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    public void setOnShowDiff(Consumer<DiffTexts> onShowDiff) {
        this.onShowDiff = onShowDiff;
    }

    public void setOnStatus(Consumer<String> onStatus) {
        this.onStatus = onStatus;
    }

    public void bind(GitService git) {
        this.git = git;
        clearFiles();
    }

    public void clear() {
        git = null;
        clearFiles();
    }

    public void clearFiles() {
        selectedCommitId = null;
        model.clear();
        status.setText(" ");
    }

    public void showCommit(String commitId) {
        model.clear();
        selectedCommitId = null;
        if (git == null || commitId == null || commitId.isBlank()) {
            status.setText(" ");
            return;
        }
        selectedCommitId = commitId;
        status.setText("…");
        String id = commitId;
        new SwingWorker<List<GitChange>, Void>() {
            @Override
            protected List<GitChange> doInBackground() throws Exception {
                return git.listCommitChanges(id);
            }

            @Override
            protected void done() {
                if (!id.equals(selectedCommitId)) {
                    return;
                }
                try {
                    List<GitChange> changes = get();
                    model.clear();
                    for (GitChange change : changes) {
                        model.addElement(change);
                    }
                    status.setText(changes.size() + " files");
                    if (!changes.isEmpty()) {
                        list.setSelectedIndex(0);
                        showSelectedDiff();
                    }
                } catch (Exception ex) {
                    status.setText("失敗");
                    onStatus.accept("Commit files: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showSelectedDiff() {
        GitChange change = list.getSelectedValue();
        if (change == null || git == null || selectedCommitId == null) {
            return;
        }
        try {
            DiffTexts texts = git.loadCommitDiffTexts(selectedCommitId, change.path());
            onShowDiff.accept(texts);
            onStatus.accept("Commit diff: " + change.path());
        } catch (Exception ex) {
            onStatus.accept("Diff error: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Commit Diff", JOptionPane.ERROR_MESSAGE);
        }
    }
}
