package com.jcode.ui.git;

import com.jcode.model.CommitGraphCell;
import com.jcode.model.GitCommitEntry;
import com.jcode.service.GitService;
import com.jcode.ui.UiFonts;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Git commit log list with Soft / Mixed / Hard reset.
 */
public final class GitLogPanel extends JPanel {

    private static final int LOG_LIMIT = 100;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DefaultListModel<GitCommitEntry> model = new DefaultListModel<>();
    private final JList<GitCommitEntry> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");

    private GitService git;
    private Runnable onReset = () -> {
    };
    private Consumer<String> onStatus = s -> {
    };
    private Consumer<GitCommitEntry> onCommitSelected = e -> {
    };
    private boolean suppressSelectionEvent;

    public GitLogPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(UiFonts.ui(13f));
        list.setCellRenderer(new CommitRenderer());
        list.setFixedCellHeight(38);
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressSelectionEvent) {
                return;
            }
            onCommitSelected.accept(list.getSelectedValue());
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
                showResetMenu(e.getX(), e.getY());
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("HISTORY"), BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    public void setOnReset(Runnable onReset) {
        this.onReset = onReset;
    }

    public void setOnStatus(Consumer<String> onStatus) {
        this.onStatus = onStatus;
    }

    public void setOnCommitSelected(Consumer<GitCommitEntry> onCommitSelected) {
        this.onCommitSelected = onCommitSelected;
    }

    public void clearSelection() {
        suppressSelectionEvent = true;
        try {
            list.clearSelection();
        } finally {
            suppressSelectionEvent = false;
        }
    }

    public void bind(GitService git) {
        this.git = git;
        refresh();
    }

    public void clear() {
        git = null;
        model.clear();
        status.setText(" ");
        onCommitSelected.accept(null);
    }

    public void refresh() {
        if (git == null) {
            model.clear();
            status.setText(" ");
            onCommitSelected.accept(null);
            return;
        }
        status.setText("読込中…");
        new SwingWorker<List<GitCommitEntry>, Void>() {
            @Override
            protected List<GitCommitEntry> doInBackground() throws Exception {
                return git.listLog(LOG_LIMIT);
            }

            @Override
            protected void done() {
                try {
                    List<GitCommitEntry> entries = get();
                    model.clear();
                    for (GitCommitEntry entry : entries) {
                        model.addElement(entry);
                    }
                    status.setText(entries.size() + " commits");
                    if (!entries.isEmpty()) {
                        list.setSelectedIndex(0);
                    } else {
                        onCommitSelected.accept(null);
                    }
                } catch (Exception ex) {
                    status.setText("ログ取得失敗");
                    onStatus.accept("Git log: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showResetMenu(int x, int y) {
        GitCommitEntry entry = list.getSelectedValue();
        if (entry == null || git == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem createBranch = new JMenuItem("ブランチを作成…");
        createBranch.addActionListener(e -> createBranchFrom(entry));
        menu.add(createBranch);
        menu.addSeparator();
        JMenuItem soft = new JMenuItem("Soft Reset（HEAD のみ移動）");
        JMenuItem mixed = new JMenuItem("Mixed Reset（ステージ解除）");
        JMenuItem hard = new JMenuItem("Hard Reset（作業ツリーも破棄）");
        soft.addActionListener(e -> resetTo(entry, GitService.ResetMode.SOFT));
        mixed.addActionListener(e -> resetTo(entry, GitService.ResetMode.MIXED));
        hard.addActionListener(e -> resetTo(entry, GitService.ResetMode.HARD));
        menu.add(soft);
        menu.add(mixed);
        menu.add(hard);
        menu.show(list, x, y);
    }

    private void createBranchFrom(GitCommitEntry entry) {
        if (git == null || entry == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(
                this,
                "ブランチ名（このコミットから作成）:\n" + entry.shortId() + "  " + entry.shortMessage(),
                "ブランチを作成",
                JOptionPane.PLAIN_MESSAGE
        );
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            return;
        }
        int checkout = JOptionPane.showConfirmDialog(
                this,
                "ブランチ「" + name + "」を作成します。\n作成後に切り替えますか？",
                "ブランチを作成",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (checkout == JOptionPane.CANCEL_OPTION || checkout == JOptionPane.CLOSED_OPTION) {
            return;
        }
        try {
            if (checkout == JOptionPane.YES_OPTION) {
                git.createAndCheckoutBranch(name, entry.id());
                onStatus.accept("作成して切替: " + name + " @ " + entry.shortId());
                onReset.run();
            } else {
                git.createBranch(name, entry.id());
                onStatus.accept("ブランチ作成: " + name + " @ " + entry.shortId());
            }
            refresh();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "ブランチを作成", JOptionPane.ERROR_MESSAGE);
            onStatus.accept("ブランチ作成失敗: " + ex.getMessage());
        }
    }

    private void resetTo(GitCommitEntry entry, GitService.ResetMode mode) {
        if (git == null || entry == null) {
            return;
        }
        String modeLabel = switch (mode) {
            case SOFT -> "Soft";
            case MIXED -> "Mixed";
            case HARD -> "Hard";
        };
        StringBuilder message = new StringBuilder();
        message.append(modeLabel).append(" Reset しますか？\n\n")
                .append(entry.shortId()).append("  ").append(entry.shortMessage()).append("\n")
                .append("author: ").append(entry.author()).append("\n");
        if (mode == GitService.ResetMode.HARD) {
            message.append("\n※ 未コミットの変更はすべて失われます。");
        } else if (mode == GitService.ResetMode.MIXED) {
            message.append("\n※ インデックスは戻り、作業ツリーの変更は残ります。");
        } else {
            message.append("\n※ インデックスと作業ツリーはそのままです。");
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                message.toString(),
                modeLabel + " Reset",
                JOptionPane.OK_CANCEL_OPTION,
                mode == GitService.ResetMode.HARD
                        ? JOptionPane.WARNING_MESSAGE
                        : JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            git.resetTo(entry.id(), mode);
            onStatus.accept(modeLabel + " Reset → " + entry.shortId());
            onReset.run();
            refresh();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Reset", JOptionPane.ERROR_MESSAGE);
            onStatus.accept("Reset 失敗: " + ex.getMessage());
        }
    }

    private static final class CommitRenderer extends JPanel implements ListCellRenderer<GitCommitEntry> {
        private final JLabel graph = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel authorPad = new JLabel();
        private final JLabel author = new JLabel();
        private final JLabel time = new JLabel();
        private final JPanel titleRow = new JPanel(new BorderLayout(0, 0));
        private final JPanel meta = new JPanel(new BorderLayout(8, 0));
        private final JPanel authorRow = new JPanel(new BorderLayout(0, 0));

        CommitRenderer() {
            setLayout(new BorderLayout(0, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 18));

            Font ui = UiFonts.ui(13f);
            // Mono keeps lane columns aligned; slightly smaller than message
            graph.setFont(UiFonts.mono(11f));
            title.setFont(ui);
            authorPad.setFont(UiFonts.mono(11f));
            author.setFont(UiFonts.ui(12f).deriveFont(Font.PLAIN));
            time.setFont(UiFonts.ui(12f).deriveFont(Font.PLAIN));
            time.setHorizontalAlignment(SwingConstants.RIGHT);

            titleRow.setOpaque(false);
            titleRow.add(graph, BorderLayout.WEST);
            titleRow.add(title, BorderLayout.CENTER);

            authorPad.setOpaque(false);
            authorRow.setOpaque(false);
            authorRow.add(authorPad, BorderLayout.WEST);
            authorRow.add(author, BorderLayout.CENTER);

            meta.setOpaque(false);
            meta.add(authorRow, BorderLayout.CENTER);
            meta.add(time, BorderLayout.EAST);

            add(titleRow, BorderLayout.NORTH);
            add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends GitCommitEntry> list, GitCommitEntry entry, int index,
                boolean isSelected, boolean cellHasFocus
        ) {
            if (entry == null) {
                graph.setText("");
                title.setText("");
                authorPad.setText("");
                author.setText("");
                time.setText("");
            } else {
                String plainGraph = entry.graphPlain();
                graph.setText(coloredGraphHtml(entry.graphCellsOrEmpty(), isSelected));
                title.setText(entry.shortMessage());
                authorPad.setText("");
                int pad = graph.getFontMetrics(UiFonts.mono(11f)).stringWidth(plainGraph);
                authorPad.setPreferredSize(new Dimension(pad, 1));
                authorPad.setMinimumSize(new Dimension(pad, 1));
                author.setText(entry.author() == null ? "" : entry.author());
                time.setText(rightLabelHtml(entry, isSelected));
                setToolTipText(entry.id().substring(0, 7) + "\n" + entry.fullMessage());
            }

            Color bg = isSelected
                    ? UIManager.getColor("List.selectionBackground")
                    : UIManager.getColor("List.background");
            Color fg = isSelected
                    ? UIManager.getColor("List.selectionForeground")
                    : UIManager.getColor("List.foreground");
            if (bg == null) {
                bg = isSelected ? new Color(60, 60, 60) : Color.WHITE;
            }
            if (fg == null) {
                fg = isSelected ? Color.WHITE : new Color(30, 30, 30);
            }
            setBackground(bg);
            title.setForeground(fg);
            Color muted = isSelected ? fg : new Color(
                    (fg.getRed() * 2 + 120) / 3,
                    (fg.getGreen() * 2 + 120) / 3,
                    (fg.getBlue() * 2 + 120) / 3
            );
            author.setForeground(muted);
            authorPad.setForeground(bg);

            int width = list.getWidth();
            if (width <= 0 && list.getParent() != null) {
                width = list.getParent().getWidth();
            }
            setPreferredSize(new Dimension(Math.max(120, width), 38));
            return this;
        }

        private static String coloredGraphHtml(List<CommitGraphCell> cells, boolean selected) {
            if (cells.isEmpty()) {
                return "";
            }
            StringBuilder html = new StringBuilder(
                    "<html><nobr><span style='font-size:11pt; font-family:monospace'>");
            for (CommitGraphCell cell : cells) {
                if (cell.kind() == CommitGraphCell.Kind.EMPTY) {
                    html.append("&nbsp;&nbsp;");
                    continue;
                }
                Color color = selected
                        ? UIManager.getColor("List.selectionForeground")
                        : BranchColors.forLane(cell.lane());
                if (color == null) {
                    color = BranchColors.forLane(cell.lane());
                }
                html.append("<font color='").append(BranchColors.html(color)).append("'>")
                        .append(cell.symbol())
                        .append("</font>&nbsp;");
            }
            html.append("</span></nobr></html>");
            return html.toString();
        }

        private static String rightLabelHtml(GitCommitEntry entry, boolean selected) {
            List<String> labels = new ArrayList<>();
            if (entry.head()) {
                labels.add("HEAD");
            }
            if (entry.refs() != null) {
                labels.addAll(entry.refs());
            }
            if (labels.isEmpty()) {
                return TIME_FMT.format(entry.when());
            }
            if (selected) {
                return String.join(", ", labels);
            }
            StringBuilder html = new StringBuilder("<html><nobr>");
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) {
                    html.append("<font color='#888888'>, </font>");
                }
                String label = labels.get(i);
                Color color = "HEAD".equals(label)
                        ? BranchColors.forLane(entry.commitLane())
                        : BranchColors.forBranch(label);
                // Prefer lane color when label is the only branch tip on this commit
                if (labels.size() == 1 || (labels.size() == 2 && entry.head() && i == 1)) {
                    color = BranchColors.forLane(entry.commitLane());
                }
                html.append("<font color='").append(BranchColors.html(color)).append("'>")
                        .append(escapeHtml(label))
                        .append("</font>");
            }
            html.append("</nobr></html>");
            return html.toString();
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
