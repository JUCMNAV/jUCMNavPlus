# jUCMNav

[![Build and publish update site](https://github.com/JUCMNAV/jUCMNavPlus/actions/workflows/build-and-deploy.yml/badge.svg?branch=master)](https://github.com/JUCMNAV/jUCMNavPlus/actions/workflows/build-and-deploy.yml)

Eclipse plug-in for the **User Requirements Notation (URN)** — a graphical
editor and analysis tool combining **Use Case Maps (UCM)** and the
**Goal-oriented Requirements Language (GRL)**. Built on EMF (model), GEF
Classic (diagram editors), and MDT/OCL (constraints).

<img width="1917" height="1008" alt="jUCMNav" src="https://github.com/user-attachments/assets/dd26fec9-23ad-486a-a10e-f8c323f09161" />

## Status

**Modernization complete and shipping from `master`.** jUCMNav now builds,
tests, and runs on Java 21 LTS + Eclipse 2026-03 (4.39). The full Phase A
(compile-clean, Tycho build, p2 update site) and Phase B (QA bug hunt:
SWT leaks, dispose races, thread-affinity issues, GEF generics fallout,
JDK 21 API drift) are merged. Every push to `master` runs the 466-test
JUnit suite under a headless Eclipse UI harness as a hard CI gate. The
current release is **10.0.8**.

The installable update site is published continuously to GitHub Pages at
[`https://jucmnav.github.io/jUCMNavPlus/`](https://jucmnav.github.io/jUCMNavPlus/) —
see [Install](#install) below. The repository lives in the `JUCMNAV/`
organization for continuity with the historical project.

**Reference docs**
- [CLAUDE.md](CLAUDE.md) — orientation for contributors (and AI tools).
  Read first.
- [MIGRATION_ERRORS.md](MIGRATION_ERRORS.md) — Phase A burn-down of every
  compile error and how it was resolved.
- [QA_FINDINGS.md](QA_FINDINGS.md) — Phase B static bug-hunt report
  (110 candidates, 78 verified findings across 7 categories).
- [docs/legacy-issue-triage.md](docs/legacy-issue-triage.md) — classification
  of the open issues on the legacy
  [`JUCMNAV/projetseg-update`](https://github.com/JUCMNAV/projetseg-update)
  repo, with notes on which are likely fixed by the modernization.

## What's new

Quick tour of the changes since the modernization started, focused on what
they mean in practice when you actually use jUCMNav. For the full commit
trail, see [`git log master`](https://github.com/JUCMNAV/jUCMNavPlus/commits/master).

| Area | Change | What it means for you |
|---|---|---|
| **Platform** | Java 21 LTS + Eclipse 2026-03 (4.39), built with Tycho 5.0.3 | Runs on current machines; "won't install" against modern Eclipse is gone |
| **CORE library** | The `ca.mcgill.sel.core` dependency is now vendored in-tree | Builds don't depend on an external university Maven host that could disappear; everything you need to compile and run is in this repo |
| **EMF model** | The URN / UCM / GRL / FM model code was regenerated from the `.ecore` / `.genmodel` sources using current EMF tooling | Model loading, serialization, and validation run on supported APIs (no JDK-removed methods); future model changes can be regenerated cleanly instead of hand-patched |
| **Distribution** | One-click p2 update site from GitHub Pages | Paste `https://jucmnav.github.io/jUCMNavPlus/` into Eclipse's Install New Software and you're done — automatic updates via Help → Check for Updates |
| **Quality gate** | 466 JUnit tests under a headless Eclipse harness, gating every push | Regressions get caught in CI instead of by you mid-presentation |
| **CI / artifacts** | GitHub Actions builds + tests + publishes the update site on every push to `master`; downloadable site artifact on every PR build | You can install a feature-branch / PR build locally before it's merged or published — no waiting on a release cycle |
| **Project home** | Repo lives in the `JUCMNAV/` organization for continuity and multi-maintainer support | Install URL is `jucmnav.github.io/jUCMNavPlus/`; the historical `damyot/jUCMNavPlus` URL auto-redirects but the old Pages host does not — update your Eclipse update site list |
| **HTML report — modern rendering** | Replaced the 2008-era frameset + browser-side XSLT + jQuery pipeline with a self-contained `index.html` (flexbox sidebar + content iframe) and full diagram names sorted alphabetically | Reports open correctly in current Chrome / Edge / Firefox — the old XSLT silently failed on `file://` and Chrome announced removal of `XSLTProcessor` in 2024. Names like "GRL-Adequate Follow-up" stay intact instead of getting truncated to "up", and the sidebar reads top-to-bottom in a predictable order |
| **HTML report — model-faithful navigation** | Sidebar follows real model structure (Map → Stub → bound submap, recursively, with cycle handling), shows static vs dynamic stubs with distinct icons, and a single failing diagram no longer aborts the whole report | You navigate the report the way the model is actually shaped instead of as one flat list, can tell at a glance which stubs are dynamic, and a complex 56-diagram model with one bad figure produces 55 good pages plus a named log entry — not a half-finished folder with no index |
| **Z.151 import / export** | The Z.151 standard interchange format round-trips correctly: GRL `ref` relationships are preserved on export, and optional style elements are tolerated on import | Models exchanged with other Z.151-compliant URN tools (or the URN reference implementation) re-load with GRL contributions / dependencies intact, and partial / older Z.151 files no longer break import on a missing optional element |
| **PDF / RTF reports** | SWT `Transform` and `SWTGraphics` resources disposed on every page | Long export runs no longer exhaust GDI handles or crash with "no more handles" mid-document |
| **Reports — date format** | `urn.getCreated()` / `getModified()` parse against the locale-specific JDK-21 LONG format | Generation no longer dies with `Unparseable date` on any locale where the format changed between JDK 8 and JDK 21 |
| **Reports — UI threading** | Image export wrapped in `Display.syncExec`; error dialog threads through workbench shell | "Invalid thread access" during HTML / PDF report generation is gone |
| **Editor — Save As** | Auto-appends `.jucm` if you forget it; pinned reopen editor id | Typing `model` saves as `model.jucm`. No more silent reopen in the text editor followed by a confusing `IllegalStateException` |
| **Editor — undo** | `PathNodeEditPart.notifyChanged` guards against null viewer / disposed control | Complex undo across `SplitLinkCommand` and similar structural commands no longer NPEs |
| **Editor — close** | Comment and path-node editparts no longer hit a disposed shared draw2d GC during the dispose cascade | Closing a dirty editor or deleting a populated map runs cleanly, without "Graphic is disposed" dialogs |
| **Diagrams — antialiasing** | Off-screen GCs enable GDI+ before painting | Copied / exported diagrams render the same antialiased curves as on-screen, not pixelated approximations |
| **Diagrams — label scaling** | GRL evaluation labels, KPI labels, change markers, actor stickman painted via the scaled primary layer | Decorations stay attached to their elements and shrink / grow correctly with zoom |
| **MSC scenario viewer — paint and fonts** | Default font now seeded from the platform system font (was empty-string + `SWT.CANCEL` style); Set Font dialog input validated and old fonts no longer disposed while figures still reference them; a propagated refresh applies the new font to live figures | The viewer actually opens (every paint used to throw `IllegalArgumentException` from `GC.setFont`), and changing the MSC font via Set Font now takes effect immediately on the live scenario instead of crashing the next paint |
| **MSC scenario viewer — image export (legacy #545)** | Brand-new export wizard supports multiple-scenario selection (Select All / Deselect All), per-scenario file naming into a chosen directory, zoom factor (25 % – 400 %) driven through the live `ZoomManager`, and a cancellable progress dialog with the worker doing the PNG / BMP / JPEG encode off the UI thread | Export N scenarios in one pass at the resolution you want without freezing the workbench; cancel mid-run safely |
| **MSC scenario viewer — Copy / Export from canvas** | Ctrl+C and right-click "Copy" put the current scenario diagram on the system clipboard via SWT `ImageTransfer`; right-click "Export to Image…" opens the export wizard pre-targeted at the active model | Paste the current MSC scenario directly into chat / docs / Paint; reach the export wizard without navigating File → Export |
| **Add Stereotype Definitions** | Icon resolved against both classloader and bundle-root paths | Menu item shows the correct icon instead of a red-square missing-image placeholder |
| **Performance — static slicing** | Cached regex `Pattern` + `LinkedHashSet` for dedup in `Parsing.getVariables` | Slicing large GRL models is meaningfully faster (was hot enough to look hung) |
| **Resource hygiene** | Per-instance `Color` / `Font` / `Image` allocations routed through `ColorManager` cache and `JFaceResources` registries | No SWT-resource warnings in the Error Log during a long modeling session |
| **10.0.1 maintenance** | Actor stickman shape fixed in clipboard / bitmap output (draw2d scaled-graphics cache bug); the traversal and GRL-evaluation preference pages restored to the jUCMNav Preferences dialog; more post-dispose crash guards; Z.151 belief round-trip and six previously disabled tests added to the suite | Exported Actors look right, the traversal settings (max-hit-count loop guard) are reachable again, and fewer "widget is disposed" crashes during ordinary drag / undo / save |
| **10.0.2 — OR-fork branch probabilities** | The Condition Editor (double-click an OR-fork) edits each branch's probability with undo/redo, warning when they don't sum to 1.0 — same check for dynamic-stub plug-ins, and both stay hidden unless probabilities are actually in use; static slicing moved off the UI thread behind a cancellable dialog; test suite migrated to JUnit 4 | Set branch probabilities without digging into the Advanced tab and get told when they don't add up, without nagging models that don't use them; slicing a large model no longer freezes the workbench |
| **10.0.3 — crash & menu fixes** | Selecting a start/end point with no condition no longer throws (operator-precedence bug in `AddConditionLabelAction`), and action enablement is now refreshed per-action so one failure can't blank a whole context menu; the dynamic-stub condition wizard opens on top of its modal dialog and unchecking a plug-in no longer NPEs; disposed-control guards across the Elements, KPI, Strategies and Dynamic Contexts views | Clicking an end point, closing the workbench, or editing a stub condition no longer fills the error log — and menu items that quietly disappeared, like "Run All Scenarios", are back |
| **10.0.4 — AND-fork loop crash** | Routing a path that loops back through an AND-fork or AND-join no longer throws `StackOverflowError` — the connection router's spline walk now tracks which splines it has already expanded (a bug open since 2015 as legacy #930) | Draw a loop through an AND-fork, which is legal UCM, and the diagram keeps working instead of filling the error log and becoming unusable |
| **10.0.5 — large-model performance** | Three quadratic hot spots removed: same-document ID references now resolve through an id map instead of a full model walk per reference; enumeration values are indexed instead of scanned on every variable reference; expression syntax trees are cached instead of re-parsed on every evaluation. Plus: multi-line responsibility and stub names no longer truncate to their first line in the outline and list views, and a fly-out submenu is no longer disposed while Windows still tracks it | A 3.7 MB generated model (30 maps, 1155 scenarios) opens in 10 s instead of 167 s, and running all 1155 scenario definitions takes 28 s instead of not finishing at all |
| **10.0.6 — undo & menu correctness** | Refactor into Stub could never be undone at all: it nests helper commands that are empty when they have no work, and GEF treats an empty compound as un-undoable, which silently blocked the whole refactor — nine further commands carried the same latent flaw. The command stack also advertised an Undo it could not perform, so the action stayed enabled and every press did nothing. Plus: a keyboard shortcut back to the selection tool, the URN Links menu no longer breaks when a link's target is deleted, blank names report as missing rather than "already exists", and two hand-built pop-up menus stop leaking | Undo actually undoes a Refactor into Stub, and greys out honestly when it cannot; pressing Undo repeatedly to no effect is gone |
| **10.0.7 — Refactor into Stub, rebuilt** | The command now computes what it extracts and constructs the result, instead of deleting the selection and assembling a stub from the severed ends left behind. See [below](#refactor-into-stub-rebuilt) | Select a region, get a stub with exactly one path per boundary crossing, a plug-in map that still means what the region meant, and scenarios that run identically before and after |

| **10.0.8 — auto-layout, rebuilt** | UCM maps are now laid out by a layered swim-lane algorithm that needs no Graphviz at all: components get disjoint horizontal bands, so they cannot overlap by construction, and each node sits at the average height of its neighbours, which is what keeps paths smooth and crossings down. Graphviz remains selectable. See [below](#auto-layout-rebuilt) | Press Auto-layout and get a readable left-to-right map without installing anything; laying out a whole model is a single undo |

### Auto-layout, rebuilt

Auto-layout had silently done nothing on any Graphviz released since about 2015 — it
scraped `-Tdot` with regular expressions pinned to the 2011 output format, found no
match, and positioned nothing. Fixing that exposed the real problem: a UCM path is
drawn as a spline through its nodes, so its shape depends on their spacing and turn
angles, and a layered graph layout reasons about neither.

**What it does now.** Layer assignment by longest path gives the x axis. Every
top-level component gets its own horizontal band, and a nested component's band sits
inside its parent's — so two component rectangles *cannot* intersect, whatever the
nodes do, and containment holds geometrically rather than by repair. Each node then
takes the average height of its neighbours, clamped to its band, which is
simultaneously the standard crossing-reduction heuristic and a smoothing operator.
Bands that never compete for horizontal space share one row, which keeps the drawing
wide and flat instead of growing a band per component.

**Graphviz is optional.** The layered layout runs entirely in-process. Graphviz stays
selectable in the wizard and preferences for UCM maps; GRL graphs and feature diagrams
still use it, and the wizard says so rather than leaving you to find out.

**Also in this release.** Laying out a whole model is one undo instead of one per
diagram. A laid-out diagram no longer opens with a screenful of empty space before the
model — a container reserved room equal to its *current* on-screen size, so an actor
dragged out to 1669px became a 23-inch invisible node and the drawing was sized around
it. Three settings that did nothing were removed.

The design record, including how the quality measure came to rate an unreadable drawing
43% better than a good one, is in
[`docs/auto-layout-objective.md`](docs/auto-layout-objective.md) and on
[issue #30](https://github.com/JUCMNAV/jUCMNavPlus/issues/30).

### Refactor into Stub, rebuilt

Select part of a map, extract it into a stub, and the plug-in map should mean
exactly what the selection meant. It didn't. The old command deleted the
selected nodes, let deletion incidentally spawn start and end points wherever
it had severed a path, then swept the map for "every start/end point newer than
me" and attached whatever it found to the stub. It could not tell an end it had
severed *inside* the selection from one on the boundary, so every interior
severing produced a surplus stub path — and since the two sides of the
transformation were built by unrelated mechanisms, the stub-to-plug-in bindings
had to be reconstructed afterwards by matching element names, with a positional
fallback for when the names didn't line up.

It now computes what it extracts, and constructs the result:

```
scope = the selection, plus everything lying between two selected nodes
in    = connections entering the scope        out = connections leaving it
```

One stub in-path per inbound connection, one out-path per outbound one, each
paired with the plug-in endpoint that continues it — **by construction**, so
surplus paths cannot arise and the bindings are known rather than guessed.

| Selection | Before | Now |
|---|---|---|
| A whole OR-fork/join block | stub with 3 in-paths and 3 out-paths | **1 and 1** |
| Just the fork and the join | extracted 2 nodes, plug-in map lost its meaning | **extracts the whole block, 1 and 1** |
| Anything reaching the start point | stub with **no** way in, and a root map with no start point at all | **the start point stays put and feeds the stub** |

Nodes are **moved, not copied**, so ids, responsibility definitions, metadata
and history survive. Components come across too: one whose nodes all leave
moves with them, and one straddling the boundary is *replicated* on the plug-in
map, because a component definition is meant to have a reference per map it
appears on. Start and end points never move — a start point is the map's way
in, and a stub replaces a body, not an entry. An OR-fork's branch guards travel
with the fork that reads them.

That last pair are not cosmetic. Extracting a start point used to strand any
scenario anchored on it: the traversal began *inside* the plug-in map, which it
had never entered through a stub, so on reaching the far end there was nothing
to return from and everything downstream went unvisited. A guard left behind
left the moved fork with branches that all evaluate true, and the traversal
reported "multiple alternatives — taking first option to remain deterministic"
and quietly ran a different scenario. Both passed every structural check.

Which is why the command is now judged by what a model is *for*. A round-trip
suite extracts a stub, re-runs every scenario definition, undoes, and runs them
again, requiring that the same responsibilities execute the same number of
times with the same warnings at each step — across nine selections on a
process-mined model with true concurrency, a counted loop, an enumerated
variant selector and five components. Underneath it, the scope calculation is
checked exhaustively against all 2,048 subsets of a sample map.

Undo works throughout, and a Refactor into Stub now survives an edit on a map
it never touched instead of being discarded by it.

## Install

Add this URL to Eclipse → Help → Install New Software… → Add → Location:

```
https://jucmnav.github.io/jUCMNavPlus/
```

Then select **jUCMNav** under "URN: UCM + GRL" and finish the wizard.
Subsequent updates come via Help → Check for Updates.

**To install a PR / feature-branch build that hasn't been published to
Pages yet:** download the `jucmnav-update-site` artifact from the relevant
[workflow run](https://github.com/JUCMNAV/jUCMNavPlus/actions), unzip it
locally, and point Install New Software at the unzipped folder via the
**Local…** button.

## Build

```bash
mvn -B clean verify
```

The installable p2 update site lands at
`seg.jUCMNav.repository/target/repository`. Requires JDK 21 (Adoptium /
Temurin recommended).

`verify` also runs the JUnit suite under `seg.jUCMNav.tests/` inside a
headless Eclipse UI harness. On Linux this needs a display — CI wraps
`mvn` in `xvfb-run`. Locally, pass `-DskipTests` to skip tests if you
only want the update site.

## Test

Three ways to run the suite:

- **Headless / CI parity:** `mvn -B clean verify` from the repo root.
  Tests fail the build. ~3 minutes total wall-clock.
- **Single suite from the CLI:** `mvn -B verify -pl seg.jUCMNav.tests -am
  -Dtest=JUCMNavCommandTests`. Useful for iterating on one failure
  without paying the full reactor cost.
- **From inside Eclipse:** import `seg.jUCMNav.tests` as an existing
  project, then right-click any test class (or the `src/` folder) →
  **Run As → JUnit Plug-in Test**. Eclipse boots a runtime workbench and
  reports results in the JUnit view.

Six tests are intentionally disabled (`disabled_test*` prefix). See
[issue #6](https://github.com/JUCMNAV/jUCMNavPlus/issues/6) and
[issue #7](https://github.com/JUCMNAV/jUCMNavPlus/issues/7) for the
context and re-enable plan.

On Windows, if `mvn` fails with a PKIX TLS-handshake error fetching from
Maven Central, drop the following two lines into `.mvn/jvm.config`
(gitignored — local override only) to use the Windows certificate store:

```
-Djavax.net.ssl.trustStoreType=Windows-ROOT
-Djavax.net.ssl.trustStore=NUL
```

## Develop in the IDE

In Eclipse → File → Import → Existing Projects into Workspace → root this
repository. Set the target platform via
`seg.jUCMNav.target/seg.jUCMNav.target` (open it, click "Set as Active
Target Platform"). Run As → Eclipse Application launches a child workbench
with the plug-in.

## Historical project

This repository is the modernized successor to
[`JUCMNAV/projetseg-update`](https://github.com/JUCMNAV/projetseg-update),
which holds the pre-modernization codebase and the legacy issue/wiki
archive (943 issues, 107 still open at the time of transfer). The wiki
content has been imported into [this repository's
wiki](https://github.com/JUCMNAV/jUCMNavPlus/wiki); the legacy issue
archive remains on `projetseg-update` for reference, and surviving bugs
are being transferred selectively — see
[`docs/legacy-issue-triage.md`](docs/legacy-issue-triage.md).

## License

See the in-repo headers and [seg.jUCMNav/about.html](seg.jUCMNav/about.html).
