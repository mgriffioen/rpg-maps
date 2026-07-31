# Architecture

Notes on why things are shaped the way they are, for when you come back to this
in six months.

## The one big idea

There is **one player view**, `receiver/index.html`, and it runs everywhere: on
a Chromecast, in a browser on your laptop, in a WebView on an Android TV. The
tablet never renders a frame for the TV; it sends state, and the receiver draws.

The alternative — rendering on the tablet and streaming pixels — was rejected
because panning would be a slideshow over Cast's message channel. Sending
"the viewport moved" is a few dozen bytes.

The cost is that fog is drawn twice, once in Kotlin
(`fog/FogMask.kt`) and once in JavaScript (`applyFogOp` in the receiver). Those
two must stay visually equivalent: same coordinate space, same round caps, same
blur-as-a-fraction-of-radius. If you change one, change the other.

## Coordinate spaces

Three, and keeping them straight is most of the work:

| Space | Where it comes from | Used for |
|---|---|---|
| **Map pixels** | the *display render*, not the original file | viewport, grid, calibration |
| **Fog pixels** | `FogMask.sizeFor()`, long edge capped at 1536 | every brush stroke |
| **Screen pixels** | whatever device is drawing | nothing on the wire |

Two decisions fall out of this:

- **The display render is the source of truth, not the original image.** Import
  downscales to 2048 px on the long edge and everything downstream uses that.
  A 9000 px original would otherwise need tiled rendering to be viewable at all.
  The original is kept so a different render size can be produced later.

- **Fog strokes travel in fog pixels, not normalised coordinates.** The receiver
  allocates a mask of exactly the announced size and replays the same ops, so
  there is no normalisation to get subtly wrong and no aspect correction
  anywhere.

## The viewport is vertical

`ViewportMessage` says "centre here, show this many map pixels above and below".
It deliberately does not say how wide.

The DM's tablet and the TV have different aspect ratios. If the sender dictated
a rectangle, one of them would have to letterbox or crop. Instead the sender
fixes the vertical framing and each receiver widens to its own aspect. A 16:9 TV
shows more to the sides than a 4:3 preview, which is exactly what you want.

This is also what makes *scale to life* possible: the receiver reports its pixel
size in `ReceiverHello`, the DM enters the physical diagonal, and the sender can
compute the `halfH` that renders one battle square at one real inch.

## Rotation

A TV lying flat on a table has no natural "up", so which edge the map reads
from depends on where people are sitting. There are **two** quarter-turn
values, because there are two different questions.

`tvRotationQuarters` — *how the TV is standing.* This applies to the player
view only; the DM's canvas ignores it. The first version turned both together,
reasoning that the two screens should agree — but they must not. The tablet is
in your hands and you orient it by turning it; the TV lies flat facing the
players, often at ninety degrees to you. Coupled, fixing the TV always broke
the tablet.

`mapRotationQuarters` — *which way the map artwork faces.* That belongs to the
map rather than to either screen, so it turns **both** together, and the
tablet stays an honest preview of the TV.

The receiver only ever needs the sum, so the wire format still carries a single
`RotationMessage`: the sender composes `playerTotalQuarters =
(map + tv) mod 4`. Fog lives in map coordinates, so none of this touches the
mask.

The rule both ends derive from: **`halfH` governs whichever screen axis is
vertical after the rotation.** At 0 and 180 degrees that is the height; at 90
and 270 the width. That is also why turning the picture is what makes a
landscape signal fill a TV stood on its end. Three places implement it and must
agree: the receiver's `framing` in `draw()` and `scaleToLife`, both keyed on
the *total*, and the DM canvas's `framingExtent`, keyed on the *map* turn
alone.

The two rotate functions differ in one telling way. `rotateTv` deliberately
does not touch the viewport — this canvas is not rotating, so its framing must
not move. `rotateMap` must, because a quarter turn swaps which canvas axis
`halfH` is measured against; it rescales `halfH` by
`extentAfter / extentBefore` so the on-screen zoom is unchanged by the turn.

Both are global preferences rather than per-map columns. They model where the
TV sits and how the table is laid out, which does not change between maps, and
keeping them out of the database avoided a schema migration on libraries that
already have maps in them.

## Display sinks

`DisplaySink` has two implementations that differ in exactly one interesting
way — how the map image gets across:

- `LanSink` serves the bytes over HTTP and sends a URL. The receiver is on the
  same Wi-Fi and can just fetch.
- `CastSink` has no such option. A Chromecast receiver cannot reach the tablet,
  so the JPEG is base64-encoded and pushed through the Cast message channel in
  48 KB slices, each awaited before the next is sent. Awaiting is what keeps a
  slow first-generation Chromecast from dropping the tail of the transfer, and
  it gives an honest progress figure for free.

Everything else — viewport, fog, grid, curtain — is small JSON and goes through
`send()` unchanged.

