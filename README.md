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
JDK 21 API drift) are merged. Every push to `master` runs the 328-test
JUnit suite under a headless Eclipse UI harness as a hard CI gate. The
current release is **10.0.2**.

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
  of the 107 open issues on the legacy
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
| **Quality gate** | 328 JUnit tests under a headless Eclipse harness, gating every push | Regressions get caught in CI instead of by you mid-presentation |
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
| **10.0.1 maintenance** | Actor stickman now copies / exports with the correct shape (a draw2d scaled-graphics cache bug had dropped its body and one leg in clipboard / bitmap output); the **UCM Scenario Traversal Algorithm** and **GRL Strategy Evaluation Algorithm** pages are back in the jUCMNav Preferences dialog; more post-dispose crash guards across command-stack and property-sheet refreshes; Z.151 belief author / size and the disabled command-stack + Z.151 round-trip tests are now covered by the suite | Paste or export a diagram containing an Actor and the stickman looks right; reach the traversal settings (max-hit-count loop guard, etc.) and the GRL evaluation settings from the jUCMNav Preferences button again; fewer "widget is disposed" crashes during ordinary drag / undo / save |
| **10.0.2 — OR-fork branch probabilities** | The Condition Editor (double-click an OR-fork) now edits each branch's **probability** alongside its condition, backed by undo/redo, with a warning when the branch probabilities don't sum to 1.0; the same sum check is available on a dynamic stub's plug-in probabilities. Both checks stay hidden unless probabilities are actually in use (every branch defaults to 1.0). **Static slicing** now runs off the UI thread under a cancellable progress dialog instead of synchronously on the UI thread. The JUnit **test suite was migrated from JUnit 3 to JUnit 4** (POJO test classes + a shared `JUCMNavTestFixture`). Also: a post-dispose crash guard for the outline page when closing an editor tab | Set and review branch probabilities without digging into the Advanced properties tab, and get told when they don't add up — without nagging the majority of models that don't use probabilities at all; slicing a large model no longer freezes the workbench (and you can cancel it); contributors get the cleaner, modern test style |

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
