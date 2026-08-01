# Legacy issue triage (open issues on `JUCMNAV/projetseg-update`)

Snapshot of 94 open issues, taken 2026-08-01.
Source: <https://github.com/JUCMNAV/projetseg-update/issues?q=is%3Aissue+is%3Aopen>

> Regenerate with `triage.ps1`, which reads `legacy-issues-raw.json`. Refresh that dump first --
> the script does not fetch it:
>
> ```
> gh api "repos/JUCMNAV/projetseg-update/issues?state=open&per_page=100" --paginate \
>   --jq '.[] | select(.pull_request == null)'
> ```
>
> A stale dump reads as a live backlog and wastes triage time on issues that are already closed.

## Triage summary

- Total open: **94**
- Flagged as possibly already fixed by `modernization`: **1** (need verification before opening fresh issues)

### By area

| Area | Open |
|---|---|
| GRL/KPI | 27 |
| Other / uncategorized | 15 |
| Scenarios | 11 |
| UCM stubs / bindings | 10 |
| UI / actions / palette | 6 |
| Import / export (Z.151/TDL) | 4 |
| MSC scenario viewer | 3 |
| Rendering / GEF | 3 |
| Views / properties | 3 |
| Concerns / AoURN | 3 |
| Save / load / persistence | 2 |
| PDF/RTF reports | 2 |
| Docs | 2 |
| Clipboard / copy | 1 |
| Feature model | 1 |
| Metadata / stereotypes | 1 |

### By disposition (heuristic)

| Disposition | Open |
|---|---|
| Needs review | 77 |
| Enhancement (defer) | 9 |
| Bug - needs repro | 5 |
| Question / docs | 3 |

## "Possibly fixed by modernization" - verify first

These issues match keywords from areas the modernization branch already touched. Spot-check on the latest `master` before deciding to re-file or close as fixed.

