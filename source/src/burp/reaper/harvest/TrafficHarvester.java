package burp.reaper.harvest;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * Turns a single request/response into graph nodes and edges. Shared by the Site Map seed pass and
 * the live HttpHandler, so it is written to be called from any thread (GraphModel is thread-safe).
 */

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.reaper.model.GraphModel;
import burp.reaper.model.GraphModel.EdgeType;
import burp.reaper.net.HostUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class TrafficHarvester {

    private final GraphModel model;
    private final MontoyaApi api;

    private static final Set<MimeType> TEXT_MIMES = EnumSet.of(
            MimeType.HTML, MimeType.SCRIPT, MimeType.JSON, MimeType.CSS,
            MimeType.XML, MimeType.PLAIN_TEXT, MimeType.IMAGE_SVG_XML, MimeType.YAML);

    public TrafficHarvester(GraphModel model, MontoyaApi api) {
        this.model = model;
        this.api = api;
    }

    /** Fold one exchange into the graph. Either argument may be null (e.g. a request with no response). */
    public void consume(HttpRequest request, HttpResponse response) {
        if (request == null) {
            return;
        }

        String host = hostOfRequest(request);
        if (host == null) {
            return;
        }
        boolean secure = isSecure(request);
        String url = safeUrl(request);
        boolean inScope = isInScope(request, url);

        model.contact(host, secure, inScope, url);

        // The page that caused this request -> a real "contact" dependency edge.
        String originHost = firstHost(request.headerValue("Referer"), request.headerValue("Origin"));
        if (originHost != null && !originHost.equals(host)) {
            model.edge(originHost, host, EdgeType.CONTACT, url);
        }

        if (response == null) {
            return;
        }

        // 3xx redirects to another host.
        int status = response.statusCode() & 0xffff;
        if (status >= 300 && status < 400) {
            String location = response.headerValue("Location");
            String locHost = HostUtils.hostOf(location); // only absolute Locations yield a cross-host edge
            if (locHost != null && !locHost.equals(host)) {
                model.edge(host, locHost, EdgeType.REDIRECT, location);
            }
        }

        // CORS: this host grants a (specific) origin cross-origin read access.
        String acao = response.headerValue("Access-Control-Allow-Origin");
        if (acao != null && !acao.trim().equals("*")) {
            String acaoHost = HostUtils.hostOf(acao.trim());
            if (acaoHost != null && !acaoHost.equals(host)) {
                model.edge(acaoHost, host, EdgeType.CORS, null);
            }
        }

        // Referenced hosts discovered by parsing a text body (the referenced-but-not-visited source).
        if (isText(response)) {
            String body;
            try {
                body = response.bodyToString();
            } catch (Exception e) {
                body = null;
            }
            if (body != null) {
                List<String> urls = HostUtils.extractUrls(body);
                int added = 0;
                for (String u : urls) {
                    String h = HostUtils.hostOf(u);
                    if (h != null && !h.equals(host)) {
                        model.edge(host, h, EdgeType.REFERENCE, u);
                        if (++added >= 300) {
                            break; // cap per response
                        }
                    }
                }
            }
        }
    }

    private boolean isText(HttpResponse response) {
        try {
            return TEXT_MIMES.contains(response.inferredMimeType());
        } catch (Exception e) {
            return false;
        }
    }

    private static String hostOfRequest(HttpRequest request) {
        try {
            HttpService svc = request.httpService();
            if (svc != null && svc.host() != null && !svc.host().isEmpty()) {
                return svc.host().toLowerCase();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return HostUtils.hostOf(safeUrl(request));
    }

    private static boolean isSecure(HttpRequest request) {
        try {
            HttpService svc = request.httpService();
            return svc != null && svc.secure();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInScope(HttpRequest request, String url) {
        try {
            if (request.isInScope()) {
                return true;
            }
        } catch (Exception ignored) {
            // some requests cannot answer isInScope(); fall back to the URL check
        }
        try {
            return url != null && api.scope().isInScope(url);
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeUrl(HttpRequest request) {
        try {
            return request.url();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstHost(String... values) {
        for (String v : values) {
            String h = HostUtils.hostOf(v);
            if (h != null) {
                return h;
            }
        }
        return null;
    }
}
