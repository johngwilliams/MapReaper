package burp.reaper.net;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * Pure, dependency-free host/URL text helpers: extracting hosts from URLs, approximating the
 * registrable domain (eTLD+1) for grouping related hosts, and pulling referenced URLs out of a
 * response body. Uses only java.util.regex with linear-time patterns (single bounded character
 * classes, no catastrophic backtracking) so it is safe to run over hostile minified JavaScript.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HostUtils {

    private HostUtils() {}

    /** Scan at most this many characters of a body (guards against multi-MB bundles). */
    private static final int MAX_SCAN = 2_000_000;

    /** Absolute or protocol-relative URL token. The character class is bounded, so this is linear. */
    private static final Pattern URL_TOKEN = Pattern.compile(
            "(?i)(?:https?:)?//[^\\s\"'<>`()\\[\\]{}\\\\|^]+");

    /**
     * Two-level public suffixes we special-case so that, e.g., "www.aramco.co.uk" groups under
     * "aramco.co.uk" rather than "co.uk". Not the full Public Suffix List (that would mean bundling
     * a large data file); this is the common set and is documented as an approximation.
     */
    private static final Set<String> TWO_LEVEL_SUFFIXES = new HashSet<>(Arrays.asList(
            "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk",
            "com.au", "net.au", "org.au", "gov.au", "edu.au", "id.au",
            "co.nz", "net.nz", "org.nz", "govt.nz",
            "com.br", "net.br", "org.br", "gov.br",
            "com.cn", "net.cn", "org.cn", "gov.cn",
            "com.mx", "com.ar", "com.co", "com.pe", "com.ve",
            "co.jp", "or.jp", "ne.jp", "go.jp",
            "co.kr", "or.kr",
            "co.in", "net.in", "org.in", "gov.in",
            "co.za", "org.za", "gov.za",
            "com.sg", "com.hk", "com.tw", "com.my", "com.ph", "com.vn", "com.tr", "com.ua",
            "co.il", "com.sa", "com.eg", "com.ng", "com.pk"));

    /** Extract the lowercase host from a full or protocol-relative URL / origin string. */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Strip scheme.
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        } else if (s.startsWith("//")) {
            s = s.substring(2);
        }
        // Cut authority at first path/query/fragment delimiter.
        int cut = indexOfAny(s, "/?#");
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        // Drop userinfo.
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        // Drop port. Keep bracketed IPv6 literals intact.
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end >= 0) {
                s = s.substring(0, end + 1);
            }
        } else {
            int colon = s.indexOf(':');
            if (colon >= 0) {
                s = s.substring(0, colon);
            }
        }
        s = s.toLowerCase();
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return isPlausibleHost(s) ? s : null;
    }

    /** A host must contain a dot and end in an alphabetic TLD of length >= 2 (or be an IP/IPv6). */
    private static boolean isPlausibleHost(String h) {
        if (h == null || h.isEmpty()) {
            return false;
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            return true; // IPv6 literal
        }
        if (h.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return true; // IPv4
        }
        int dot = h.lastIndexOf('.');
        if (dot < 0 || dot == h.length() - 1) {
            return false;
        }
        String tld = h.substring(dot + 1);
        if (tld.length() < 2) {
            return false;
        }
        for (int i = 0; i < tld.length(); i++) {
            if (!Character.isLetter(tld.charAt(i))) {
                return false;
            }
        }
        // Reject obvious non-hosts (all chars must be host-legal).
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** The path portion of a URL (leading '/', no query/fragment); "/" if none. */
    public static String pathOf(String url) {
        if (url == null) {
            return "/";
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        } else if (s.startsWith("//")) {
            s = s.substring(2);
        }
        int slash = s.indexOf('/');
        if (slash < 0) {
            return "/";
        }
        s = s.substring(slash);
        int cut = indexOfAny(s, "?#");
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        return s.isEmpty() ? "/" : s;
    }

    /** Approximate the registrable domain (eTLD+1) used to group related hosts. */
    public static String registrableDomain(String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return host; // IP address: it is its own group
        }
        String[] labels = host.split("\\.");
        int n = labels.length;
        if (n <= 2) {
            return host;
        }
        String lastTwo = labels[n - 2] + "." + labels[n - 1];
        if (TWO_LEVEL_SUFFIXES.contains(lastTwo)) {
            return labels[n - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    /** All absolute / protocol-relative URLs found in a body (deduplicated, bounded scan). */
    public static List<String> extractUrls(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        String scan = body.length() > MAX_SCAN ? body.substring(0, MAX_SCAN) : body;
        Set<String> seen = new HashSet<>();
        Matcher m = URL_TOKEN.matcher(scan);
        while (m.find()) {
            String u = trimTrailingJunk(m.group());
            if (u.length() > 4 && seen.add(u) && hostOf(u) != null) {
                out.add(u);
            }
            if (out.size() >= 5000) {
                break;
            }
        }
        return out;
    }

    /** Strip trailing punctuation that regularly rides along at the end of a URL in source. */
    private static String trimTrailingJunk(String u) {
        int end = u.length();
        while (end > 0) {
            char c = u.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
                    || c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`') {
                end--;
            } else {
                break;
            }
        }
        return u.substring(0, end);
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }
}