| # | Area | Title | Likely fixed by | Created |
|---|---|---|---|---|
| [#629](https://github.com/JUCMNAV/projetseg-update/issues/629) | MSC scenario viewer | MSC of start point with condition created incorrectly (Bugzilla Bug 629) | MSC viewer font / paint crash | 2021-08-03 |

## Full list by area

### Clipboard / copy (1)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#365](https://github.com/JUCMNAV/projetseg-update/issues/365) | Bug - needs repro | refactor: seg.jUCMNav.figures.util (Bugzilla Bug 365) | 2021-08-03 |

### Concerns / AoURN (3)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#916](https://github.com/JUCMNAV/projetseg-update/issues/916) | Needs review | 6. Support for new Concern-Oriented Metamodel (Bugzilla Bug 916) | 2021-08-03 |
| [#915](https://github.com/JUCMNAV/projetseg-update/issues/915) | Needs review | 5. Transform feature model to concern-oriented design model (Bugzilla Bug 915) | 2021-08-03 |
| [#323](https://github.com/JUCMNAV/projetseg-update/issues/323) | Needs review | asynch connect: improve figure position (Bugzilla Bug 323) | 2021-08-03 |

### Docs (2)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#914](https://github.com/JUCMNAV/projetseg-update/issues/914) | Question / docs | Documentation on the jUCMNav TWiki (Bugzilla Bug 914) | 2021-08-03 |
| [#361](https://github.com/JUCMNAV/projetseg-update/issues/361) | Question / docs | refactor: clean rotation code (Bugzilla Bug 361) | 2021-08-03 |

### Feature model (1)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#935](https://github.com/JUCMNAV/projetseg-update/issues/935) | Needs review | Double click toggle of mandatory/optional link on feature diagram (Bugzilla Bug 935) | 2021-08-03 |

### GRL/KPI (27)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#939](https://github.com/JUCMNAV/projetseg-update/issues/939) | Needs review | Feature Models should be supported in reports (Bugzilla Bug 939) | 2021-08-03 |
| [#934](https://github.com/JUCMNAV/projetseg-update/issues/934) | Needs review | Show non-leaf diagrams in separate diagram creates GRL and not Feature children (Bugzilla Bug 934) | 2021-08-03 |
| [#931](https://github.com/JUCMNAV/projetseg-update/issues/931) | Needs review | URN needs Agents,Roles and Positions in GRL (Bugzilla Bug 931) | 2021-08-03 |
| [#920](https://github.com/JUCMNAV/projetseg-update/issues/920) | Needs review | ShowLinkedElementCommand does not support Beliefs in GRL (Bugzilla Bug 920) | 2021-08-03 |
| [#909](https://github.com/JUCMNAV/projetseg-update/issues/909) | Needs review | problem with dependency evaluation (Bugzilla Bug 909) | 2021-08-03 |
| [#908](https://github.com/JUCMNAV/projetseg-update/issues/908) | Needs review | Trends depend on order of strategies (Bugzilla Bug 908) | 2021-08-03 |
| [#873](https://github.com/JUCMNAV/projetseg-update/issues/873) | Needs review | Refactor EvaluationStrategyManager (Bugzilla Bug 873) | 2021-08-03 |
| [#869](https://github.com/JUCMNAV/projetseg-update/issues/869) | Enhancement (defer) | Paste at the cursor (GRL) (Bugzilla Bug 869) | 2021-08-03 |
| [#867](https://github.com/JUCMNAV/projetseg-update/issues/867) | Needs review | Outline pane sorting after changing type (Bugzilla Bug 867) | 2021-08-03 |
| [#866](https://github.com/JUCMNAV/projetseg-update/issues/866) | Enhancement (defer) | Source / Destination Icons in the Outline Pane (Bugzilla Bug 866) | 2021-08-03 |
| [#865](https://github.com/JUCMNAV/projetseg-update/issues/865) | Needs review | URN Links in the context menu stops working (Bugzilla Bug 865) | 2021-08-03 |
| [#864](https://github.com/JUCMNAV/projetseg-update/issues/864) | Enhancement (defer) | Bulk Copy of Elements from Navigation Pane (GRL) (Bugzilla Bug 864) | 2021-08-03 |
| [#863](https://github.com/JUCMNAV/projetseg-update/issues/863) | Bug - needs repro | Save Error (Bugzilla Bug 863) | 2021-08-03 |
| [#861](https://github.com/JUCMNAV/projetseg-update/issues/861) | Enhancement (defer) | It's not possible to manually resize dimensions  (Bugzilla Bug 861) | 2021-08-03 |
| [#859](https://github.com/JUCMNAV/projetseg-update/issues/859) | Needs review | Copying and Pasting Changes the coordinates of labels  (Bugzilla Bug 859) | 2021-08-03 |
| [#858](https://github.com/JUCMNAV/projetseg-update/issues/858) | Needs review | GRL quantitative algorithms (family) should support "exceeds" and new KPI conversions (Bugzilla Bug 858) | 2021-08-03 |
| [#848](https://github.com/JUCMNAV/projetseg-update/issues/848) | Needs review | Elimination of delay between clicking on an Item and its selection (Bugzilla Bug 848) | 2021-08-03 |
| [#841](https://github.com/JUCMNAV/projetseg-update/issues/841) | Needs review | Selecting Items on a large graph at low view scale (Bugzilla Bug 841) | 2021-08-03 |
| [#828](https://github.com/JUCMNAV/projetseg-update/issues/828) | Needs review | Support (or prevent) the 0..100 scale in the Constraint-based GRL algorithm (Bugzilla Bug 828) | 2021-08-03 |
| [#827](https://github.com/JUCMNAV/projetseg-update/issues/827) | Needs review | Support (or prevent) the 0..100 scale in the Qualitative GRL algorithm (Bugzilla Bug 827) | 2021-08-03 |
| [#784](https://github.com/JUCMNAV/projetseg-update/issues/784) | Bug - needs repro | Crash on Mac OS X Lion (Bugzilla Bug 784) | 2021-08-03 |
| [#761](https://github.com/JUCMNAV/projetseg-update/issues/761) | Needs review | GRL propagation does not handle dependency loops correctly (Bugzilla Bug 761) | 2021-08-03 |
| [#719](https://github.com/JUCMNAV/projetseg-update/issues/719) | Needs review | GRL intentional element: Show Containing Actor (Bugzilla Bug 719) | 2021-08-03 |
| [#676](https://github.com/JUCMNAV/projetseg-update/issues/676) | Needs review | GRL evaluations of dependencies with dependum or initial values (Bugzilla Bug 676) | 2021-08-03 |
| [#661](https://github.com/JUCMNAV/projetseg-update/issues/661) | Needs review | GRL actor collapsing/expanding (Bugzilla Bug 661) | 2021-08-03 |
| [#633](https://github.com/JUCMNAV/projetseg-update/issues/633) | Needs review | Incorrect handling of dependency in GRL qualitative algorithm (Bugzilla Bug 633) | 2021-08-03 |
| [#424](https://github.com/JUCMNAV/projetseg-update/issues/424) | Needs review | problem with automatically adding links between nodes (Bugzilla Bug 424) | 2021-08-03 |

### Import / export (Z.151/TDL) (4)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#832](https://github.com/JUCMNAV/projetseg-update/issues/832) | Needs review | Hotkeys conflict with Direct Edit (Bugzilla Bug 832) | 2021-08-03 |
| [#674](https://github.com/JUCMNAV/projetseg-update/issues/674) | Needs review | Verify compliance of jUCMNav's data model with Z.151's (Bugzilla Bug 674) | 2021-08-03 |
| [#669](https://github.com/JUCMNAV/projetseg-update/issues/669) | Needs review | UCM ComponentType should be supported (Bugzilla Bug 669) | 2021-08-03 |
| [#422](https://github.com/JUCMNAV/projetseg-update/issues/422) | Needs review | ExportDXL: CompRefs have no descriptions, while they should include that of their respective definitions (Bugzilla Bug 422) | 2021-08-03 |

### Metadata / stereotypes (1)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#645](https://github.com/JUCMNAV/projetseg-update/issues/645) | Question / docs | Link online documentation and Eclipse "?" icon (Bugzilla Bug 645) | 2021-08-03 |

### MSC scenario viewer (3)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#900](https://github.com/JUCMNAV/projetseg-update/issues/900) | Bug - needs repro | MSC viewer can't open files that are linked in an Eclipse folder (Bugzilla Bug 900) | 2021-08-03 |
| [#629](https://github.com/JUCMNAV/projetseg-update/issues/629) | Needs review [?] | MSC of start point with condition created incorrectly (Bugzilla Bug 629) | 2021-08-03 |
| [#547](https://github.com/JUCMNAV/projetseg-update/issues/547) | Needs review | scenarios: limitation with triggering paths (Bugzilla Bug 547) | 2021-08-03 |

### Other / uncategorized (15)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#938](https://github.com/JUCMNAV/projetseg-update/issues/938) | Needs review | "Show Bound Intentional Elements" does not work (Bugzilla Bug 938) | 2021-08-03 |
| [#921](https://github.com/JUCMNAV/projetseg-update/issues/921) | Needs review | Use of OCL requires a Restore Default Settings after installing jUCMNav (Bugzilla Bug 921) | 2021-08-03 |
| [#917](https://github.com/JUCMNAV/projetseg-update/issues/917) | Needs review | Support merge function to merger two feature model diagram,. Basicly merger two tasks with their children (Bugzilla Bug 917) | 2021-08-03 |
| [#913](https://github.com/JUCMNAV/projetseg-update/issues/913) | Needs review | Advanced Mode in jUCMNav for Feature Modeling (Bugzilla Bug 913) | 2021-08-03 |
| [#912](https://github.com/JUCMNAV/projetseg-update/issues/912) | Needs review | 2. Support Object Constraint Language (OCL) in feature modeling. (Bugzilla Bug 912) | 2021-08-03 |
| [#897](https://github.com/JUCMNAV/projetseg-update/issues/897) | Needs review | Report on OCL constraints (Bugzilla Bug 897) | 2021-08-03 |
| [#860](https://github.com/JUCMNAV/projetseg-update/issues/860) | Enhancement (defer) | Multiple line name for Actors (Bugzilla Bug 860) | 2021-08-03 |
| [#847](https://github.com/JUCMNAV/projetseg-update/issues/847) | Needs review | Moving elements with labels (UCM) (Bugzilla Bug 847) | 2021-08-03 |
| [#766](https://github.com/JUCMNAV/projetseg-update/issues/766) | Needs review | comment should be linked to individual element, not diagram (Bugzilla Bug 766) | 2021-08-03 |
| [#734](https://github.com/JUCMNAV/projetseg-update/issues/734) | Needs review | User should be able to set the default contribution level as a preference (Bugzilla Bug 734) | 2021-08-03 |
| [#635](https://github.com/JUCMNAV/projetseg-update/issues/635) | Needs review | MacOS refresh problem while drawing/moving components (Bugzilla Bug 635) | 2021-08-03 |
| [#517](https://github.com/JUCMNAV/projetseg-update/issues/517) | Needs review | refactor: DelegatingCommandStack executer/redo/undo (Bugzilla Bug 517) | 2021-08-03 |
| [#367](https://github.com/JUCMNAV/projetseg-update/issues/367) | Needs review | refactor: query infrastructure (Bugzilla Bug 367) | 2021-08-03 |
| [#267](https://github.com/JUCMNAV/projetseg-update/issues/267) | Enhancement (defer) | new element position (fork/join) enhancements (Bugzilla Bug 267) | 2021-08-03 |
| [#264](https://github.com/JUCMNAV/projetseg-update/issues/264) | Needs review | ParentFinder getPossibleParents bug (Bugzilla Bug 264) | 2021-08-03 |

### PDF/RTF reports (2)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#929](https://github.com/JUCMNAV/projetseg-update/issues/929) | Needs review | Reporting does not include meta data or user defined links (Bugzilla Bug 929) | 2021-08-03 |
| [#922](https://github.com/JUCMNAV/projetseg-update/issues/922) | Needs review | PDF reports: strategies not sorted for trends (Bugzilla Bug 922) | 2021-08-03 |

### Rendering / GEF (3)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#842](https://github.com/JUCMNAV/projetseg-update/issues/842) | Needs review | Dragging and Dropping more than one intention from outline bar (Bugzilla Bug 842) | 2021-08-03 |
| [#374](https://github.com/JUCMNAV/projetseg-update/issues/374) | Needs review | refactor & improve: CutPathCommand (Bugzilla Bug 374) | 2021-08-03 |
| [#265](https://github.com/JUCMNAV/projetseg-update/issues/265) | Enhancement (defer) | add/delete map command stack management (Bugzilla Bug 265) | 2021-08-03 |

### Save / load / persistence (2)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#750](https://github.com/JUCMNAV/projetseg-update/issues/750) | Needs review | Handle new concern relationships in Z.151 export/import (Bugzilla Bug 750) | 2021-08-03 |
| [#634](https://github.com/JUCMNAV/projetseg-update/issues/634) | Needs review | Autolayout problems on MacOS (Bugzilla Bug 634) | 2021-08-03 |

### Scenarios (11)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#940](https://github.com/JUCMNAV/projetseg-update/issues/940) | Needs review | GRL strategies: initialization problems when included strategies are expanded (Bugzilla Bug 940) | 2021-08-03 |
| [#927](https://github.com/JUCMNAV/projetseg-update/issues/927) | Needs review | UCM execution path not "lighting up" (Bugzilla Bug 927) | 2021-08-03 |
| [#852](https://github.com/JUCMNAV/projetseg-update/issues/852) | Needs review | Support the integration of jUCMNav with Cognos 10 (Bugzilla Bug 852) | 2021-08-03 |
| [#824](https://github.com/JUCMNAV/projetseg-update/issues/824) | Needs review | export overwrites existing file without warning (Bugzilla Bug 824) | 2021-08-03 |
| [#672](https://github.com/JUCMNAV/projetseg-update/issues/672) | Needs review | UCM waiting kinds should be supported for waiting places and timers (Bugzilla Bug 672) | 2021-08-03 |
| [#671](https://github.com/JUCMNAV/projetseg-update/issues/671) | Needs review | UCM singleton maps not handled (Bugzilla Bug 671) | 2021-08-03 |
| [#659](https://github.com/JUCMNAV/projetseg-update/issues/659) | Needs review | MacOS user interface bugs (Bugzilla Bug 659) | 2021-08-03 |
| [#560](https://github.com/JUCMNAV/projetseg-update/issues/560) | Needs review | Need to refactor access to Problems view (Bugzilla Bug 560) | 2021-08-03 |
| [#518](https://github.com/JUCMNAV/projetseg-update/issues/518) | Needs review | scenarios: create extension point(s) for scenario traversal (Bugzilla Bug 518) | 2021-08-03 |
| [#493](https://github.com/JUCMNAV/projetseg-update/issues/493) | Needs review | scenarios: plugin selection window refinement (Bugzilla Bug 493) | 2021-08-03 |
| [#487](https://github.com/JUCMNAV/projetseg-update/issues/487) | Enhancement (defer) | scenarios: add intellisense in code-editor (Bugzilla Bug 487) | 2021-08-03 |

### UCM stubs / bindings (10)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#924](https://github.com/JUCMNAV/projetseg-update/issues/924) | Needs review | Additional paths are generated when refactor Responsibilities into a stub (Bugzilla Bug 924) | 2021-08-03 |
| [#923](https://github.com/JUCMNAV/projetseg-update/issues/923) | Needs review | The chart is displayed in disorder when do some "Undo" operations. (Bugzilla Bug 923) | 2021-08-03 |
| [#880](https://github.com/JUCMNAV/projetseg-update/issues/880) | Needs review | Adding and removing stub plugins (Bugzilla Bug 880) | 2021-08-03 |
| [#770](https://github.com/JUCMNAV/projetseg-update/issues/770) | Needs review | refactoring into stub problem (Bugzilla Bug 770) | 2021-08-03 |
| [#768](https://github.com/JUCMNAV/projetseg-update/issues/768) | Needs review | expand templating to anything that can be defined on a plug-in map (Bugzilla Bug 768) | 2021-08-03 |
| [#767](https://github.com/JUCMNAV/projetseg-update/issues/767) | Needs review | custom-defined stubs (Bugzilla Bug 767) | 2021-08-03 |
| [#765](https://github.com/JUCMNAV/projetseg-update/issues/765) | Needs review | improved visualization of bindings (Bugzilla Bug 765) | 2021-08-03 |
| [#752](https://github.com/JUCMNAV/projetseg-update/issues/752) | Needs review | Z.151 import/export: need to handle differences in the data languages used in conditions (Bugzilla Bug 752) | 2021-08-03 |
| [#513](https://github.com/JUCMNAV/projetseg-update/issues/513) | Needs review | Create a DetachBranchCommand (Bugzilla Bug 513) | 2021-08-03 |
| [#368](https://github.com/JUCMNAV/projetseg-update/issues/368) | Needs review | refactor: AddPluginCommand (Bugzilla Bug 368) | 2021-08-03 |

### UI / actions / palette (6)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#884](https://github.com/JUCMNAV/projetseg-update/issues/884) | Needs review | shortcut for "select" (Bugzilla Bug 884) | 2021-08-03 |
| [#644](https://github.com/JUCMNAV/projetseg-update/issues/644) | Needs review | Centralize Performance menus to minimize pop-up clutter (Bugzilla Bug 644) | 2021-08-03 |
| [#516](https://github.com/JUCMNAV/projetseg-update/issues/516) | Needs review | refactor: create a DisconnectTimeoutPathCommand (Bugzilla Bug 516) | 2021-08-03 |
| [#406](https://github.com/JUCMNAV/projetseg-update/issues/406) | Needs review | palette preferences (Bugzilla Bug 406) | 2021-08-03 |
| [#370](https://github.com/JUCMNAV/projetseg-update/issues/370) | Bug - needs repro | refactor: attempt alternate solution in DevDocConnectionOnBottom (Bugzilla Bug 370) | 2021-08-03 |
| [#261](https://github.com/JUCMNAV/projetseg-update/issues/261) | Enhancement (defer) | multipage editor / outline:  should be able to open only a subset of all maps. (Bugzilla Bug 261) | 2021-08-03 |

### Views / properties (3)

| # | Disposition | Title | Created |
|---|---|---|---|
| [#521](https://github.com/JUCMNAV/projetseg-update/issues/521) | Needs review | mac bug: perspective not reset (Bugzilla Bug 521) | 2021-08-03 |
| [#376](https://github.com/JUCMNAV/projetseg-update/issues/376) | Needs review | refactor: DevDocProperties (Bugzilla Bug 376) | 2021-08-03 |
| [#321](https://github.com/JUCMNAV/projetseg-update/issues/321) | Needs review | connects: if change x,y in properties, connected element doesn't work. (Bugzilla Bug 321) | 2021-08-03 |

## Recommended next steps

1. Walk the "Possibly fixed by modernization" table above. For each row, install the current `modernization` build and try the original repro. If the bug is gone, leave a comment on the legacy issue pointing at the fixing commit on `JUCMNAV/jUCMNavPlus` and close as obsolete. (You have admin on `JUCMNAV/projetseg-update`, so you can close directly.)
2. For surviving bugs, use GitHub's "Transfer issue" feature on the legacy issue (same-org transfers work natively now that both repos live under `JUCMNAV/`). Preserves comments, authors, and cross-references. Add an `area:*` label after transfer.
3. Enhancements / questions / docs items: leave on `projetseg-update` for the archive. Cherry-pick to the new tracker only when someone actually plans to work on them.
4. Re-run this triage (`triage.ps1` in the repo root of `jUCMNav2026`) periodically as the legacy issue list shrinks.

