# STYLE_GUIDE.md — LUMEN Visual System

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

The visual language is **3D Liquid Glass**: a translucent, refractive, depth-bearing material that floats above the content it describes. This document fixes the tokens so the console, the overlay cards, and the screen indicator are one system.

---

## 1. Principles

1. **Glass is a lens, not a sheet.** Every glass surface refracts what is behind it. A surface that only blurs is a mistake.
2. **Depth is real.** A glass surface has a thickness. The thickness is what produces the refraction and the rim.
3. **Light has a direction.** One key light (top-left, 135°) and one fill (bottom-right). Every specular highlight follows it.
4. **The content stays readable.** Glass is a frame for information, never a filter over it. Body text on glass holds ≥ 4.5:1 contrast.
5. **Motion explains.** Springs carry state changes; nothing bounces for decoration.

---

## 2. Colour

### 2.1 Base palette

| Token | Value | Use |
|---|---|---|
| `void` | `#07090F` | Deep background |
| `abyss` | `#0C1018` | Console background |
| `slate` | `#161C28` | Raised surfaces under glass |
| `glassTint` | `#8FB4FF` @ 6 % | The tint a glass surface casts |
| `glassTintStrong` | `#6E9BFF` @ 12 % | Elevated glass |
| `textPrimary` | `#F2F5FC` | Body text |
| `textSecondary` | `#A9B4C8` | Labels, metadata |
| `textTertiary` | `#6B7789` | Disabled, de-emphasised |
| `hairline` | `#FFFFFF` @ 10 % | 1 dp separators |

### 2.2 State colours

These drive the indicator, the approval cards, and the run state everywhere. They are the *only* saturated colours in the system.

| State | Token | Core | Halo | Meaning |
|---|---|---|---|---|
| Idle | `stateIdle` | `#7C8AA3` | `#5A6580` @ 40 % | Agent registered, no run |
| Working | `stateWorking` | `#3FE0D0` | `#1FB8AC` @ 55 % | Actively executing |
| Awaiting approval | `stateApproval` | `#FFB020` | `#E08A00` @ 60 % | A human decision is required |
| Blocked | `stateBlocked` | `#FF5A5F` | `#D93A3F` @ 60 % | Idle gate, allowlist, or policy refusal |
| Error | `stateError` | `#FF3B3B` | `#C41E22` @ 65 % | The run failed |
| Aborting | `stateAbort` | `#FF8A3D` | `#E06A1E` @ 60 % | Abort in progress |

**Never** encode a run state by colour alone. Every state also has a distinct shape and a text label (§5, §6).

### 2.3 Glass surfaces

| Surface | Tint | Blur radius | Elevation |
|---|---|---|---|
| `GlassSurface.Card` | `glassTint` 6 % | 24 dp | 8 dp |
| `GlassSurface.Panel` | `glassTint` 8 % | 32 dp | 16 dp |
| `GlassSurface.Sheet` | `glassTintStrong` 12 % | 40 dp | 24 dp |
| `GlassSurface.Overlay` | `glassTint` 10 % | 28 dp | 20 dp |
| `GlassSurface.Inline` | `glassTint` 4 % | 12 dp | 2 dp |

**Blur radius is a dp value, not a px value**, and it scales with density. The blur is applied with `TileMode.Decal` so it does not sample outside the surface bounds.

---

## 3. The material

### 3.1 Layer stack (bottom to top)

```
┌─────────────────────────────────────────┐
│ 5. Rim specular (1 dp, moving highlight)│
│ 4. Inner shadow (ambient occlusion)     │
│ 3. Refraction layer (AGSL)               │
│ 2. Tint wash                            │
│ 1. Blur layer (RenderEffect blur-behind)│
│ 0. Backdrop (content beneath)           │
└─────────────────────────────────────────┘
```

### 3.2 Refraction parameters

| Parameter | Value | Meaning |
|---|---|---|
| `refractionStrength` | 1.0 (default) | Master scale, `0.0..1.5` |
| `maxDisplacement` | 3.5 dp | Maximum UV displacement at the rim |
| `displacementFalloff` | `pow(1 - r, 2.2)` | Displacement concentrates at the edge |
| `edgeBand` | 0.18 (of the half-width) | The band where displacement is strongest |
| `chromaticAberration` | ±0.6 dp at the rim, 0 at centre | RGB split |
| `ior` | 1.45 | Index of refraction, for the rim brightening |
| `fresnelPower` | 3.2 | Rim intensity falloff |
| `noiseScale` | 2.4 | Frequency of the surface warp field |
| `noiseAmplitude` | 0.35 | Warp strength, in dp |
| `surfaceThickness` | 6 dp | Drives the parallax offset of the backdrop sample |

**Rule:** `refractionStrength = 0` is only valid for `GlassSurface.Inline`, and inline surfaces must then use a plain tint with no rim specular.

**The shader is self-contained.** It produces the glass material itself — the warp field, the dispersion, the fresnel rim — and is drawn with an alpha, rather than sampling a chained backdrop. AGSL's `uniform shader` chain needs a backdrop that Compose cannot hand it, and a shader that samples nothing refracts nothing. The content behind the surface shows through it, bent.

