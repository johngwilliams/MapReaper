package burp.reaper.ui;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * The suite-tab UI: a two-row toolbar of controls, the graph canvas in the centre, and a details
 * pane on the right that inspects the selected host/endpoint. Holds the live-capture flag the
 * extension's HttpHandler reads, the "rebuild from Site Map" action, the right-click "send to Burp
 * tools" actions, and the JSMReaper-findings overlay (read from Burp's shared audit-issue store).
 */

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.reaper.model.GraphModel;
import burp.reaper.model.GraphModel.EdgeType;
import burp.reaper.model.GraphModel.Edge;
import burp.reaper.model.GraphModel.Kind;
import burp.reaper.model.GraphModel.Node;
import burp.reaper.net.HostUtils;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MapReaperTab extends JPanel implements GraphPanel.NodeActions {

    private final MontoyaApi api;
    private final GraphModel model;
    private final GraphPanel graph;
    private final Runnable seedAction;
    private final AtomicBoolean liveEnabled = new AtomicBoolean(true);

    private final JTextArea details = new JTextArea();
    private Node current;

    public MapReaperTab(MontoyaApi api, GraphModel model, Runnable seedAction, boolean dark) {
        super(new BorderLayout());
        this.api = api;
        this.model = model;
        this.seedAction = seedAction;
        this.graph = new GraphPanel(model, dark);
        this.graph.setNodeActions(this);

        add(buildToolbars(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graph, buildDetails());
        split.setResizeWeight(1.0);
        split.setDividerLocation(0.76);
        add(split, BorderLayout.CENTER);

        graph.setSelectionListener(this::showDetails);
        showDetails(null);
    }

    public boolean isLiveCaptureEnabled() { return liveEnabled.get(); }

    public GraphPanel graphPanel() { return graph; }

    // ---- toolbars ----------------------------------------------------------------------------

    private JComponent buildToolbars() {
        JToolBar row1 = bar();
        JToggleButton live = new JToggleButton("Live capture", true);
        live.setToolTipText("Add hosts/links to the map as you browse the target through Burp");
        live.addActionListener(e -> liveEnabled.set(live.isSelected()));
        row1.add(live);
        row1.add(button("Rebuild from Site Map", "Re-scan everything already in Burp's Site Map",
                e -> seedAction.run()));
        row1.add(button("Overlay JSMReaper findings",
                "Mark hosts that have JSMReaper / JS Miner findings in Burp's issues, and add hosts they reference",
                e -> overlayFindings()));
        row1.add(button("Clear", "Remove every node and link from the map", e -> {
            model.clear();
            graph.clearSelection();
            graph.resetView();
        }));
        row1.addSeparator();
        row1.add(button("Re-layout", "Re-seed node positions and let the graph settle again",
                e -> graph.relayout()));
        row1.add(button("Unpin all", "Release every node you have dragged/pinned",
                e -> graph.unpinAll()));
        JToggleButton freeze = new JToggleButton("Freeze");
        freeze.setToolTipText("Stop the physics so nodes stay exactly where they are");
        freeze.addActionListener(e -> graph.setFreeze(freeze.isSelected()));
        row1.add(freeze);
        row1.addSeparator();
        row1.add(button("Export", "Save the graph as JSON or PNG", this::exportMenu));

        JToolBar row2 = bar();
        row2.add(new JLabel("Show: "));
        JCheckBox inScope = new JCheckBox("In-scope only");
        inScope.addActionListener(e -> graph.setInScopeOnly(inScope.isSelected()));
        row2.add(inScope);
        JCheckBox referenced = new JCheckBox("Referenced (un-visited) hosts", true);
        referenced.addActionListener(e -> graph.setShowReferenced(referenced.isSelected()));
        row2.add(referenced);
        row2.addSeparator();
        row2.add(new JLabel("Links: "));
        row2.add(edgeToggle("Contact", EdgeType.CONTACT));
        row2.add(edgeToggle("Redirect", EdgeType.REDIRECT));
        row2.add(edgeToggle("Reference", EdgeType.REFERENCE));
        row2.add(edgeToggle("CORS", EdgeType.CORS));
        row2.addSeparator();
        row2.add(new JLabel("Find: "));
        JTextField search = new JTextField(16);
        search.setMaximumSize(new Dimension(200, 26));
        search.setToolTipText("Highlight and centre on hosts containing this text");
        search.getDocument().addDocumentListener(new SimpleDocListener(
                () -> graph.setSearch(search.getText())));
        row2.add(search);

        JPanel holder = new JPanel(new GridLayout(2, 1));
        holder.add(row1);
        holder.add(row2);
        return holder;
    }

    private JToggleButton edgeToggle(String label, EdgeType type) {
        JToggleButton t = new JToggleButton(label, true);
        t.addActionListener(e -> graph.setEdgeTypeEnabled(type, t.isSelected()));
        return t;
    }

    private static JToolBar bar() {
        JToolBar b = new JToolBar();
        b.setFloatable(false);
        b.setBorderPainted(false);
        return b;
    }

    private static JButton button(String text, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(l);
        return b;
    }

    // ---- JSMReaper / scanner findings overlay ------------------------------------------------

    private void overlayFindings() {
        new Thread(() -> {
            int flagged = 0, addedHosts = 0, issues = 0;
            try {
                model.clearFindings();
                for (AuditIssue issue : api.siteMap().issues()) {
                    String name = issue.name();
                    if (name == null || !(name.contains("JSMReaper") || name.contains("JS Miner"))) {
                        continue;
                    }
                    issues++;
                    String base = issueHost(issue);
                    if (base != null) {
                        model.markFinding(base, name);
                        flagged++;
                        String detail = issue.detail();
                        if (detail != null) {
                            for (String u : HostUtils.extractUrls(detail)) {
                                String h = HostUtils.hostOf(u);
                                if (h != null && !h.equals(base)) {
                                    model.edge(base, h, EdgeType.REFERENCE, u);
                                    addedHosts++;
                                }
                            }
                        }
                    }
                }
                api.logging().logToOutput("[MapReaper] JSMReaper overlay: " + issues
                        + " matching issue(s), flagged " + flagged + " host(s), added "
                        + addedHosts + " referenced host-link(s).");
                if (issues == 0) {
                    api.logging().logToOutput("[MapReaper] No [JSMReaper]/[JS Miner] issues found in Burp "
                            + "yet — run a JSMReaper scan first so its findings are in the issues list.");
                }
            } catch (Exception ex) {
                api.logging().logToError("[MapReaper] Findings overlay failed: " + ex);
            }
            SwingUtilities.invokeLater(graph::refresh);
        }, "mapreaper-overlay").start();
    }

    private static String issueHost(AuditIssue issue) {
        try {
            HttpService svc = issue.httpService();
            if (svc != null && svc.host() != null && !svc.host().isEmpty()) {
                return svc.host().toLowerCase();
            }
        } catch (Exception ignored) {
            // fall through to baseUrl
        }
        try {
            return HostUtils.hostOf(issue.baseUrl());
        } catch (Exception e) {
            return null;
        }
    }

    // ---- NodeActions (right-click menu) ------------------------------------------------------

    private HttpRequest requestFor(Node n) {
        return HttpRequest.httpRequestFromUrl(n.bestUrl());
    }

    @Override
    public void sendToRepeater(Node n) {
        try {
            api.repeater().sendToRepeater(requestFor(n), "MapReaper: " + shortLabel(n));
            api.logging().logToOutput("[MapReaper] Sent to Repeater: " + n.bestUrl());
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Send to Repeater failed: " + e);
        }
    }

    @Override
    public void sendToIntruder(Node n) {
        try {
            api.intruder().sendToIntruder(requestFor(n));
            api.logging().logToOutput("[MapReaper] Sent to Intruder: " + n.bestUrl());
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Send to Intruder failed: " + e);
        }
    }

    @Override
    public void activeScan(Node n) {
        try {
            Audit audit = api.scanner().startAudit(
                    AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS));
            audit.addRequest(requestFor(n));
            api.logging().logToOutput("[MapReaper] Started active scan of: " + n.bestUrl());
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Active scan failed: " + e);
        }
    }

    @Override
    public void openInBrowser(Node n) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(n.bestUrl()));
            } else {
                api.logging().logToError("[MapReaper] Desktop browse not supported; URL: " + n.bestUrl());
            }
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Open in browser failed: " + e);
        }
    }

    @Override
    public void copyUrl(Node n) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(n.bestUrl()), null);
        api.logging().logToOutput("[MapReaper] Copied URL: " + n.bestUrl());
    }

    @Override
    public void addToScope(Node n) {
        try {
            if (n.kind == Kind.HOST) {
                api.scope().includeInScope("https://" + n.id + "/");
                api.scope().includeInScope("http://" + n.id + "/");
            } else {
                api.scope().includeInScope(n.id);
            }
            api.logging().logToOutput("[MapReaper] Added to scope: " + n.id);
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Add to scope failed: " + e);
        }
    }

    @Override
    public void removeNode(Node n) {
        model.remove(n.id);
        api.logging().logToOutput("[MapReaper] Removed from map: " + n.id);
    }

    private static String shortLabel(Node n) {
        return n.label.length() > 30 ? n.label.substring(0, 29) + "…" : n.label;
    }

    // ---- details pane ------------------------------------------------------------------------

    private JComponent buildDetails() {
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font("Monospaced", Font.PLAIN, 12));
        details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        actions.add(button("Copy host", "Copy the selected host to the clipboard", e -> {
            if (current != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(current.id), null);
            }
        }));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(button("Add to scope", "Add the selected host to Burp's target scope",
                e -> { if (current != null) { addToScope(current); } }));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(button("Remove", "Remove the selected node from the map", e -> {
            if (current != null) { removeNode(current); graph.clearSelection(); }
        }));

        JPanel east = new JPanel(new BorderLayout());
        east.setPreferredSize(new Dimension(330, 100));
        east.add(new JScrollPane(details), BorderLayout.CENTER);
        east.add(actions, BorderLayout.SOUTH);
        return east;
    }

    private void showDetails(Node n) {
        this.current = n;
        if (n == null) {
            details.setText(
                    "MapReaper — dependency mind-map\n"
                    + "part of the *Reaper Toolkit, created by John Gwilliams\n\n"
                    + "Click a node to inspect it. Right-click a node for actions\n"
                    + "(send to Repeater/Intruder, active scan, open, expand paths).\n\n"
                    + "Legend\n"
                    + "  ● primary app      (blue)\n"
                    + "  ● contacted host   (orange)\n"
                    + "  ● referenced only  (grey, not visited)\n"
                    + "  ● endpoint/path    (light blue, when expanded)\n"
                    + "  ● red badge        = JSMReaper/scanner finding\n"
                    + "  ─ contact / redirect / CORS links\n"
                    + "  ╌ reference link   (dashed)\n"
                    + "  gold ring = in Burp scope\n\n"
                    + "Drag = move/pin. Wheel = zoom. Drag background = pan.\n"
                    + "Double-click a node to centre; double-click empty space to reset.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (n.kind == Kind.ENDPOINT) {
            sb.append("ENDPOINT\n").append(n.label).append("\n\n");
            sb.append("Host : ").append(n.parentHost).append('\n');
            sb.append("URL  : ").append(n.id).append('\n');
            details.setText(sb.toString());
            details.setCaretPosition(0);
            return;
        }
        sb.append(n.id).append('\n');
        sb.append(repeat('=', Math.min(40, n.id.length()))).append("\n\n");
        sb.append("Registrable domain : ").append(n.registrableDomain).append('\n');
        sb.append("Status             : ").append(n.contacted ? "contacted" : "referenced only (not visited)").append('\n');
        sb.append("Primary app        : ").append(n.primary ? "yes" : "no").append('\n');
        sb.append("In Burp scope      : ").append(n.inScope ? "yes" : "no").append('\n');
        sb.append("Scheme             : ").append(n.secure ? "https" : "http").append('\n');
        sb.append("Requests seen      : ").append(n.requests.get()).append('\n');
        sb.append("Distinct paths     : ").append(n.pathCount()).append('\n');
        if (n.hasFinding) {
            sb.append("Finding            : ").append(n.findingSummary).append('\n');
        }

        List<String> out = new ArrayList<>();
        List<String> in = new ArrayList<>();
        for (Edge e : model.edges()) {
            if (e.types().contains(EdgeType.ENDPOINT)) {
                continue;
            }
            if (e.src.equals(n.id)) {
                out.add("  → " + e.dst + "  " + e.types());
            } else if (e.dst.equals(n.id)) {
                in.add("  ← " + e.src + "  " + e.types());
            }
        }
        sb.append("\nDepends on / links to (").append(out.size()).append(")\n");
        appendList(sb, out);
        sb.append("\nReached from (").append(in.size()).append(")\n");
        appendList(sb, in);

        List<String> paths = n.pathsSeen();
        if (!paths.isEmpty()) {
            sb.append("\nPaths (").append(paths.size()).append(") — right-click node → Expand paths\n");
            int shown = 0;
            for (String pth : paths) {
                if (shown++ >= 40) { sb.append("  … and ").append(paths.size() - 40).append(" more\n"); break; }
                sb.append("  ").append(pth).append('\n');
            }
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    private static void appendList(StringBuilder sb, List<String> items) {
        if (items.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        int shown = 0;
        for (String s : items) {
            if (shown++ >= 60) {
                sb.append("  … and ").append(items.size() - 60).append(" more\n");
                break;
            }
            sb.append(s).append('\n');
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(3, n); i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- export ------------------------------------------------------------------------------

    private void exportMenu(ActionEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem json = new JMenuItem("Export graph as JSON…");
        json.addActionListener(a -> exportJson());
        JMenuItem png = new JMenuItem("Export view as PNG…");
        png.addActionListener(a -> exportPng());
        menu.add(json);
        menu.add(png);
        JComponent src = (JComponent) e.getSource();
        menu.show(src, 0, src.getHeight());
    }

    private void exportJson() {
        File f = chooseSave("mapreaper-graph.json");
        if (f == null) {
            return;
        }
        try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) {
            w.write(graph.toJson());
            api.logging().logToOutput("[MapReaper] Exported graph JSON: " + f.getAbsolutePath());
        } catch (Exception ex) {
            api.logging().logToError("[MapReaper] JSON export failed: " + ex);
        }
    }

    private void exportPng() {
        File f = chooseSave("mapreaper-graph.png");
        if (f == null) {
            return;
        }
        try {
            ImageIO.write(graph.renderImage(), "png", f);
            api.logging().logToOutput("[MapReaper] Exported graph PNG: " + f.getAbsolutePath());
        } catch (Exception ex) {
            api.logging().logToError("[MapReaper] PNG export failed: " + ex);
        }
    }

    private File chooseSave(String suggested) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(suggested));
        int r = chooser.showSaveDialog(this);
        return r == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDocListener(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { fire(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { fire(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { fire(); }
        private void fire() { SwingUtilities.invokeLater(r); }
    }
}
