package burp.reaper.ui;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * The interactive mind-map canvas. Custom-painted (no external graph library): animated spring
 * layout, draggable + pinnable nodes, zoom/pan, hover tooltips, click-to-select with neighbour
 * highlighting, URL-level drill-down (expand a host into its path nodes), a right-click menu to send
 * a host/endpoint to Burp's tools, search, and scope / edge-type filtering. All state here lives on
 * the Swing EDT; the model it reads is thread-safe and may be mutated by Burp's HTTP threads.
 */

import burp.reaper.model.GraphModel;
import burp.reaper.model.GraphModel.EdgeType;
import burp.reaper.model.GraphModel.Edge;
import burp.reaper.model.GraphModel.Kind;
import burp.reaper.model.GraphModel.Node;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class GraphPanel extends JPanel {

    /** Actions the right-click menu delegates to the tab (which holds the Montoya API). */
    public interface NodeActions {
        void sendToRepeater(Node n);
        void sendToIntruder(Node n);
        void activeScan(Node n);
        void openInBrowser(Node n);
        void copyUrl(Node n);
        void addToScope(Node n);
        void removeNode(Node n);
    }

    private final GraphModel model;
    private final ForceLayout layout = new ForceLayout();
    private final Timer timer;

    private double zoom = 1.0;
    private double panX = 0, panY = 0;
    private boolean panInitialised = false;

    private long lastVersion = -1;
    private int settleFrames = 0;

    private Node selected, hover, drag;
    private Point lastDragPoint;
    private boolean dragIsPan;

    private boolean showReferenced = true;
    private boolean inScopeOnly = false;
    private boolean freeze = false;
    private final Set<EdgeType> enabledEdgeTypes = EnumSet.allOf(EdgeType.class);
    private String searchTerm = "";
    private final Set<String> searchHosts = new HashSet<>();
    private Set<String> primaryDomains = new HashSet<>();

    private Consumer<Node> selectionListener = n -> { };
    private NodeActions nodeActions;

    private final Palette palette;
    private final java.util.Random rnd = new java.util.Random(42);

    public GraphPanel(GraphModel model, boolean dark) {
        this.model = model;
        this.palette = dark ? Palette.dark() : Palette.light();
        setBackground(palette.bg);
        setToolTipText("");
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        timer = new Timer(28, e -> tick());
        timer.start();
    }

    public void stopAnimation() { timer.stop(); }

    public void setSelectionListener(Consumer<Node> l) { this.selectionListener = l == null ? n -> { } : l; }

    public void setNodeActions(NodeActions a) { this.nodeActions = a; }

    public Node getSelected() { return selected; }

    // ---- public controls (toolbar) -----------------------------------------------------------

    public void setShowReferenced(boolean b) { showReferenced = b; reenergise(); }
    public void setInScopeOnly(boolean b)    { inScopeOnly = b; reenergise(); }
    public void setFreeze(boolean b)         { freeze = b; if (!b) reenergise(); else repaint(); }

    public void setEdgeTypeEnabled(EdgeType t, boolean on) {
        if (on) { enabledEdgeTypes.add(t); } else { enabledEdgeTypes.remove(t); }
        repaint();
    }

    public void setSearch(String term) {
        this.searchTerm = term == null ? "" : term.trim().toLowerCase();
        recomputeSearch();
        Node first = null;
        for (String h : searchHosts) {
            Node n = model.getNode(h);
            if (n != null) { first = n; break; }
        }
        if (first != null) {
            selected = first;
            selectionListener.accept(first);
            centreOn(first);
        }
        repaint();
    }

    public void relayout() {
        for (Node n : model.nodes()) { n.placed = false; n.pinned = false; }
        reenergise();
    }

    public void unpinAll() {
        for (Node n : model.nodes()) { n.pinned = false; }
        reenergise();
    }

    public void resetView() {
        zoom = 1.0;
        centreGraph();
        repaint();
    }

    public void clearSelection() {
        selected = null;
        selectionListener.accept(null);
        repaint();
    }

    public void focusHost(String id) {
        Node n = model.getNode(id);
        if (n != null) {
            selected = n;
            selectionListener.accept(n);
            centreOn(n);
        }
    }

    public void refresh() { reenergise(); }

    private void reenergise() {
        settleFrames = 0;
        if (!timer.isRunning()) {
            timer.start();
        }
        repaint();
    }

    // ---- animation loop ----------------------------------------------------------------------

    private void tick() {
        long v = model.version();
        if (v != lastVersion) {
            lastVersion = v;
            recomputePrimaryDomains();
            recomputeSearch();
            settleFrames = 0;
        }
        if (!panInitialised && model.nodeCount() > 0) {
            centreGraph();
            panInitialised = true;
        }
        placeNewNodes();

        double energy = 0;
        if (!freeze) {
            Point wc = screenToWorld(getWidth() / 2.0, getHeight() / 2.0);
            energy = layout.step(model.nodes(), new ArrayList<>(model.edges()), wc.x, wc.y, drag);
        }

        if (drag == null && !freeze) {
            double threshold = 0.35 * Math.max(1, model.nodeCount());
            if (energy < threshold) {
                if (++settleFrames > 45) {
                    timer.stop();
                }
            } else {
                settleFrames = 0;
            }
        }
        repaint();
    }

    private void placeNewNodes() {
        Point wc = screenToWorld(getWidth() / 2.0, getHeight() / 2.0);
        double cx = wc.x, cy = wc.y;
        Map<String, Node> byId = null;
        for (Node n : model.nodes()) {
            if (n.placed) {
                continue;
            }
            double ox = 0, oy = 0;
            boolean anchored = false;
            if (byId == null) {
                byId = new HashMap<>();
                for (Node m : model.nodes()) { byId.put(m.id, m); }
            }
            for (Edge e : model.edges()) {
                Node other = null;
                if (e.src.equals(n.id)) { other = byId.get(e.dst); }
                else if (e.dst.equals(n.id)) { other = byId.get(e.src); }
                if (other != null && other.placed) {
                    ox = other.x; oy = other.y; anchored = true;
                    break;
                }
            }
            double angle = rnd.nextDouble() * Math.PI * 2;
            double radius = anchored ? 35 + rnd.nextDouble() * 45 : 60 + rnd.nextDouble() * 220;
            double baseX = anchored ? ox : cx;
            double baseY = anchored ? oy : cy;
            n.x = baseX + Math.cos(angle) * radius;
            n.y = baseY + Math.sin(angle) * radius;
            n.placed = true;
        }
    }

    // ---- derived state -----------------------------------------------------------------------

    private void recomputePrimaryDomains() {
        Map<String, Long> scopedCounts = new HashMap<>();
        Map<String, Long> allCounts = new HashMap<>();
        for (Node n : model.nodes()) {
            if (n.kind != Kind.HOST || !n.contacted || n.registrableDomain == null) {
                continue;
            }
            long r = Math.max(1, n.requests.get());
            allCounts.merge(n.registrableDomain, r, Long::sum);
            if (n.inScope) {
                scopedCounts.merge(n.registrableDomain, r, Long::sum);
            }
        }
        Set<String> result = new HashSet<>();
        if (!scopedCounts.isEmpty()) {
            result.addAll(scopedCounts.keySet());
        } else if (!allCounts.isEmpty()) {
            String best = null;
            long bestN = -1;
            for (Map.Entry<String, Long> e : allCounts.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            if (best != null) { result.add(best); }
        }
        primaryDomains = result;
        for (Node n : model.nodes()) {
            n.primary = n.kind == Kind.HOST && n.registrableDomain != null
                    && primaryDomains.contains(n.registrableDomain);
        }
    }

    private void recomputeSearch() {
        searchHosts.clear();
        if (searchTerm.isEmpty()) {
            return;
        }
        for (Node n : model.nodes()) {
            if (n.id.toLowerCase().contains(searchTerm)) {
                searchHosts.add(n.id);
            }
        }
    }

    private Set<String> neighboursOf(Node node) {
        Set<String> set = new HashSet<>();
        if (node == null) {
            return set;
        }
        for (Edge e : model.edges()) {
            if (e.src.equals(node.id)) { set.add(e.dst); }
            else if (e.dst.equals(node.id)) { set.add(e.src); }
        }
        return set;
    }

    // ---- filtering ---------------------------------------------------------------------------

    private boolean visible(Node n) {
        if (n == null) {
            return false;
        }
        if (inScopeOnly && !n.inScope) {
            return false;
        }
        if (!showReferenced && !n.contacted) {
            return false;
        }
        return true;
    }

    private boolean edgeVisible(Edge e) {
        if (!e.hasAnyType(enabledEdgeTypes)) {
            return false;
        }
        return visible(model.getNode(e.src)) && visible(model.getNode(e.dst));
    }

    // ---- painting ----------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (model.nodeCount() == 0) {
            drawEmptyState(g2);
            g2.dispose();
            return;
        }

        Set<String> nbrs = selected != null ? neighboursOf(selected) : null;
        boolean dim = selected != null;

        Map<String, double[]> pos = new HashMap<>();
        for (Node n : model.nodes()) {
            pos.put(n.id, new double[]{ n.x * zoom + panX, n.y * zoom + panY });
        }

        List<Edge> edges = new ArrayList<>(model.edges());
        boolean drawArrows = edges.size() < 1400 && zoom > 0.5;
        for (Edge e : edges) {
            if (!edgeVisible(e)) {
                continue;
            }
            double[] s = pos.get(e.src);
            double[] d = pos.get(e.dst);
            if (s == null || d == null) {
                continue;
            }
            boolean incident = !dim || (selected != null
                    && (e.src.equals(selected.id) || e.dst.equals(selected.id)));
            Set<EdgeType> types = e.types();
            Color c = edgeColour(types);
            g2.setColor(incident ? c : fade(c, 0.18f));
            g2.setStroke(edgeStroke(types, incident));
            g2.draw(new Line2D.Double(s[0], s[1], d[0], d[1]));
            if (drawArrows && incident) {
                drawArrowHead(g2, s[0], s[1], d[0], d[1], radiusOf(model.getNode(e.dst)) * zoom);
            }
        }

        Font base = getFont().deriveFont(Font.PLAIN, 11f);
        g2.setFont(base);
        for (Node n : model.nodes()) {
            if (!visible(n)) {
                continue;
            }
            double[] p = pos.get(n.id);
            double r = radiusOf(n) * zoom;
            boolean isSelected = n == selected;
            boolean isNeighbour = nbrs != null && nbrs.contains(n.id);
            boolean faded = dim && !isSelected && !isNeighbour;
            boolean matched = !searchHosts.isEmpty() && searchHosts.contains(n.id);

            Color fill = nodeColour(n);
            if (faded) {
                fill = fade(fill, 0.22f);
            }
            Ellipse2D circle = new Ellipse2D.Double(p[0] - r, p[1] - r, r * 2, r * 2);
            g2.setColor(fill);
            g2.fill(circle);

            if (n.inScope && n.kind == Kind.HOST) {
                g2.setColor(faded ? fade(palette.scopeRing, 0.3f) : palette.scopeRing);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(circle);
            } else {
                g2.setColor(faded ? fade(palette.nodeOutline, 0.3f) : palette.nodeOutline);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(circle);
            }

            if (matched) {
                g2.setColor(palette.searchHalo);
                g2.setStroke(new BasicStroke(2.5f));
                double hr = r + 4;
                g2.draw(new Ellipse2D.Double(p[0] - hr, p[1] - hr, hr * 2, hr * 2));
            }

            if (isSelected) {
                g2.setColor(palette.selectRing);
                g2.setStroke(new BasicStroke(3f));
                double sr = r + 3;
                g2.draw(new Ellipse2D.Double(p[0] - sr, p[1] - sr, sr * 2, sr * 2));
            }

            // Finding badge (JSMReaper / scanner).
            if (n.hasFinding && !faded) {
                double br = Math.max(3, r * 0.35);
                double bx = p[0] + r * 0.72;
                double by = p[1] - r * 0.72;
                g2.setColor(palette.findingBadge);
                g2.fill(new Ellipse2D.Double(bx - br, by - br, br * 2, br * 2));
                g2.setColor(palette.bg);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new Ellipse2D.Double(bx - br, by - br, br * 2, br * 2));
            }

            // Expanded-host indicator.
            if (n.kind == Kind.HOST && n.expanded && !faded) {
                g2.setColor(palette.pinMark);
                g2.fillRect((int) (p[0] - 2), (int) (p[1] - r - 7), 4, 4);
            }
            if (n.pinned) {
                g2.setColor(palette.pinMark);
                g2.fillRect((int) (p[0] + r * 0.4), (int) (p[1] - r - 3), 4, 4);
            }

            boolean showLabel = isSelected || n == hover || n.primary || matched
                    || (n.kind == Kind.HOST && n.contacted && r >= 7)
                    || (n.kind == Kind.ENDPOINT && zoom > 0.85);
            if (showLabel && !faded) {
                drawLabel(g2, n.label, p[0], p[1] + r + 12);
            }
        }

        drawHud(g2);
        g2.dispose();
    }

    private void drawEmptyState(Graphics2D g2) {
        g2.setColor(palette.labelDim);
        g2.setFont(getFont().deriveFont(Font.PLAIN, 13f));
        String msg = "No hosts yet — browse the target through Burp, or click \"Rebuild from Site Map\".";
        int w = g2.getFontMetrics().stringWidth(msg);
        g2.drawString(msg, Math.max(10, (getWidth() - w) / 2), getHeight() / 2);
    }

    private void drawLabel(Graphics2D g2, String text, double cx, double baselineY) {
        String t = text.length() > 46 ? text.substring(0, 45) + "…" : text;
        int w = g2.getFontMetrics().stringWidth(t);
        float x = (float) (cx - w / 2.0);
        g2.setColor(palette.labelShadow);
        g2.drawString(t, x + 1, (float) baselineY + 1);
        g2.setColor(palette.label);
        g2.drawString(t, x, (float) baselineY);
    }

    private void drawHud(Graphics2D g2) {
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(palette.labelDim);
        String line = model.nodeCount() + " nodes  •  " + model.edgeCount() + " links"
                + "  •  zoom " + String.format("%.0f%%", zoom * 100)
                + (freeze ? "  •  frozen" : "");
        g2.drawString(line, 10, getHeight() - 10);
    }

    private void drawArrowHead(Graphics2D g2, double sx, double sy, double dx, double dy, double dstR) {
        double ang = Math.atan2(dy - sy, dx - sx);
        double tipX = dx - Math.cos(ang) * (dstR + 1);
        double tipY = dy - Math.sin(ang) * (dstR + 1);
        double len = 7, spread = 0.5;
        g2.draw(new Line2D.Double(tipX, tipY,
                tipX - Math.cos(ang - spread) * len, tipY - Math.sin(ang - spread) * len));
        g2.draw(new Line2D.Double(tipX, tipY,
                tipX - Math.cos(ang + spread) * len, tipY - Math.sin(ang + spread) * len));
    }

    private double radiusOf(Node n) {
        if (n == null) {
            return 5;
        }
        if (n.kind == Kind.ENDPOINT) {
            return 4;
        }
        double r = n.contacted ? 7 : 5;
        r += Math.min(16, Math.sqrt(n.requests.get()) * 2.2);
        if (n.primary) {
            r += 3;
        }
        return r;
    }

    private Color nodeColour(Node n) {
        if (n.kind == Kind.ENDPOINT) {
            return palette.endpoint;
        }
        if (!n.contacted) {
            return palette.nodeReferenced;
        }
        return n.primary ? palette.nodePrimary : palette.nodeContacted;
    }

    private Color edgeColour(Set<EdgeType> types) {
        if (types.contains(EdgeType.ENDPOINT)) return palette.endpointEdge;
        if (types.contains(EdgeType.CONTACT)) return palette.edgeContact;
        if (types.contains(EdgeType.REDIRECT)) return palette.edgeRedirect;
        if (types.contains(EdgeType.CORS)) return palette.edgeCors;
        return palette.edgeReference;
    }

    private Stroke edgeStroke(Set<EdgeType> types, boolean incident) {
        boolean referenceOnly = types.contains(EdgeType.REFERENCE)
                && !types.contains(EdgeType.CONTACT)
                && !types.contains(EdgeType.REDIRECT)
                && !types.contains(EdgeType.CORS)
                && !types.contains(EdgeType.ENDPOINT);
        float w = incident ? 1.6f : 1.0f;
        if (referenceOnly) {
            return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, 0f);
        }
        return new BasicStroke(w);
    }

    private static Color fade(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, (int) (alpha * 255))));
    }

    // ---- geometry ----------------------------------------------------------------------------

    private Point screenToWorld(double sx, double sy) {
        Point p = new Point();
        p.x = (int) ((sx - panX) / zoom);
        p.y = (int) ((sy - panY) / zoom);
        return p;
    }

    private static final class Pt { final double x, y; Pt(double x, double y) { this.x = x; this.y = y; } }

    private Pt worldPoint(double sx, double sy) {
        return new Pt((sx - panX) / zoom, (sy - panY) / zoom);
    }

    private Node hitTest(int sx, int sy) {
        Node best = null;
        double bestD = Double.MAX_VALUE;
        for (Node n : model.nodes()) {
            if (!visible(n)) {
                continue;
            }
            double px = n.x * zoom + panX;
            double py = n.y * zoom + panY;
            double r = radiusOf(n) * zoom + 2;
            double dx = sx - px, dy = sy - py;
            double d2 = dx * dx + dy * dy;
            if (d2 <= r * r && d2 < bestD) {
                bestD = d2;
                best = n;
            }
        }
        return best;
    }

    private void centreOn(Node n) {
        panX = getWidth() / 2.0 - n.x * zoom;
        panY = getHeight() / 2.0 - n.y * zoom;
        repaint();
    }

    private void centreGraph() {
        Collection<Node> nodes = model.nodes();
        if (nodes.isEmpty()) {
            panX = getWidth() / 2.0;
            panY = getHeight() / 2.0;
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Node n : nodes) {
            minX = Math.min(minX, n.x); maxX = Math.max(maxX, n.x);
            minY = Math.min(minY, n.y); maxY = Math.max(maxY, n.y);
        }
        double gcx = (minX + maxX) / 2, gcy = (minY + maxY) / 2;
        panX = getWidth() / 2.0 - gcx * zoom;
        panY = getHeight() / 2.0 - gcy * zoom;
    }

    // ---- tooltip -----------------------------------------------------------------------------

    @Override
    public String getToolTipText(MouseEvent event) {
        Node n = hitTest(event.getX(), event.getY());
        if (n == null) {
            return null;
        }
        if (n.kind == Kind.ENDPOINT) {
            return "<html><b>" + esc(n.label) + "</b><br>endpoint on " + esc(n.parentHost) + "</html>";
        }
        int inbound = 0, outbound = 0;
        for (Edge e : model.edges()) {
            if (e.dst.equals(n.id)) { inbound++; }
            if (e.src.equals(n.id)) { outbound++; }
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(n.id)).append("</b><br>");
        sb.append("domain: ").append(esc(n.registrableDomain)).append("<br>");
        sb.append(n.contacted ? "contacted" : "referenced only (not visited)");
        if (n.primary) { sb.append(" &middot; primary app"); }
        if (n.inScope) { sb.append(" &middot; in scope"); }
        if (n.hasFinding) { sb.append(" &middot; <b>finding</b>"); }
        sb.append("<br>");
        sb.append(n.secure ? "https" : "http").append(" &middot; ")
                .append(n.requests.get()).append(" request(s) &middot; ")
                .append(n.pathCount()).append(" path(s)").append("<br>");
        sb.append("links: ").append(outbound).append(" out, ").append(inbound).append(" in");
        if (n.findingSummary != null) {
            sb.append("<br><i>").append(esc(abbreviate(n.findingSummary, 90))).append("</i>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- export ------------------------------------------------------------------------------

    public BufferedImage renderImage() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        paintComponent(g);
        g.dispose();
        return img;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"generator\": \"MapReaper (part of the *Reaper Toolkit, created by John Gwilliams)\",\n");
        sb.append("  \"nodes\": [\n");
        List<Node> nodes = new ArrayList<>(model.nodes());
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("    {\"id\": ").append(js(n.id))
                    .append(", \"kind\": ").append(js(n.kind.name()))
                    .append(", \"domain\": ").append(js(n.registrableDomain))
                    .append(", \"contacted\": ").append(n.contacted)
                    .append(", \"inScope\": ").append(n.inScope)
                    .append(", \"primary\": ").append(n.primary)
                    .append(", \"secure\": ").append(n.secure)
                    .append(", \"finding\": ").append(n.hasFinding)
                    .append(", \"requests\": ").append(n.requests.get())
                    .append("}").append(i < nodes.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n  \"edges\": [\n");
        List<Edge> edges = new ArrayList<>(model.edges());
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            sb.append("    {\"src\": ").append(js(e.src))
                    .append(", \"dst\": ").append(js(e.dst))
                    .append(", \"types\": ").append(js(e.types().toString()))
                    .append(", \"weight\": ").append(e.weight.get())
                    .append("}").append(i < edges.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String js(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- right-click menu --------------------------------------------------------------------

    private void showPopup(Node n, int x, int y) {
        if (n == null || nodeActions == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        if (n.kind == Kind.HOST && n.pathCount() > 0) {
            if (n.expanded) {
                menu.add(item("Collapse paths", () -> { model.collapseEndpoints(n.id); reenergise(); }));
            } else {
                menu.add(item("Expand paths (" + n.pathCount() + ")",
                        () -> { model.expandEndpoints(n.id); reenergise(); }));
            }
            menu.addSeparator();
        }
        menu.add(item("Send to Repeater", () -> nodeActions.sendToRepeater(n)));
        menu.add(item("Send to Intruder", () -> nodeActions.sendToIntruder(n)));
        menu.add(item("Active scan", () -> nodeActions.activeScan(n)));
        menu.addSeparator();
        menu.add(item("Open in browser", () -> nodeActions.openInBrowser(n)));
        menu.add(item("Copy URL", () -> nodeActions.copyUrl(n)));
        if (n.kind == Kind.HOST) {
            menu.addSeparator();
            menu.add(item("Add host to scope", () -> nodeActions.addToScope(n)));
        }
        menu.add(item("Remove from map", () -> { nodeActions.removeNode(n); clearSelection(); }));
        menu.show(this, x, y);
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    // ---- mouse -------------------------------------------------------------------------------

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (maybePopup(e)) {
                return;
            }
            Node hit = hitTest(e.getX(), e.getY());
            if (hit != null) {
                selected = hit;
                drag = hit;
                hit.pinned = true;
                selectionListener.accept(hit);
                reenergise();
            } else {
                dragIsPan = true;
                selected = null;
                selectionListener.accept(null);
            }
            lastDragPoint = e.getPoint();
            repaint();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (drag != null) {
                Pt w = worldPoint(e.getX(), e.getY());
                drag.x = w.x;
                drag.y = w.y;
                reenergise();
            } else if (dragIsPan && lastDragPoint != null) {
                panX += e.getX() - lastDragPoint.x;
                panY += e.getY() - lastDragPoint.y;
                lastDragPoint = e.getPoint();
                repaint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            drag = null;
            dragIsPan = false;
            maybePopup(e);
        }

        private boolean maybePopup(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return false;
            }
            Node hit = hitTest(e.getX(), e.getY());
            if (hit != null) {
                selected = hit;
                selectionListener.accept(hit);
                repaint();
                showPopup(hit, e.getX(), e.getY());
                return true;
            }
            return false;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                Node hit = hitTest(e.getX(), e.getY());
                if (hit != null) {
                    selected = hit;
                    selectionListener.accept(hit);
                    centreOn(hit);
                } else {
                    resetView();
                }
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            Node h = hitTest(e.getX(), e.getY());
            if (h != hover) {
                hover = h;
                setCursor(h != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
                repaint();
            }
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            double factor = e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1;
            double newZoom = Math.max(0.15, Math.min(4.0, zoom * factor));
            double wx = (e.getX() - panX) / zoom;
            double wy = (e.getY() - panY) / zoom;
            zoom = newZoom;
            panX = e.getX() - wx * zoom;
            panY = e.getY() - wy * zoom;
            repaint();
        }
    }

    // ---- theme palette -----------------------------------------------------------------------

    private static final class Palette {
        Color bg, label, labelDim, labelShadow, nodeOutline, scopeRing, selectRing, searchHalo, pinMark;
        Color nodePrimary, nodeContacted, nodeReferenced, endpoint, findingBadge;
        Color edgeContact, edgeRedirect, edgeReference, edgeCors, endpointEdge;

        static Palette dark() {
            Palette p = new Palette();
            p.bg = new Color(31, 32, 36);
            p.label = new Color(224, 227, 232);
            p.labelDim = new Color(140, 146, 156);
            p.labelShadow = new Color(0, 0, 0, 160);
            p.nodeOutline = new Color(90, 96, 108);
            p.scopeRing = new Color(240, 200, 70);
            p.selectRing = new Color(255, 255, 255);
            p.searchHalo = new Color(120, 220, 160);
            p.pinMark = new Color(240, 200, 70);
            p.nodePrimary = new Color(70, 170, 215);
            p.nodeContacted = new Color(228, 150, 72);
            p.nodeReferenced = new Color(108, 114, 126);
            p.endpoint = new Color(150, 170, 200);
            p.findingBadge = new Color(230, 70, 70);
            p.edgeContact = new Color(130, 140, 152, 210);
            p.edgeRedirect = new Color(165, 120, 215, 220);
            p.edgeReference = new Color(96, 102, 114, 150);
            p.edgeCors = new Color(216, 96, 96, 220);
            p.endpointEdge = new Color(120, 140, 170, 160);
            return p;
        }

        static Palette light() {
            Palette p = new Palette();
            p.bg = new Color(250, 250, 251);
            p.label = new Color(38, 42, 50);
            p.labelDim = new Color(120, 126, 136);
            p.labelShadow = new Color(255, 255, 255, 170);
            p.nodeOutline = new Color(170, 176, 186);
            p.scopeRing = new Color(200, 150, 20);
            p.selectRing = new Color(20, 22, 26);
            p.searchHalo = new Color(40, 160, 100);
            p.pinMark = new Color(200, 150, 20);
            p.nodePrimary = new Color(42, 130, 185);
            p.nodeContacted = new Color(214, 128, 42);
            p.nodeReferenced = new Color(150, 156, 166);
            p.endpoint = new Color(110, 140, 180);
            p.findingBadge = new Color(205, 50, 50);
            p.edgeContact = new Color(150, 160, 172, 210);
            p.edgeRedirect = new Color(132, 86, 186, 220);
            p.edgeReference = new Color(176, 182, 192, 160);
            p.edgeCors = new Color(200, 72, 72, 220);
            p.endpointEdge = new Color(150, 165, 190, 170);
            return p;
        }
    }
}
