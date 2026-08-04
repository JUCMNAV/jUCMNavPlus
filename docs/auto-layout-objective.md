# The auto-layout objective, and how it lied

A record of something that happened while building the constrained placement
for [#30](https://github.com/JUCMNAV/jUCMNavPlus/issues/30). It is written down
because the failure was invisible in every number we had, and the way it was
eventually caught is not the way anybody would plan to catch it.

## What happened

Issue #30 states an objective for a UCM drawing — four terms, arrived at by
looking hard at real models and at what the previous four attempts got wrong:

```
  w1 * path bending      turn angle^2 at each node along each chain
+ w2 * component area    area of the box each component implies
+ w3 * label overlap     pairwise intersection of element+label boxes
+ w4 * edge length       keeps the drawing compact
```

`LayoutObjective` implements it. Before writing any solver, it was validated the
obvious way: score the map a person drew by hand, then score the same nodes
scattered at random over the same rectangle. Hand-drawn came out at **17.98**,
scattered at **41.76**, and every one of the four terms moved the right way. A
measure that cannot tell those apart is measuring nothing, so this seemed like
enough to build on.

Then the solver was built to minimise exactly those four terms. It worked. It
scored **10.29** — not merely better than the pipeline it replaced (22.08), but
**43% better than the map a human being drew**.

The drawing was unreadable. Paths crossed each other repeatedly. Two branches of
a fork were drawn on top of one another. Labels sat on labels. The left-to-right
reading that makes a start point look like a beginning was gone entirely.

Every number said it was the best drawing anyone had produced. It was the worst.

## Why

Crossings were free.

Nothing in the four terms charges a drawing anything for one path crossing
another, and path crossings are most of what makes a diagram unreadable. Given
freedom and an objective that ignores crossings, an optimiser spends the freedom
on crossings — not out of perversity, but because that is the cheapest currency
available to pay for the terms that *are* scored.

The reason nobody noticed the omission earlier is the interesting part.
**Graphviz had been silently supplying crossing minimisation all along.** Every
previous approach used Graphviz to place the nodes, and crossing minimisation is
the thing Graphviz is genuinely excellent at. The property was always present in
the output, so it never appeared in anybody's list of things to ask for. It was
load-bearing and invisible at the same time.

The solver replaced Graphviz's placement. It therefore inherited responsibility
for every property Graphviz had been providing — including the ones nobody had
written down, because they had never had to be.

## Why the validation didn't catch it

This is the part worth keeping.

The objective was validated against **hand-drawn versus randomly scattered**.
That test passes with a missing crossing term, and it passes comfortably,
because random scattering is bad in all the ways the objective *does* measure —
wild bending, sprawling component boxes, enormous edge lengths. The crossing
counts of the two were 1 and 1. The validation pair could not see the missing
term because neither member of it exercised the gap.

Random noise does not find the holes in a measure. **Optimisation does.** That
is what optimisation is for. A validation set of "something good" and "something
random" tells you your measure is not *completely* blind; it tells you nothing
about the direction an optimiser will actually go, because an optimiser goes
somewhere neither sample was.

So the honest generalisation is not "we forgot crossings". It is:

> A measure validated only against good and random examples has been tested
> nowhere near the place an optimiser will take it. The first serious optimiser
> run *is* the real validation, and its output has to be looked at with eyes.

## The through-line: three blind instruments in one piece of work

The crossings gap was not an isolated slip. Three times in this work the
instrument was wrong while the numbers looked entirely reasonable:

1. **Bending was measured on 12 of 20 corners.** Chains were scored one at a
   time, so the turn *at* a junction between two chains was never measured. A
   node bound to a component is a junction however ordinary it looks, so on a map
   with components that is most of the corners. Caught by noticing that 30 path
   segments met at only 12 measured vertices — arithmetic, not observation.

2. **The solver was benchmarked against itself.** Once the solver became what
   `placeUcm` does, the test helper that fetched "the old pipeline for comparison"
   was fetching the solver. The comparison ran, passed, and printed two nearly
   identical rows. Caught only because "nearly identical" was implausible.

3. **Crossings, as above.** Caught by rendering a PNG and looking at it.

The common shape: a number that is confidently computed, plausibly sized, and
answering a different question from the one being asked. None of the three
produced an error, a warning, or an obviously silly value.

## What was done

- `LayoutObjective.crossings` counts proper segment crossings, and crossing rate
  is now a fifth term in the objective.
- The default weights are no longer all 1. They are derived from the measured
  spread of each term between a good drawing and a bad one, so that each term
  contributes comparably to the difference — at unit weights the component term
  was 85% of the total while discriminating least of the five.
- `ConstrainedPlacement` gained a flow force, which keeps a step forward from
  going backwards. This is deliberately **not** the rank that every rejected
  approach imported: it forces no layers and no contiguity, only a direction.
- `ConstrainedPlacementTest` asserts the crossing count against the hand-drawn
  map directly, not only through the weighted total, so this specific failure
  cannot come back quietly.

## What to do next time

- **Render it and look at it.** This is already the standing lesson from the
  earlier Graphviz work, where three experiments were wasted because the render
  loop showed what came *out* and never what went *in*. It generalises: the
  numbers are an instrument, and instruments need calibrating against reality
  more than once.
- **Suspect the objective first when a result is too good.** 43% better than a
  careful human is not a triumph, it is a bug report about the measure. A score
  that beats the best known example by a wide margin has almost always found a
  gap rather than a solution.
- **When you replace a component, enumerate what it was giving you.** Not what
  it was *for* — what it was *giving you*. Graphviz was there for topology and
  ranking; it was also, unasked, keeping the paths from crossing.
