package burp.reaper;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * A Burp Suite (Montoya API) extension that draws a dynamic, mind-map-style dependency graph of
 * every application and host a target depends on or links to: the hosts actually contacted, the
 * hosts only referenced in responses (potential targets you have not touched yet), redirects and
 * CORS trust relationships. The graph is seeded from the existing Site Map and grows live as you
 * browse through Burp.
 */

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.Theme;
import burp.reaper.harvest.TrafficHarvester;
import burp.reaper.model.GraphModel;
import burp.reaper.ui.MapReaperTab;

import javax.swing.SwingUtilities;
import java.util.List;

public final class MapReaperExtension implements BurpExtension {

    private static final String NAME = "MapReaper";
    private static final String VERSION = "1.1";
    private static final String DESCRIPTOR = "MapReaper part of the *Reaper Toolkit, created by John Gwilliams";

    private MontoyaApi api;
    private GraphModel model;
    private TrafficHarvester harvester;
    private MapReaperTab tab;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName(NAME);

        this.model = new GraphModel();
        this.harvester = new TrafficHarvester(model, api);

        boolean dark = api.userInterface().currentTheme() == Theme.DARK;
        this.tab = new MapReaperTab(api, model, this::seedFromSiteMap, dark);

        api.userInterface().registerSuiteTab(NAME, tab);
        api.http().registerHttpHandler(new LiveHandler());

        api.extension().registerUnloadingHandler(() -> {
            if (tab != null) {
                tab.graphPanel().stopAnimation();
            }
        });

        api.logging().logToOutput(banner());

        // Seed from whatever is already in the Site Map, off the EDT.
        new Thread(this::seedFromSiteMap, "mapreaper-seed").start();
    }

    private String banner() {
        return "============================================================\n"
                + " " + NAME + " v" + VERSION + "\n"
                + " " + DESCRIPTOR + "\n"
                + "------------------------------------------------------------\n"
                + " Dynamic dependency mind-map of the hosts a target relies on.\n"
                + " Open the \"" + NAME + "\" tab. Browse the target through Burp\n"
                + " to grow the map live, or click \"Rebuild from Site Map\".\n"
                + "============================================================";
    }

    /** Re-read the Site Map and fold every exchange into the graph. Safe to call repeatedly. */
    private void seedFromSiteMap() {
        try {
            List<HttpRequestResponse> entries = api.siteMap().requestResponses();
            int count = 0;
            for (HttpRequestResponse rr : entries) {
                try {
                    harvester.consume(rr.request(), rr.hasResponse() ? rr.response() : null);
                    count++;
                } catch (Exception perEntry) {
                    // one bad entry should never abort the whole seed
                }
            }
            api.logging().logToOutput("[MapReaper] Seeded from Site Map: " + count
                    + " entries → " + model.nodeCount() + " hosts, " + model.edgeCount() + " links.");
            SwingUtilities.invokeLater(() -> tab.graphPanel().resetView());
        } catch (Exception e) {
            api.logging().logToError("[MapReaper] Site Map seed failed: " + e);
        }
    }

    /** Feeds live proxy/browser traffic into the graph when live capture is enabled. */
    private final class LiveHandler implements HttpHandler {
        @Override
        public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
            return RequestToBeSentAction.continueWith(request);
        }

        @Override
        public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
            try {
                if (tab != null && tab.isLiveCaptureEnabled()) {
                    harvester.consume(response.initiatingRequest(), response);
                }
            } catch (Exception e) {
                // never let a graph-building error disturb Burp's traffic
            }
            return ResponseReceivedAction.continueWith(response);
        }
    }
}
