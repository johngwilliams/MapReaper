import burp.reaper.net.HostUtils;
import burp.reaper.model.GraphModel;
import burp.reaper.model.GraphModel.EdgeType;

import java.util.List;

/** Offline sanity check of the dependency-free core (no Burp/Montoya required). */
public class Test {
    static int pass = 0, fail = 0;

    static void eq(String what, Object got, Object want) {
        boolean ok = (got == null && want == null) || (got != null && got.equals(want));
        System.out.printf("%s %s -> got=%s want=%s%n", ok ? "PASS" : "FAIL", what, got, want);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        // ---- hostOf ----
        eq("hostOf https+path", HostUtils.hostOf("https://www.aramco.jobs/path?x=1#f"), "www.aramco.jobs");
        eq("hostOf protocol-rel", HostUtils.hostOf("//s.go-mpulse.net/boomerang/XYZ"), "s.go-mpulse.net");
        eq("hostOf port+userinfo", HostUtils.hostOf("http://user:pw@api.cloudinary.com:443/v1"), "api.cloudinary.com");
        eq("hostOf bare-noscheme", HostUtils.hostOf("api.tiles.mapbox.com/v4"), "api.tiles.mapbox.com");
        eq("hostOf rejects word", HostUtils.hostOf("justsometext"), null);
        eq("hostOf ipv4", HostUtils.hostOf("http://10.0.0.5:8080/x"), "10.0.0.5");

        // ---- registrableDomain ----
        eq("rd simple", HostUtils.registrableDomain("www.aramco.jobs"), "aramco.jobs");
        eq("rd co.uk", HostUtils.registrableDomain("careers.aramco.co.uk"), "aramco.co.uk");
        eq("rd apex", HostUtils.registrableDomain("cloudinary.com"), "cloudinary.com");
        eq("rd deep", HostUtils.registrableDomain("api.tiles.mapbox.com"), "mapbox.com");
        eq("rd ipv4", HostUtils.registrableDomain("10.0.0.5"), "10.0.0.5");

        // ---- extractUrls + graph assembly on a realistic body ----
        String body = String.join("\n",
                "<script src=\"https://maps.googleapis.com/maps/api/js?key=AIzaFAKE\"></script>",
                "var cz = 'https://analytics.churnzero.net/collect';",
                "fetch('https://api.cloudinary.com/v1_1/brazen/auto/upload');",
                "img.src='//cdn.jsdelivr.net/npm/thing.js';",
                "// a stray comment with no url here",
                "<a href=\"https://support.brazenconnect.com/help\">help</a>",
                "<a href=\"https://www.aramco.jobs/apply\">apply</a>",   // same-domain-ish link
                "s.src = 'https://s.go-mpulse.net/boomerang/NU7TD';");

        List<String> urls = HostUtils.extractUrls(body);
        System.out.println("\nExtracted URLs: " + urls.size());
        for (String u : urls) System.out.println("   " + u);

        GraphModel model = new GraphModel();
        String page = "www.brazenconnect.com";
        model.contact(page, true, true, "https://www.brazenconnect.com/brazen-chat");
        for (String u : urls) {
            String h = HostUtils.hostOf(u);
            if (h != null && !h.equals(page)) {
                model.edge(page, h, EdgeType.REFERENCE, u);
            }
        }
        // Simulate that one of them was actually contacted later.
        model.contact("api.cloudinary.com", true, false, "https://api.cloudinary.com/v1_1/brazen/auto/upload");

        System.out.println("\nGraph: " + model.nodeCount() + " nodes, " + model.edgeCount() + " edges");
        for (GraphModel.Node n : model.nodes()) {
            System.out.printf("   %-32s contacted=%-5s domain=%s%n", n.id, n.contacted, n.registrableDomain);
        }

        boolean hasCloudinary = model.getNode("api.cloudinary.com") != null
                && model.getNode("api.cloudinary.com").contacted;
        boolean hasGoogle = model.getNode("maps.googleapis.com") != null;
        boolean commentNotHost = model.getNode("a") == null;
        eq("cloudinary contacted", hasCloudinary, true);
        eq("googleapis referenced", hasGoogle, true);
        eq("stray comment not a host", commentNotHost, true);
        eq("brazen is contacted", model.getNode(page).contacted, true);

        // ---- pathOf + URL-level drill-down (expand/collapse endpoints) ----
        eq("pathOf", HostUtils.pathOf("https://api.cloudinary.com/v1_1/brazen/auto/upload?x=1"), "/v1_1/brazen/auto/upload");
        eq("pathOf root", HostUtils.pathOf("https://x.com"), "/");

        GraphModel.Node cloud = model.getNode("api.cloudinary.com");
        eq("cloudinary has a path", cloud.pathCount() >= 1, true);
        int before = model.nodeCount();
        model.expandEndpoints("api.cloudinary.com");
        int afterExpand = model.nodeCount();
        eq("expand adds endpoint node(s)", afterExpand > before, true);
        eq("host marked expanded", model.getNode("api.cloudinary.com").expanded, true);
        model.collapseEndpoints("api.cloudinary.com");
        eq("collapse restores count", model.nodeCount(), before);
        eq("host marked collapsed", model.getNode("api.cloudinary.com").expanded, false);

        // ---- finding overlay marking ----
        model.markFinding("api.cloudinary.com", "[JSMReaper] Exposed Secret");
        eq("finding flag set", model.getNode("api.cloudinary.com").hasFinding, true);
        model.clearFindings();
        eq("finding cleared", model.getNode("api.cloudinary.com").hasFinding, false);

        System.out.printf("%n==== %d passed, %d failed ====%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