**The glass is AGSL, and AGSL is API 33.** Below that, the surface falls back to a blurred tint with a specular rim: honest glass rather than pretend glass. The same applies to the edge glow, which has a Canvas comet fallback with the same picture.

### 3.3 Rim and specular

| Token | Value |
|---|---|
| `rimWidth` | 1 dp |
| `rimGradient` | 135° linear, `#FFFFFF` @ 55 % → `#FFFFFF` @ 6 % → `#A9C8FF` @ 22 % |
| `rimHighlightWidth` | 22 % of the rim arc, travelling |
| `rimHighlightSpeed` | 0.18 cycles/s (idle), 0.55 cycles/s (working) |
| `innerShadow` | `0 dp` offset, 12 dp blur, `#000000` @ 18 %, edges only |
| `outerShadow` | `y + 6 dp`, blur 24 dp, `#000000` @ 32 % |

### 3.4 Corner radius

| Surface | Corner radius |
|---|---|
| `Inline` | 10 dp |
| `Card` | 22 dp |
| `Panel` | 28 dp |
| `Sheet` | 32 dp top, 0 bottom (sheet) |
| `Overlay` | 24 dp |

---

## 4. Motion

| Transition | Curve | Duration | Notes |
|---|---|---|---|
| Surface enter | `spring(dampingRatio = 0.78, stiffness = 320)` | ~380 ms | Blur 0 → target, scale 0.96 → 1.0, alpha 0 → 1 |
| Surface exit | `spring(dampingRatio = 0.90, stiffness = 420)` | ~240 ms | Blur → 0, alpha → 0 |
| Refraction uniform | driven by the enter/exit spring | — | Never a separate animation |
| State change | `tween` 220 ms, `FastOutSlowIn` | 220 ms | Colour cross-fade between state tokens |
| Approval card in | `spring(dampingRatio = 0.85, stiffness = 380)` | ~320 ms | Scale 0.92 → 1.0, blur 12 → 28 dp |
| Approval card out | `spring(dampingRatio = 0.95, stiffness = 500)` | ~180 ms | — |
| Indicator (Mode A) | continuous | 0.25 Hz idle / 0.9 Hz working / 2 Hz approval | Travelling highlight, not a fade |
| Status dot (Mode B) | `spring(dampingRatio = 0.6, stiffness = 200)` | 900 ms/cycle | Scale 1.0 → 1.18 → 1.0 |
| Takeover handover | `tween` 160 ms | 160 ms | Immediate, no spring — takeover must feel instant |

**No bounce on a critical path.** An approval card, an abort, or a takeover uses a fast tween or a critically damped spring. Springs are for surfaces that are not on the user's critical path.

---

## 5. The active-status indicator

### 5.1 Mode A — Edge glow

