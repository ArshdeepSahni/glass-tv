# Glass Design Language

> Glass is a home screen and an assistant for Android TV, used from a sofa with a remote (and a phone). This file
> is the source of truth for how every Glass surface looks, moves and speaks: the TV, the phone remote, the `glass`
> installer in a terminal, and the website. Extracted from the code (`Ui.java`, `Pane.java`, `Spring.java`, the
> phone remote's CSS) and the logo; the terminal and web values are derived from them.

## 1. Visual Theme

**Theme statement:** quiet, luminous glass.

**Character:** You look *through* Glass, not at it. The wallpaper is real and refracted in live glass panes; the
words are monochrome ink; there is one warm light, the sunrise inside the logo's sphere, and it is spent sparingly.
Everything moves like a physical object (springs that keep their speed), and everything says plainly what it does.
Nothing is promoted, badged or decorated.

**References:** Apple's Liquid Glass and visionOS materials (refraction, platters) · tvOS focus (a platter that
rises, no rings) · Dieter Rams ("less, but better") · Arc Browser's cinematic onboarding (the wake film).

**The mark:** a rendered glass sphere holding a sunrise over a dark horizon (`launcher/app/src/main/assets/phone/icon-512.png`).
It is a rendered picture, never redrawn as flat vector art.

---

## 2. Color Palette

Glass on the TV is monochrome: two tones (Day, Night) of ink at fixed strengths over glass. Colour comes from the
wallpaper and, for brand moments only, from the sunrise.

