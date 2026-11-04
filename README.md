# MapReaper  (v1.1)

**Part of the \*Reaper Toolkit, created by John Gwilliams.**

A Burp Suite extension that draws a **dynamic, mind-map-style dependency graph** of every
application and host a target relies on or links to — so you can see associated sites and potential
targets at a glance.

It is a fresh extension built on the modern **Montoya API** (not a fork of JS Miner / JSMReaper),
and it has **no external dependencies**: the graph is rendered with a self-contained, force-directed
Swing canvas. There is no JavaFX/WebView involved, so it loads in any Burp build.

### New in v1.1
- **URL-level drill-down** — right-click a host → *Expand paths* to spawn a child node per distinct
  path seen on that host (collapse to hide them again).
- **Right-click → send to Burp tools** — on any host or endpoint: *Send to Repeater*, *Send to
  Intruder*, *Active scan*, *Open in browser*, *Copy URL*, *Add host to scope*, *Remove from map*.
- **JSMReaper findings overlay** — the *Overlay JSMReaper findings* button reads Burp's issue store,
  flags every host that has a `[JSMReaper]` / `[JS Miner]` finding with a **red badge**, and adds the
  hosts those findings reference to the map.

---

## What it shows

Each **node** is a host / application. Each **edge** is a directed relationship:

| Link type    | Meaning                                                                 | Style            |
|--------------|-------------------------------------------------------------------------|------------------|
| `CONTACT`    | A page (its `Referer`/`Origin`) caused a request to the target host     | solid, neutral   |
| `REDIRECT`   | Host reached via a `3xx` `Location` redirect to another host            | solid, purple    |
| `REFERENCE`  | Host found **inside a response body** (may never have been visited)     | **dashed**, grey |
| `CORS`       | Host grants another origin cross-origin access (`Access-Control-Allow-Origin`) | solid, red |

Node colouring:

- **Blue** — the *primary application* (the registrable domain that is in Burp scope, or the most-
  contacted domain if nothing is in scope).
- **Orange** — a host you have actually contacted.
- **Grey** — a host that is only *referenced* in code and has **not been visited** — i.e. a
  potential target you haven't touched yet.
- **Gold ring** — the host is inside Burp's configured scope.
- Node size grows with the number of requests seen.

Hosts are grouped by **registrable domain (eTLD+1)** so related sub-domains cluster together
(e.g. `apply.aramco.jobs` and `www.aramco.jobs` share the `aramco.jobs` group; `careers.aramco.co.uk`
groups under `aramco.co.uk`).

## How the map is built

- **Seeded** from Burp's existing **Site Map** the moment the extension loads (and whenever you click
  *Rebuild from Site Map*).
- **Grows live** as you browse the target through Burp — a Montoya `HttpHandler` folds each new
  response into the graph in real time. Toggle this with the **Live capture** button.

## JSMReaper integration (how the overlay works)

MapReaper and JSMReaper stay **decoupled** — they do not call each other. JSMReaper writes its
findings into Burp's shared **issue store** (issues named `[JSMReaper] …`, or `[JS Miner] …` from the
original). When you click **Overlay JSMReaper findings**, MapReaper reads `siteMap().issues()`, keeps
the ones whose name contains `JSMReaper` or `JS Miner`, marks each issue's host with a red finding
badge, and parses the issue detail for referenced hosts to add to the map (so JSMReaper's
"Referenced External Hosts" / subdomain findings become nodes). Run a JSMReaper scan first so its
findings exist in Burp, then click the button.

## Using the tab

- **Drag a node** — move it and pin it in place (a small gold mark shows it is pinned).
- **Right-click a node** — menu: Expand/Collapse paths (hosts only), Send to Repeater, Send to
  Intruder, Active scan, Open in browser, Copy URL, Add host to scope, Remove from map.
- **Drag the background** — pan.
- **Mouse wheel** — zoom (keeps the point under the cursor fixed).
- **Click a node** — select it; its neighbours stay bright and everything else dims. The right-hand
  panel lists what it *depends on / links to*, what it is *reached from*, and sample URLs.
- **Double-click a node** — centre on it. **Double-click empty space** — reset the view.
- **Hover** — tooltip with host, domain, scheme, request count and link counts.

### Toolbar

- **Live capture** — grow the map from live traffic (on by default).
- **Rebuild from Site Map** — re-read everything already in Burp.
- **Clear** — empty the map.
- **Re-layout** — re-seed node positions and let the graph settle again.
- **Unpin all** — release every node you dragged.
- **Freeze** — stop the physics so nothing moves.
- **Export** — save the graph as **JSON** (nodes + edges) or the current view as **PNG**.
- **Show: In-scope only / Referenced (un-visited) hosts** — filter which nodes appear.
- **Links: Contact / Redirect / Reference / CORS** — toggle each edge type.
- **Find** — highlight and centre on hosts matching the text.

### Details panel actions

- **Copy host** — host to clipboard.
- **Add to scope** — add the selected host to Burp's target scope (http + https).
- **Remove** — drop the host from the map.

## Install

1. Burp → **Extensions → Add → Extension type: Java**.
2. Select **`MapReaper.jar`**.
3. Open the **MapReaper** tab. The Output log shows the load banner:
   `MapReaper v1.0 … part of the *Reaper Toolkit, created by John Gwilliams`.

Then either browse the target through Burp (live) or click **Rebuild from Site Map**.

> Tip: to populate the *referenced-but-not-visited* (grey, dashed) hosts, make sure the target's
> HTML/JS/CSS responses are in Burp's Site Map — browse the target through the proxy first. MapReaper
> parses those bodies for linked hosts.

## Build from source

No Gradle/Git required. With a JDK (17+) and the Montoya API jar on the classpath (compile-only —
Burp supplies it at runtime):

```
javac --release 17 -cp montoya-api-<version>.jar -d out <all .java under source/src>
jar cf MapReaper.jar -C out .
```

Montoya is **not** bundled in the jar (Burp provides it). The entry point is
`burp.reaper.MapReaperExtension` (implements `burp.api.montoya.BurpExtension`); Burp discovers it
automatically — no manifest `Main-Class` needed.

`source/test/Test.java` is an offline unit check of the dependency-free core (host parsing, eTLD+1
grouping, URL extraction, graph assembly). Compile it against `out` and run `Test`.

## Notes / limitations

- The registrable-domain logic uses a built-in common set of two-level public suffixes (`co.uk`,
  `com.au`, `co.jp`, …) rather than the full Public Suffix List, to avoid bundling a large data file.
  It is an approximation used only for grouping/colouring.
- The force layout is `O(n^2)` in the node count; above ~900 nodes it drops the pairwise repulsion
  so the UI stays responsive (edges still attract). Typical dependency graphs are well under that.
- `REFERENCE` edges only track **absolute** or protocol-relative URLs found in text responses
  (`https://host…`, `//host…`). Relative links stay on the same host and are not drawn cross-host.
- *Active scan* from the right-click menu starts a **new** Burp audit per click (adding that one
  request). For a sustained engagement, prefer adding the host to scope and using Burp's own scan
  queue. Active scanning is noisy and intrusive — only run it against targets you are authorised to test.
- The JSMReaper overlay matches issues by the name prefix `[JSMReaper]` / `[JS Miner]`. If you have
  renamed JSMReaper's issues, update the filter in `MapReaperTab.overlayFindings()`.
```