`DisplayHub` keeps a full snapshot of player-visible state and replays it
whenever a sink reports a fresh connection or a receiver asks to resync. This is
the most important thing in the display layer: mid-session a Chromecast *will*
drop, and recovering without the DM noticing is the difference between a usable
app and a toy.

## Fog

The mask is a bitmap, not a stroke list. Replaying strokes would make drawing
cost grow through a session, and a three-hour dungeon crawl is a lot of strokes.

Undo is a stack of PNG snapshots. That sounds expensive until you notice the
mask is two-tone — black and transparent — so a 1536 px PNG of it is tens of
kilobytes. Thirty levels of history costs a couple of megabytes.

Rectangles and ovals are the same `FogOp`, not a new message: `shape` says how
to read `pts` -- a polyline for a brush, two opposite corners for a shape. One
op type means one ordering, sequencing and undo path for both. Shapes have no
incremental phase, so they skip the stroke buffer and commit through
`FogEditor.applyOnce` as a single undo step.

Softness is a blur in both cases, but from different bases: `radius * softness`
for a brush, and a capped fraction of the shorter side for a shape, which has
no radius. `SHAPE_SOFTNESS_FACTOR` and its neighbours live in the protocol file
precisely because `applyFogOp` in the receiver has to compute the same number.

**The receiver blurs with `shadowBlur`, not `ctx.filter`.** Android has
`BlurMaskFilter`; the obvious canvas equivalent is
`ctx.filter = 'blur(Npx)'`, and that is what the receiver used to do. Safari
does not implement `CanvasRenderingContext2D.filter` — it accepts the
assignment and silently ignores it — so on an Apple TV or an iPad browser every
soft edge came out hard while the tablet showed it correctly. The replacement
draws the shape *off-screen* with a shadow offset back into view: the shadow is
the blurred copy, and it is the only thing that lands in the visible region.
`shadowBlur` is roughly twice the standard deviation of `filter: blur()`, hence
the `blur * 2`. This costs one scratch canvas and works everywhere.

Chromium *does* support `ctx.filter`, which is why the whole browser test suite
stayed green while the real target was broken. `shapes.js` now runs a second
time under `NO_CANVAS_FILTER=1`, which stubs the property out to behave the way
Safari does.

Strokes are **batched, not sent per touch event**. `FogEditor` buffers points
and flushes every 40 ms; the flush produces one `FogOp` that is both drawn
locally and sent over the wire. Applying the identical op on both sides is what
keeps the tablet and the TV from drifting apart. Undo and redo cannot be
expressed as an op, so those send the whole mask instead.

## Threading

**A `DisplaySink.send` must never block, and must never touch the network on
the calling thread.** Both sinks therefore encode on the caller's thread and
hand the string to an unbounded channel drained by a single coroutine.

This is not a stylistic preference, it is the fix for a bug that cost a full
debugging session. `LanSink.send` used to write to the socket synchronously.
Every fog stroke, grid toggle and curtain originates in `viewModelScope`, which
is `Dispatchers.Main`, and Android answers a blocking socket write there with
`NetworkOnMainThreadException` — which `DisplayHub.broadcast` caught and logged
at warning level. The result was a player view that connected perfectly, showed
the right map, tracked pan and zoom, and silently ignored every reveal. The two
things that worked were the two that never ran on the main thread: the viewport
pump and full-state pushes, both on the hub's own scope.

The single consumer matters too. Independent `launch` calls reach a socket in
whatever order the dispatcher picks, and fog ops applied out of order leave the
receiver's mask quietly wrong.

Fog snapshots (`toPng`) do run on the **main thread** on purpose, even though
they take tens of milliseconds. That is CPU work, not I/O. The mask is mutated
from the main thread while painting, so encoding it anywhere else risks a torn
snapshot mid-stroke; a one-off frame hitch when a receiver connects is the
better trade. The file write that follows *is* on IO, debounced ~1.2 s so a
burst of strokes doesn't hammer flash.

## A trap worth knowing about

Kotlin generates a JVM setter for every non-private `var`, including one marked
`private set`. Writing a function of the same name -- the natural thing to do
when setting a property should also have a side effect, like persisting it or
mirroring it to the TV -- is a duplicate JVM method, and the build fails with
*"Platform declaration clash"*.

This project hit it three times: `setView`, `setBrushSoftness`, `setFogShape`.
The convention is to name those functions `update*`, `apply*` or `select*`
instead, and `tools/check-declaration-clashes.sh` finds any that slip through.

## What is deliberately not here

- **Tiled rendering.** Capping the display render at 2048 px sidesteps it. If
  you want to zoom deep into a 10000 px map, this is the thing to build.
- **A `PresentationSink`** for HDMI second screens. The `DisplaySink` interface
  exists partly so this can be added without touching anything else.
- **Token / miniature tracking.** The TV is the map; real minis sit on it.
- **Multiple maps on screen at once.**
