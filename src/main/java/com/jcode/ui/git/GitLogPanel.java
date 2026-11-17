package com.jcode.ui.git;

import com.jcode.model.CommitGraphCell;
import com.jcode.model.GitCommitEntry;
import com.jcode.service.GitService;
import com.jcode.ui.ThemeManager;
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
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Git commit log list with Soft / Mixed / Hard reset.
 */
public final class GitLogPanel extends JPanel {

    private static final int LOG_LIMIT = 100;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final DefaultListModel<GitCommitEntry> model = new DefaultListModel<>();
    private final JList<GitCommitEntry> list = new JList<>(model) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };
    private final JLabel status = new JLabel(" ");
    private final CommitRenderer renderer = new CommitRenderer();

    private GitService git;
    private Runnable onReset = () -> {
    };
    private Consumer<String> onStatus = s -> {
    };
    private Consumer<GitCommitEntry> onCommitSelected = e -> {
    };
    private Consumer<GitCommitEntry> onCreatePatch = e -> {
    };
    /** (older, newer) for a 2-commit range patch. */
    private BiConsumer<GitCommitEntry, GitCommitEntry> onCreateRangePatch = (a, b) -> {
    };
    private boolean suppressSelectionEvent;

    public GitLogPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFont(UiFonts.ui(13f));
        list.setCellRenderer(renderer);
        list.setFixedCellHeight(38);
        list.setToolTipText("Ctrl+クリックで2件選択 → 右クリックで範囲パッチ");
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressSelectionEvent) {
                return;
            }
            // FILES / diff follow the lead (last clicked) selection
            onCommitSelected.accept(list.getSelectedValue());
        });

        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                list.setCursor(hitTestHash(e) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isPopupTrigger() || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                GitCommitEntry hit = hitTestHash(e);
                if (hit != null) {
                    copyCommitHash(hit);
                }
            }

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
                // Keep multi-selection when right-clicking an already selected row
                if (!list.isSelectedIndex(index)) {
                    list.setSelectedIndex(index);
                }
                showContextMenu(e.getX(), e.getY());
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("HISTORY"), BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
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

    public void setOnCreatePatch(Consumer<GitCommitEntry> onCreatePatch) {
        this.onCreatePatch = onCreatePatch != null ? onCreatePatch : e -> {
        };
    }

    public void setOnCreateRangePatch(BiConsumer<GitCommitEntry, GitCommitEntry> onCreateRangePatch) {
        this.onCreateRangePatch = onCreateRangePatch != null ? onCreateRangePatch : (a, b) -> {
        };
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
                    renderer.clearDisplayCache();
                    model.clear();
                    if (!entries.isEmpty()) {
                        model.addAll(entries);
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

    private void showContextMenu(int x, int y) {
        List<GitCommitEntry> selected = list.getSelectedValuesList();
        if (selected.isEmpty() || git == null) {
            return;
        }
        GitCommitEntry entry = list.getSelectedValue();
        JPopupMenu menu = new JPopupMenu();

        GitCommitEntry[] range = resolveSelectedRange(selected);
        if (range != null) {
            JMenuItem createRangePatch = new JMenuItem(
                    "選択範囲のパッチを作成（" + range[0].shortId() + ".." + range[1].shortId() + "）…");
            createRangePatch.addActionListener(e -> onCreateRangePatch.accept(range[0], range[1]));
            menu.add(createRangePatch);
            menu.addSeparator();
        } else if (entry != null) {
            JMenuItem createRangePatch = new JMenuItem("コミット範囲のパッチを作成…");
            createRangePatch.setToolTipText("Ctrl+クリックで2件選択すると、その範囲で作成できます");
            // older=null → open dialog with this commit as "to"
            createRangePatch.addActionListener(e -> onCreateRangePatch.accept(null, entry));
            menu.add(createRangePatch);
        }

        if (entry != null) {
            JMenuItem copyHash = new JMenuItem("コミットハッシュをコピー");
            copyHash.addActionListener(e -> copyCommitHash(entry));
            menu.add(copyHash);
            JMenuItem createBranch = new JMenuItem("ブランチを作成…");
            createBranch.addActionListener(e -> createBranchFrom(entry));
            menu.add(createBranch);
            JMenuItem createPatch = new JMenuItem("このコミットのパッチを作成…");
            createPatch.addActionListener(e -> onCreatePatch.accept(entry));
            menu.add(createPatch);
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
        }
        menu.show(list, x, y);
    }

    private void copyCommitHash(GitCommitEntry entry) {
        if (entry == null || entry.shortId() == null) {
            return;
        }
        String shortHash = entry.shortId();
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(shortHash), null);
        onStatus.accept("コピーしました: " + shortHash);
        status.setText("copied " + shortHash);
    }

    /** Returns the commit if the mouse is over its short hash link area. */
    private GitCommitEntry hitTestHash(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index < 0 || index >= model.size()) {
            return null;
        }
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(e.getPoint())) {
            return null;
        }
        GitCommitEntry entry = model.get(index);
        int x = e.getX() - bounds.x;
        int y = e.getY() - bounds.y;
        // Hash is on the top title row (cell height 38, top padding 4)
        if (y < 4 || y > 22) {
            return null;
        }
        FontMetrics graphFm = list.getFontMetrics(UiFonts.mono(11f));
        FontMetrics hashFm = list.getFontMetrics(UiFonts.ui(13f));
        int leftPad = 4;
        int graphW = graphFm.stringWidth(entry.graphPlain());
        int hashStart = leftPad + graphW;
        int hashEnd = hashStart + hashFm.stringWidth(entry.shortId());
        if (x >= hashStart && x <= hashEnd) {
            return entry;
        }
        return null;
    }

    /**
     * Newest-first list: smaller index is newer.
     * With 2+ selected, uses the newest and oldest among the selection as {@code [older, newer]}.
     */
    private GitCommitEntry[] resolveSelectedRange(List<GitCommitEntry> selected) {
        if (selected == null || selected.size() < 2) {
            return null;
        }
        int newestIdx = Integer.MAX_VALUE;
        int oldestIdx = -1;
        GitCommitEntry newer = null;
        GitCommitEntry older = null;
        for (GitCommitEntry entry : selected) {
            int idx = model.indexOf(entry);
            if (idx < 0) {
                continue;
            }
            if (idx < newestIdx) {
                newestIdx = idx;
                newer = entry;
            }
            if (idx > oldestIdx) {
                oldestIdx = idx;
                older = entry;
            }
        }
        if (newer == null || older == null || newestIdx == oldestIdx) {
            return null;
        }
        return new GitCommitEntry[]{older, newer};
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
        private final JLabel hash = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel authorPad = new JLabel();
        private final JLabel author = new JLabel();
        private final JLabel time = new JLabel();
        private final JPanel titleRow = new JPanel(new BorderLayout(0, 0));
        private final JPanel titleText = new JPanel(new BorderLayout(6, 0));
        private final JPanel meta = new JPanel(new BorderLayout(8, 0));
        private final JPanel authorRow = new JPanel(new BorderLayout(0, 0));
        private final FontMetrics graphMetrics;
        private final IdentityHashMap<GitCommitEntry, String> graphHtmlCache = new IdentityHashMap<>();
        private final IdentityHashMap<GitCommitEntry, String> refsHtmlCache = new IdentityHashMap<>();
        private final IdentityHashMap<GitCommitEntry, Integer> authorPadCache = new IdentityHashMap<>();
        private Boolean cachedDark;

        CommitRenderer() {
            setLayout(new BorderLayout(0, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 18));

            Font ui = UiFonts.ui(13f);
            Font mono = UiFonts.mono(11f);
            graphMetrics = getFontMetrics(mono);
            // Mono keeps lane columns aligned; slightly smaller than message
            graph.setFont(mono);
            Map<TextAttribute, Object> linkAttrs = new HashMap<>(ui.getAttributes());
            linkAttrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            hash.setFont(ui.deriveFont(linkAttrs));
            title.setFont(ui);
            authorPad.setFont(mono);
            author.setFont(UiFonts.ui(12f).deriveFont(Font.PLAIN));
            time.setFont(UiFonts.ui(12f).deriveFont(Font.PLAIN));
            time.setHorizontalAlignment(SwingConstants.RIGHT);

            titleText.setOpaque(false);
            titleText.add(hash, BorderLayout.WEST);
            titleText.add(title, BorderLayout.CENTER);

            titleRow.setOpaque(false);
            titleRow.add(graph, BorderLayout.WEST);
            titleRow.add(titleText, BorderLayout.CENTER);

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

        void clearDisplayCache() {
            graphHtmlCache.clear();
            refsHtmlCache.clear();
            authorPadCache.clear();
            cachedDark = null;
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends GitCommitEntry> list, GitCommitEntry entry, int index,
                boolean isSelected, boolean cellHasFocus
        ) {
            boolean dark = ThemeManager.isDark();
            if (cachedDark == null || cachedDark != dark) {
                clearDisplayCache();
                cachedDark = dark;
            }

            if (entry == null) {
                graph.setText("");
                hash.setText("");
                title.setText("");
                authorPad.setText("");
                author.setText("");
                time.setText("");
            } else {
                // Selected: plain text (no HTML parse). Unselected: cached HTML colors.
                if (isSelected) {
                    graph.setText(entry.graphPlain());
                } else {
                    graph.setText(graphHtmlCache.computeIfAbsent(
                            entry, e -> coloredGraphHtml(e.graphCellsOrEmpty())));
                }
                hash.setText(entry.shortId());
                title.setText(entry.shortMessage());
                authorPad.setText("");
                int pad = authorPadCache.computeIfAbsent(
                        entry, e -> graphMetrics.stringWidth(e.graphPlain()));
                authorPad.setPreferredSize(new Dimension(pad, 1));
                authorPad.setMinimumSize(new Dimension(pad, 1));
                author.setText(entry.author() == null ? "" : entry.author());

                boolean hasRefs = (entry.refs() != null && !entry.refs().isEmpty()) || entry.head();
                if (!hasRefs) {
                    time.setText(TIME_FMT.format(entry.when()));
                } else if (isSelected) {
                    time.setText(plainRefs(entry));
                } else {
                    time.setText(refsHtmlCache.computeIfAbsent(entry, CommitRenderer::rightLabelHtml));
                }
                setToolTipText(entry.shortId() + " — " + entry.shortMessage());
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
            graph.setForeground(fg);
            hash.setForeground(isSelected ? fg : linkColor());
            Color metaGray = metaGrayColor(isSelected, fg);
            author.setForeground(metaGray);
            boolean hasRefs = entry != null
                    && ((entry.refs() != null && !entry.refs().isEmpty()) || entry.head());
            if (!hasRefs || isSelected) {
                time.setForeground(hasRefs ? fg : metaGray);
            }
            authorPad.setForeground(bg);

            int width = list.getWidth();
            if (width <= 0 && list.getParent() != null) {
                width = list.getParent().getWidth();
            }
            if (width > 0) {
                setPreferredSize(new Dimension(width, 38));
            }
            return this;
        }

        private static String plainRefs(GitCommitEntry entry) {
            List<String> labels = new ArrayList<>();
            if (entry.head()) {
                labels.add("HEAD");
            }
            if (entry.refs() != null) {
                labels.addAll(entry.refs());
            }
            return String.join(", ", labels);
        }

        private static String coloredGraphHtml(List<CommitGraphCell> cells) {
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
                Color color = BranchColors.forLane(cell.lane());
                html.append("<font color='").append(BranchColors.html(color)).append("'>")
                        .append(cell.symbol())
                        .append("</font>&nbsp;");
            }
            html.append("</span></nobr></html>");
            return html.toString();
        }

        private static String rightLabelHtml(GitCommitEntry entry) {
            List<String> labels = new ArrayList<>();
            if (entry.head()) {
                labels.add("HEAD");
            }
            if (entry.refs() != null) {
                labels.addAll(entry.refs());
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
                if (labels.size() == 1 || (labels.size() == 2 && entry.head() && i == 1)) {
                    color = BranchColors.forLane(entry.commitLane());
                }
                html.append("<font color='").append(BranchColors.html(color)).append("'>")
                        .append("<b>").append(escapeHtml(label)).append("</b>")
                        .append("</font>");
            }
            html.append("</nobr></html>");
            return html.toString();
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static Color metaGrayColor(boolean selected, Color selectionFg) {
            if (selected) {
                return selectionFg;
            }
            return ThemeManager.isDark()
                    ? new Color(168, 168, 168)
                    : new Color(150, 150, 150);
        }

        private static Color linkColor() {
            return ThemeManager.isDark()
                    ? new Color(120, 180, 255)
                    : new Color(0, 102, 204);
        }
    }
}
