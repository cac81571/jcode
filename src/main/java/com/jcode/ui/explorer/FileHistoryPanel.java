package com.jcode.ui.explorer;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitCommitEntry;
import com.jcode.service.GitService;
import com.jcode.ui.PathEllipsis;
import com.jcode.ui.UiFonts;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Right-side panel: Git commits that touched a file; selection shows its diff.
 */
public final class FileHistoryPanel extends JPanel {

    private static final int HISTORY_LIMIT = 200;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final DefaultListModel<GitCommitEntry> model = new DefaultListModel<>();
    private final JList<GitCommitEntry> list = new JList<>(model);
    private final JLabel titleLabel = new JLabel("変更履歴");
    private final JLabel status = new JLabel(" ");
    private final AtomicInteger loadGeneration = new AtomicInteger();

    private GitService git;
    private Path file;
    private Consumer<DiffTexts> onShowDiff = t -> {
    };
    private Runnable onClose = () -> {
    };

    public FileHistoryPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new java.awt.Dimension(300, 200));
        setMinimumSize(new java.awt.Dimension(220, 120));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(UiFonts.ui(12f));
        list.setFixedCellHeight(40);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof GitCommitEntry entry) {
                    String msg = entry.shortMessage() == null ? "" : entry.shortMessage();
                    setText("<html><b>" + escape(entry.shortId()) + "</b> "
                            + escape(msg)
                            + "<br><font color='#888888'>"
                            + escape(TIME_FMT.format(entry.when()))
                            + " · " + escape(entry.author() == null ? "" : entry.author())
                            + "</font></html>");
                    setToolTipText(entry.fullMessage());
                }
                return this;
            }

            private String escape(String text) {
                return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
                if (e.getClickCount() >= 2) {
                    showSelectedDiff();
                }
            }
        });

        JButton close = new JButton("×");
        close.setToolTipText("パネルを閉じる");
        close.setMargin(new java.awt.Insets(0, 6, 0, 6));
        close.addActionListener(e -> closePanel());

        JPanel header = new JPanel(new BorderLayout(4, 0));
        titleLabel.setFont(UiFonts.ui(13f));
        header.add(titleLabel, BorderLayout.CENTER);
        JPanel headerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        headerBtns.add(close);
        header.add(headerBtns, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    public void setOnShowDiff(Consumer<DiffTexts> onShowDiff) {
        this.onShowDiff = onShowDiff != null ? onShowDiff : t -> {
        };
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose != null ? onClose : () -> {
        };
    }

    public void showFile(GitService git, Path file) {
        showFile(git, file, true);
    }

    /**
     * @param selectFirst if true, select the newest commit and open its diff
     */
    public void showFile(GitService git, Path file, boolean selectFirst) {
        if (file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (this.git == git
                && this.file != null
                && this.file.equals(normalized)) {
            return;
        }
        this.git = git;
        this.file = normalized;
        String name = normalized.getFileName() != null
                ? normalized.getFileName().toString()
                : normalized.toString();
        titleLabel.setText("変更履歴 — " + name);
        titleLabel.setToolTipText(normalized.toString());
        loadHistory(selectFirst);
    }

    /** Update header when the active editor file has no Git history. */
    public void showUnavailable(Path file, String reason) {
        loadGeneration.incrementAndGet();
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        model.clear();
        list.clearSelection();
        String name = this.file != null && this.file.getFileName() != null
                ? this.file.getFileName().toString()
                : "";
        titleLabel.setText("変更履歴 — " + name);
        titleLabel.setToolTipText(this.file != null ? this.file.toString() : null);
        status.setText(reason == null ? "履歴なし" : reason);
    }

    public Path currentFile() {
        return file;
    }

    public void clear() {
        loadGeneration.incrementAndGet();
        git = null;
        file = null;
        model.clear();
        list.clearSelection();
        titleLabel.setText("変更履歴");
        titleLabel.setToolTipText(null);
        status.setText(" ");
    }

    private void closePanel() {
        clear();
        onClose.run();
    }

    private void loadHistory(boolean selectFirst) {
        loadGeneration.incrementAndGet();
        model.clear();
        list.clearSelection();
        if (git == null || file == null) {
            status.setText(" ");
            return;
        }
        status.setText("読込中…");
        GitService service = git;
        Path target = file;
        int gen = loadGeneration.get();
        new SwingWorker<List<GitCommitEntry>, Void>() {
            @Override
            protected List<GitCommitEntry> doInBackground() throws Exception {
                return service.listFileLog(target, HISTORY_LIMIT);
            }

            @Override
            protected void done() {
                if (gen != loadGeneration.get()) {
                    return;
                }
                try {
                    List<GitCommitEntry> entries = get();
                    model.clear();
                    if (!entries.isEmpty()) {
                        model.addAll(entries);
                    }
                    String rel = service.toRepoRelativePath(target);
                    String label = rel != null ? PathEllipsis.middle(rel, 36) : String.valueOf(target.getFileName());
                    status.setText(label + " · " + entries.size() + " 件");
                    if (selectFirst && !entries.isEmpty()) {
                        list.setSelectedIndex(0);
                    }
                } catch (Exception ex) {
                    status.setText("履歴の取得に失敗");
                    JOptionPane.showMessageDialog(
                            FileHistoryPanel.this,
                            ex.getMessage(),
                            "変更履歴",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showSelectedDiff() {
        GitCommitEntry entry = list.getSelectedValue();
        if (entry == null || git == null || file == null) {
            return;
        }
        GitService service = git;
        Path target = file;
        String commitId = entry.id();
        int gen = loadGeneration.get();
        status.setText("Diff 読込中… " + entry.shortId());
        new SwingWorker<DiffTexts, Void>() {
            @Override
            protected DiffTexts doInBackground() throws Exception {
                return service.loadFileCommitDiff(target, commitId);
            }

            @Override
            protected void done() {
                if (gen != loadGeneration.get()) {
                    return;
                }
                try {
                    DiffTexts texts = get();
                    onShowDiff.accept(texts);
                    status.setText(entry.shortId() + "  " + entry.shortMessage());
                } catch (Exception ex) {
                    status.setText("Diff 失敗");
                    JOptionPane.showMessageDialog(
                            FileHistoryPanel.this,
                            ex.getMessage(),
                            "変更履歴",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