A luminous band along the screen perimeter, drawn as a full-screen overlay window (`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `FLAG_LAYOUT_NO_LIMITS`).

| Token | Value |
|---|---|
| `edgeGlowThickness` | 8 dp (6 dp on compact widths) |
| `edgeGlowFalloff` | 24 dp outward, exponential |
| `edgeGlowCornerRadius` | 0 — the band follows the display edges exactly |
| `edgeGlowRim` | 1 dp inner specular at 135° |
| `edgeGlowSegments` | 34 travelling nodes (continuity with the reference's visual language, re-implemented) |
| `edgeGlowNodeSpacing` | 0.0052 × perimeter |
| `edgeGlowHaloRadius` | 2.4 dp, scaled 1.25 → 0.55 along the trail |
| `edgeGlowCoreRadius` | 1.5 dp, only on the leading 45 % of the trail |
| `edgeGlowHaloShadow` | 11 dp blur |
| `edgeGlowCoreShadow` | 5 dp blur |
| `edgeGlowAlpha` | 210/255 at the head, linear to 0 at the tail |

**Rendering:** an AGSL `RuntimeShader` on a `Canvas` that draws the trail as a sequence of radial sprites, plus a `Compose` layer for the inner rim. The shader receives `phase`, `perimeter`, `stateColor`, `speed`, and `alpha` as uniforms.

**State behaviour:**

| State | Band |
|---|---|
| Idle | 25 % alpha, 0.25 Hz, colour `stateIdle` |
| Working | 100 % alpha, 0.9 Hz, colour `stateWorking` |
| Awaiting approval | 100 % alpha, 2 Hz, colour `stateApproval`, plus a 1.5× band thickness pulse |
| Blocked | 100 % alpha, 0.4 Hz, colour `stateBlocked`, dashed pattern |
| Error | 100 % alpha, 3 Hz, colour `stateError` |
| Aborting | 100 % alpha, 3 Hz, colour `stateAbort` |

The indicator must render **above** app content and **below** the system status bar. It is hidden when the screen is off (nothing to show) and while the user is in a system-immersive surface.

### 5.2 Mode B — Status dot

A single dot at the top centre.

| Token | Value |
|---|---|
| `dotDiameter` | 10 dp |
| `dotTopInset` | 24 dp below the status bar |
| `dotHaloRadius` | 16 dp |
| `dotPulseScale` | 1.0 → 1.18 |
| `dotPulsePeriod` | 900 ms (working), 1,600 ms (idle) |
| `dotTouchTarget` | 48 dp (invisible, larger than the visual dot) |
| `dotShadow` | 5 dp blur, 40 % alpha |

Tapping the dot opens the console. The halo uses the same two-layer radial language as the edge glow, so the two modes read as the same product.

### 5.3 Mode C — Off

Nothing is drawn. The foreground-service notification still exists (Android requires it) and is **not** the indicator.

### 5.4 Indicator rules

- The indicator reflects the **agent runtime**, not the app. It is visible while a run is active, paused, or awaiting approval; hidden when idle.
- It is `runId`-idempotent: a stale console cannot clear a newer run's indicator, and one run cannot clear another's.
- It is never the only signal that the agent is active — the notification and the console always agree.
- The user can disable the indicator without disabling the agent.
- The indicator is rendered by the accessibility overlay host, so it disappears when the accessibility service is revoked. The notification remains.

---

## 6. Console layout

```
┌───────────────────────────────────────────────┐
│  ● LUMEN        [Working · 142/150]    [ ⏻ ]  │  ← status bar row, 56 dp
├───────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────┐  │
│  │  Live frame (glass inset, 16:9)         │  │  ← spectating surface
│  │  ┌──────────┐                           │  │
│  │  │ Glass    │  ← approval card overlay  │  │
│  │  │ card     │                           │  │
│  │  └──────────┘                           │  │
│  └─────────────────────────────────────────┘  │
│  ┌──────────────┐ ┌────────────────────────┐  │
│  │ Plan graph   │ │ Reasoning trace        │  │
│  │ (glass card) │ │ (glass card, scrollable)│  │
│  └──────────────┘ └────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │ RAM ▓▓▓░░ 1.2 GB │ Storage ▓▓░░░ 8 GB   │  │  ← budget meters
│  └─────────────────────────────────────────┘  │
└───────────────────────────────────────────────┘
```

| Element | Spec |
|---|---|
| Screen padding | 20 dp, 16 dp on compact |
| Card spacing | 14 dp |
| Grid | 2 columns ≥ 840 dp, 1 column below |
| Type scale | Display 32/38, Title 22/28, Body 15/22, Label 12/16 (sp/line) |
| Typeface | System default (`FontFamily.SansSerif`). No custom font — it costs RAM and adds a dependency |
| Numeric | `FontFeature.tabularFigures` for all counters and meters |

---

## 7. Approval card

The most important surface in the product. It must be readable at a glance and unambiguous.

| Element | Spec |
|---|---|
| Surface | `GlassSurface.Overlay`, 320 dp max width, 24 dp corner radius |
| Header | Risk class chip, e.g. `PAYMENT` in `stateApproval` |
| Title | 20 sp semibold — the action in plain language: "Send message to Ana" |
| Detail | 15 sp, `textPrimary` — the **concrete content**: the message body, the amount, the item |
| App row | App label + package, 13 sp `textSecondary` |
| Consequences | Bullet list, 14 sp — the irreversible effects, spelled out |
| Action preview | 14 sp `textSecondary`, monospace-ish for values |
| Buttons | `Approve` (filled, `stateWorking` glass), `Deny` (outlined), `Edit & continue` (text) |
| Countdown | A thin progress arc showing the lease remaining, `stateApproval` |
| Input | When an answer is requested, a glass text field with a send action |

**Copy rules:** state the consequence, not the mechanism. "This sends €382 to Iberia and cannot be undone" — not "executes payment intent". Never use the word "confirm" for a button that spends money; use "Pay €382".

---

## 8. Accessibility

| Rule | Requirement |
|---|---|
| Contrast | Body text on glass ≥ 4.5:1; large text and icons ≥ 3:1 |
| Colour independence | Every state has a shape difference and a text label, not just a hue |
| Motion sensitivity | Respect the system "remove animations" setting: springs become 120 ms fades, the indicator becomes a static band |
| Screen reader | Every interactive glass element has a real `contentDescription` and a real focus target |
| Touch targets | 48 dp minimum, including the invisible target around the status dot |
| Font scaling | Layouts survive 200 % font scale; glass surfaces grow rather than clip |

---

## 9. Golden references

| Asset | Path | Notes |
|---|---|---|
| Glass shader | `app/src/main/assets/shaders/glass_refraction.frag` | The single source of truth for the refraction |
| Indicator shader | `app/src/main/assets/shaders/edge_glow.frag` | Trail rendering |
| Token file | `app/src/main/java/.../design/GlassTokens.kt` | Every value in §2–§5 as a Kotlin constant |
| Screenshot tests | `app/src/test/screenshot/` | Golden PNGs for each surface × each state |

**The token file is the contract.** A magic number in a composable is a lint error, not a style preference.