### Neutrals (ink)
| Token | Day | Night | Usage |
|---|---|---|---|
| `ink` | #16181D | #F2F2F5 | Primary words, glyphs |
| `ink-2` | ink @ 62% (#6F7073 on white) | ink @ 62% (#9B9B9E on #0E0E10) | Secondary words |
| `ink-3` | ink @ 40% | ink @ 38% | Hints, placeholders, disabled. **Below 4.5:1**: never for words that matter |
| `hair` | ink @ 8% | white @ 8% | Hairlines inside glass |
| `hover` | ink @ 6% | white @ 7% | Quiet fills (rows at rest, quiet buttons) |
| `sep` | ink @ 16% | white @ 16% | Separators |
| `ground` | #0E0E10 | #F2F2F5 | Selected fill (`sel`); its words are `selink` (#FFFFFF / #0E0E10) |
| `glass` | white @ 70% | #26262E @ 72% | Body tint of a frosted pane |
| `field` | white @ 55% | white @ 8% | A text field at rest |
| `focus` | #FFFFFF (+ ink 6% edge) | white @ 20% (+ white 30% edge) | The focus platter |

### Brand: the sunrise (sampled from the mark)
| Token | Value | Usage |
|---|---|---|
| `space` | #070915 → #151122 | Deep indigo: the mark's sky; dark brand backgrounds |
| `space-2` | #0B1022 | Phone remote's dark ground |
| `dusk` | #26223A | Raised surfaces on `space` |
| `mauve` | #94708D | Quiet brand tint (4.7:1 on `space`: large text and marks only) |
| `copper` | #DD8E68 | The sphere's lit rim |
| `apricot` | #FFB88D | **Accent on dark**: 11.8:1 on `space` |
| `dawn` | #FFE4DE | The sun's core: the brightest highlight, never a fill |
| `sunset` | #FF6A3D | Evening accent (already in the code); on dark only (2.9:1 on white) |
| `ember` | #C2410C | **Accent on light**: 5.2:1 on white |

The accent marks *progress and brand moments* (the mark, the wake film, a progress fill, one call to action). It is
never used for state.

### Semantic
| Role | On light | On dark | Pair with |
|---|---|---|---|
| Success | #1F7A35 (5.4:1) | #30D158 | ✓ and a word |
| Warning | #C2410C (`ember`) | #FF9500 | ! and a word |
| Error | #D70015 (5.4:1) | #FF453A | ✗ and a word |
| Info | #3A5FCD | #7DAAFF | i and a word |

The TV UI keeps state in words and glyphs. Semantic colour is for the installer and the website, and always comes
with a glyph and a word.

### Terminal (the `glass` installer)
Truecolor uses the hex values above. With 256 colours:
| Role | Dark terminal | Light terminal |
|---|---|---|
| Words | default foreground | default foreground |
| Secondary | 250 #bcbcbc | 240 #585858 |
| Hint | 246 #949494 | 243 #767676 |
| Box and rules | 60 #5f5f87 (the mark's rim) | 103 #8787af |
| Accent | 216 #ffaf87 (`apricot`) | 130 #af5f00 (`ember`) |
| Success · Warning · Error | 77 · 214 · 203 | 28 · 130 · 160 |

With 16 colours, or `NO_COLOR`, it falls back to bold, dim and plain text, and glyphs carry the meaning on their own.

---

## 3. Typography

**Families** (bundled in the app, SIL OFL; on the web from Google Fonts):
- UI: **Inter** 400 / 500 / 600: `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`
- Display: **Instrument Serif** (and italic): the one big greeting or title: `"Instrument Serif", Georgia, serif`
- Legacy: **Outfit**: inherited from tvhome (wallpaper captions, the app library). Not for new surfaces.
- Mono (installer and web only): the reader's terminal font; on the web `"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace`

**Scale on the TV** (design px at 1280×720, × the TV's Text size 0.85–1.3; ratio ~1.15):
| Token | Size | Weight | Line height | Usage |
|---|---|---|---|---|
| `meta` | 13 | 400 | 1.35 | Times, captions, hints |
| `body` | 15 | 400 / 500 | 1.4 | Body words, row subtitles |
| `row` | 17 | 500 | 1.3 | Row titles, menu items |
| `title` | 20 | 600 | 1.25 | Sheet and window titles |
| `hero` | 22–24 | 600 | 1.2 | The one big thing on a sheet |
| `display` | 40–64 | Instrument Serif | 1.05 | Greetings and full-screen titles |

Tabular figures (`tnum`) wherever numbers update (clock, volume, progress).

---

## 4. Components

| Component | Hierarchy | Purpose | Key variants |
|---|---|---|---|
| Pane | Primitive | Live liquid glass: the scene behind, magnified, bent at the lip; bloom, depth, rim | Frost / Clear · over wallpaper / over an app (tinted frost) |
| Platter | Primitive | Focus: a bright plate behind what has focus | Day (white + hairline) / Night (lit glass) |
| Lift | Primitive | Focus rises: scale 1.03 (cards 1.05) on a soft shadow | Reduced motion: no growing |
| Veil | Primitive | Focus sheen over something with its own fill | — |
| Lozenge | Primitive | Quiet tint behind the bar's focused or open item | — |
| Field | Primitive | Text entry: a quiet well; with focus it brightens, its edge firms | With / without search glyph |
| Glyph | Primitive | Line icons drawn in ink | Sizes follow the Text size |
| Bar | Component | The menu bar along the top (34 high) | — |
| Dock | Component | Apps along the bottom; grows on focus | Running dot |
| Row | Component | Settings and menu rows | Info · link · switch · choice |
| Sheet | Pattern | A window or panel on glass; keeps focus inside | Panel (pours from the bar) · window (grows from its icon) · overlay (over an app) |
| Menu | Pattern | Contextual choices, stacked over a sheet | — |
| Note | Pattern | A short message over anything (Hud) | With glyph |
| Switcher | Pattern | Hold Home: open apps as cards | On screen · chosen · closing |
| Widgets | Pattern | Clock, weather, now playing, calendar, photos | Movable, resizable |
| Phone remote | Template | The TV's web page for phones | Light / dark |
| Installer screen | Template | `glass` in a terminal: one box, a step list, one status | See §8 and `docs/design/installer.md` |
| Frame | Component (terminal) | The box filling the terminal, with words set into its top and bottom lines | — |
| Step row | Component (terminal) | A step and its outcome | To do · running · your turn · done · skipped · stopped |
| Progress | Component (terminal) | `━━━━╸━━` accent fill (sunrise gradient in truecolor) on a hint track, count after | Determinate · indeterminate |
| Choice | Component (terminal) | One of a few options; the chosen row sits on a platter | Recommended first |
| Field · Secret | Component (terminal) | A typed answer in a well · a pasted key shown as `•` and a count | Rest · typing · error |
| Notice | Component (terminal) | Done or stopped: glyph, title, what happened, the one next step | Success · error |

---

## 5. Layout

**Grid:** everything on the TV is placed in 1280×720 design px and scaled to the screen (`s = max(w, h) / 1280`),
so a 720p and a 4K TV show the same proportions.

**Base unit:** 4. **Spacing:** 4 · 8 · 12 · 16 · 20 · 24. Screen edge 16; the bar 34 high; panels start at 42.

**Radii nest:** inner = outer − the gap between them. Windows and panels 28, their rows 16–18, the dock 31,
icons 23.5% of their size.

**Words never clip:** a box that holds words grows by what the words grow at a bigger Text size (`Ui.boxf`), and
words cut short (…) scroll into view while their item has focus.

**Density:** comfortable: ten feet away, one decision per glance.

---

## 6. Depth

| Level | What | How it reads |
|---|---|---|
| 0 | Wallpaper (or the app on screen) | The scene |
| 1 | Widgets on the desktop | Glass over the scene |
| 2 | Bar and dock | Clear glass, always there |
| 3 | Sheets (panels, windows) | Frosted glass; dims what's behind by 10% on the desktop, 34% over an app |
| 4 | Menus | Stacked over their sheet |
| 5 | Over apps (Hud) | An overlay window above any app |
| 6 | Notes | Above everything, briefly |

Shadows come from the shape itself (elevation), soft and close: focus z 3, switcher cards z 8. The web uses
`0 6px 16px rgba(14,14,16,.18)` for a filled button and nothing heavier than `0 24px 60px rgba(7,9,21,.35)`.

---

## 7. Do's and Don'ts

### Do
- Show focus with the platter and a small rise; one thing has focus, and it is obvious from across the room.
- Nest radii (inner = outer − gap) and let boxes grow with the Text size.
- Spend the sunrise on one thing per view: the mark, a progress fill, or the single most important action.
- Say what happens, in a few plain words: "Glass restarts on the new version and says so."
- Make everything undoable; ask before anything that loses data, and make the safe choice the default.
- Show Glass's own screens, drawn from its layout (`tools/scenes.py`), with neutral sample content: the same in both
  looks, by day in the light one and in the evening in the dark one.

### Don't
- Don't draw the mark (or any hero art) as flat vector shapes: it is a rendered sphere of glass.
- Don't use colour alone for state: a glyph and a word always come with it.
- Don't use `ink-3` for words someone needs to read.
- Don't add rings, badges, counters, promoted rows or anything that sells.
- Don't let an animation outlast 90 ms with Reduced Motion on, or ignore the TV's animation speed.
- Don't show anyone's addresses, town, names, accounts or photos in a capture, a log or a doc.

---

## 8. Responsive

**TV:** one layout in design px, scaled to any resolution; Text size S–XL (0.85–1.3) grows the words and the boxes
around them, never the layout.

**Phone remote:** one column, safe-area insets, 44 px minimum targets; follows the TV's Day/Night.

**Installer (terminal):**
| Width | Layout |
|---|---|
| < 60 × 18 | "Make the window a little bigger", centred |
| 60–99 cols | One column: steps, then the current step's status below them |
| ≥ 100 cols | Two columns: steps on the left (30 cols), the current step's status on the right |
| No terminal (pipe, CI) | Plain lines, one per step change, no colour |

**Website:** [docs/design/site.md](docs/design/site.md).

---

## 9. Agent Prompt Guide

**When making anything for Glass, agents must:**
- Use the tokens in §2–6; on the TV, place things in 1280×720 design px through `Ui`.
- Hold every decision against the theme: quiet, luminous glass.
- Reuse the components in §4 before making a new one.
- Treat §7 as hard rules.

**Invariants:**
- Ink is #16181D by day and #F2F2F5 by night, at the fixed strengths above.
- The accent is `apricot` on dark and `ember` on light, and it never means state.
- Focus is a platter and a rise, never a ring.
- Inter for UI, Instrument Serif for the one big title, nothing else on new surfaces.
- Durations go through `Ui.ms()`; springs through `Spring`.

**Open questions / known gaps:**
- `ink-3` is below 4.5:1 for small text on the TV. Fine for hints; anything that matters uses `ink-2`.
- The website's mono face and type scale are set with the site.

---

## 10. Motion

**Springs** (`Spring.java`; stiffness / damping ratio):
| Kind | Stiffness | Damping | Feel | Used for |
|---|---|---|---|---|
| FOCUS | 700 | 0.78 | Quick, a touch of overshoot | Dock lift, gliding highlights, the bar's lozenge |
| PANEL | 380 | 0.74 | A soft bounce | Panels pouring from the bar, menus, the volume pill |
| WINDOW | 300 | 0.86 | Heavier, no wobble | Windows growing from the dock |
| SOFT | 500 | 1.0 | No bounce | Fades |

A spring that's retargeted keeps its velocity.

**Curves:** spring `cubic-bezier(.2, 1.3, .4, 1)` · window `(.18, 1.22, .32, 1)` · arrive `(.2, .8, .3, 1)` ·
leave `(.55, 0, .8, .2)` · standard `(.4, 0, .2, 1)`.
**Durations:** focus rise 170 ms, settle 140 ms; a card leaving 220 ms; divided by the TV's animator scale so Glass
keeps its pace.
**Signature transitions:** panels pour from their bar item; windows grow from their icon; a closed app's card
leaves for the top; the wake film plays as the TV comes on.
**Reduced motion:** at most 90 ms, no growing, critically damped (stiffness 15000). On the web,
`prefers-reduced-motion` gets opacity-only changes. In the installer, the spinner becomes a static glyph with
`GLASS_REDUCED_MOTION=1`.

**Sound** (soft, synthesised, follows the TV's volume): tick (focus moved) · pop (opens) · close · on/off · bump
(nowhere further) · step (volume) · glass (the assistant arrives).

---

## 11. Voice & Tone

**Voice:** plain, calm, exact. Say what happens and what to do next, in the fewest everyday words.

**Terminology:**
| Use | Avoid |
|---|---|
| the TV | device, box, client |
| Glass, the assistant (Glass Assistant) | the launcher, the APK |
| the phone remote | web app, companion app |
| Check for updates · update | OTA, sideload, deploy |
| network debugging, allow this computer | ADB authorization, RSA key |
| the key helper | daemon, shell user |
| your computer | host, workstation |

**Microcopy:** sentence case; no exclamation marks; buttons are verbs ("Install", "Check for updates"); errors say
what happened and the one next step; paths use › (Settings › About this TV); remote keys are ← → ↑ ↓, OK, Back,
Home; list separators are " · "; numbers carry units (4.9 MB, 0:12).
