package burp.reaper.model;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * Thread-safe store of the dependency graph. Mutated from Burp's HTTP threads (live capture and the
 * Site Map seed) and read from the Swing EDT (layout + painting). Node/edge metadata uses atomics
 * and volatiles; the collections are concurrent, so iteration from the EDT is always safe. The only
 * fields touched EXCLUSIVELY on the EDT are the layout coordinates on Node (x/y/vx/vy/pinned/placed).
 *
 * Nodes come in two kinds: HOST (a host / application) and ENDPOINT (an individual path on a host,
 * created on demand when the user "expands" a host for URL-level drill-down).
 */

import burp.reaper.net.HostUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GraphModel {

    /** How a source host is related to a destination host. */
    public enum EdgeType {
        CONTACT,   // a page (Referer/Origin) caused a request to the destination host
        REDIRECT,  // destination host reached via a 3xx Location redirect
        REFERENCE, // destination host appears in the source host's response body (may be un-visited)
        CORS,      // destination host grants the source host cross-origin access (ACAO)
        ENDPOINT   // internal: a host to one of its drilled-down path nodes
    }

    public enum Kind { HOST, ENDPOINT }

    /** One host / application, or (when drilled down) one path on a host. */
    public static final class Node {
        public final String id;                // unique key: host, or full URL for an endpoint
        public final Kind kind;
        public final String parentHost;         // endpoint only: the host it belongs to
        public final String label;              // what to draw (host, or the path for an endpoint)
        public final String registrableDomain;

        // --- metadata, written on Burp threads, read anywhere ---
        public volatile boolean contacted;
        public volatile boolean secure;
        public volatile boolean inScope;
        public volatile boolean primary;
        public volatile boolean hasFinding;     // JSMReaper / scanner finding overlaid on this host
        public volatile String findingSummary;
        public final AtomicInteger requests = new AtomicInteger();
        public volatile long lastSeen;
        private final Set<String> samples = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<String> paths = Collections.synchronizedSet(new LinkedHashSet<>());

        // --- layout state, EDT only ---
        public double x, y, vx, vy;
        public boolean pinned;
        public boolean placed;
        public boolean expanded;                // host only: its endpoints are currently shown

        /** Host node. */
        Node(String host) {
            this.id = host;
            this.kind = Kind.HOST;
            this.parentHost = null;
            this.label = host;
            this.registrableDomain = HostUtils.registrableDomain(host);
        }

        /** Endpoint node. */
        Node(String url, String parentHost, String label, String domain,
             boolean contacted, boolean inScope, boolean secure) {
            this.id = url;
            this.kind = Kind.ENDPOINT;
            this.parentHost = parentHost;
            this.label = label;
            this.registrableDomain = domain;
            this.contacted = contacted;
            this.inScope = inScope;
            this.secure = secure;
        }

        /** The best URL to act on (open/send to tools). Endpoints are already a URL. */
        public String bestUrl() {
            if (kind == Kind.ENDPOINT) {
                return id;
            }
            List<String> s = sampleUrls();
            if (!s.isEmpty()) {
                return s.get(0);
            }
            return (secure ? "https://" : "http://") + id + "/";
        }

        void addSample(String url) {
            if (url == null) {
                return;
            }
            synchronized (samples) {
                if (samples.size() < 8) {
                    samples.add(url);
                }
            }
        }

        void addPath(String path) {
            if (path == null) {
                return;
            }
            synchronized (paths) {
                if (paths.size() < 300) {
                    paths.add(path);
                }
            }
        }

        public List<String> sampleUrls() {
            synchronized (samples) {
                return new ArrayList<>(samples);
            }
        }

        public List<String> pathsSeen() {
            synchronized (paths) {
                return new ArrayList<>(paths);
            }
        }

        public int pathCount() {
            synchronized (paths) {
                return paths.size();
            }
        }
    }

    /** A directed relationship between two nodes, carrying one or more {@link EdgeType}s. */
    public static final class Edge {
        public final String src;
        public final String dst;
        public final String key;
        private final Set<EdgeType> types = EnumSet.noneOf(EdgeType.class);
        public final AtomicInteger weight = new AtomicInteger();

        Edge(String src, String dst) {
            this.src = src;
            this.dst = dst;
            this.key = src + "\u0000" + dst;
        }

        void addType(EdgeType t) {
            synchronized (types) {
                types.add(t);
            }
        }

        public Set<EdgeType> types() {
            synchronized (types) {
                return EnumSet.copyOf(types);
            }
        }

        public boolean hasAnyType(Set<EdgeType> allowed) {
            synchronized (types) {
                for (EdgeType t : types) {
                    if (allowed.contains(t)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Edge> edges = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    /** Monotonic counter bumped on every structural change, so the UI can detect "something new". */
    public long version() {
        return version.get();
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Collection<Edge> edges() {
        return edges.values();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Node getNode(String id) {
        return id == null ? null : nodes.get(id);
    }

    private Node host(String host) {
        return nodes.computeIfAbsent(host, h -> {
            version.incrementAndGet();
            return new Node(h);
        });
    }

    /** Record that we actually contacted a host (seed or live). */
    public Node contact(String host, boolean secure, boolean inScope, String url) {
        if (host == null) {
            return null;
        }
        Node n = host(host);
        n.contacted = true;
        n.secure = secure;
        n.inScope = inScope;
        n.requests.incrementAndGet();
        n.lastSeen = System.currentTimeMillis();
        n.addSample(url);
        n.addPath(HostUtils.pathOf(url));
        version.incrementAndGet();
        return n;
    }

    /** Add (or reinforce) a typed edge, creating endpoint-free host nodes as needed. */
    public void edge(String src, String dst, EdgeType type, String sampleUrl) {
        if (src == null || dst == null || src.equals(dst)) {
            return;
        }
        host(src);
        Node d = host(dst);
        if (sampleUrl != null) {
            d.addSample(sampleUrl);
            d.addPath(HostUtils.pathOf(sampleUrl));
        }
        Edge e = edges.computeIfAbsent(src + "\u0000" + dst, k -> {
            version.incrementAndGet();
            return new Edge(src, dst);
        });
        e.addType(type);
        e.weight.incrementAndGet();
        version.incrementAndGet();
    }

    /** Create endpoint child nodes for every path seen on a host (URL-level drill-down). */
    public void expandEndpoints(String hostId) {
        Node h = nodes.get(hostId);
        if (h == null || h.kind != Kind.HOST) {
            return;
        }
        for (String path : h.pathsSeen()) {
            String url = (h.secure ? "https://" : "http://") + h.id + path;
            if (!nodes.containsKey(url)) {
                nodes.put(url, new Node(url, h.id, path, h.registrableDomain,
                        h.contacted, h.inScope, h.secure));
                Edge e = edges.computeIfAbsent(h.id + "\u0000" + url, k -> new Edge(h.id, url));
                e.addType(EdgeType.ENDPOINT);
            }
        }
        h.expanded = true;
        version.incrementAndGet();
    }

    /** Remove the endpoint children of a host. */
    public void collapseEndpoints(String hostId) {
        Node h = nodes.get(hostId);
        if (h == null) {
            return;
        }
        List<String> toRemove = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.kind == Kind.ENDPOINT && hostId.equals(n.parentHost)) {
                toRemove.add(n.id);
            }
        }
        for (String id : toRemove) {
            nodes.remove(id);
        }
        edges.keySet().removeIf(k -> {
            Edge e = edges.get(k);
            return e != null && toRemove.contains(e.dst);
        });
        h.expanded = false;
        version.incrementAndGet();
    }

    /** Flag a host as carrying a finding (used by the JSMReaper overlay). */
    public void markFinding(String host, String summary) {
        if (host == null) {
            return;
        }
        Node n = host(host);
        n.hasFinding = true;
        String existing = n.findingSummary;
        if (existing == null || !existing.contains(summary)) {
            n.findingSummary = existing == null ? summary : existing + "; " + summary;
        }
        version.incrementAndGet();
    }

    public void clearFindings() {
        for (Node n : nodes.values()) {
            n.hasFinding = false;
            n.findingSummary = null;
        }
        version.incrementAndGet();
    }

    public void clear() {
        nodes.clear();
        edges.clear();
        version.incrementAndGet();
    }

    public void remove(String id) {
        if (id == null) {
            return;
        }
        nodes.remove(id);
        edges.keySet().removeIf(k -> {
            Edge e = edges.get(k);
            return e != null && (e.src.equals(id) || e.dst.equals(id));
        });
        version.incrementAndGet();
    }
}
