# The website: layout and behaviour

Composed against [DESIGN.md](../../DESIGN.md), in the spirit of bend-lang.com, which the brief named: one narrow
column of monospaced text on paper, numbered claims each followed by its proof, and nothing that isn't content.
Glass's own brand comes in through the sunrise accent, the rendered glass sphere, the TV's display serif for the one
big word, and real pictures of the real TV.

## Layout Proposal: the landing page

**Pattern:** a single column (66 characters), numbered sections like an argument: claim, proof, next. Pictures of
the TV and the terminal may widen to 82 characters on large screens.
**Grid:** the column, centred; 2ch side gutter (at least 16 px); sections 4.5em apart with a hairline between them.
**Density:** comfortable; the type is fluid like Bend's (15 px on phones up to 17 px on large screens).

### Zone map
| Zone | What's in it |
|---|---|
| Top bar | `~/glass` (home), docs, changelog, GitHub; the one filled button: *get Glass* |
| Hero | The sphere (the real rendered mark), *Glass* in Instrument Serif, a one-line promise with bold key words, a dot-separated trait line, then the desktop as the TV shows it |
| Intro | Two short paragraphs and one quiet centred line |
| 1–2 | Light and quick: benchmark charts in a carousel (‹ ● ○ ○ ›), each with what was measured and on what |
| 3 | Glass: the screens, in a carousel with captions, and a recording of the glass moving |
| 4 | The assistant and the remote: what it understands, which button does what |
| 5 | The phone remote: its screens |
| 6 | Set up in one command: the installer's real recording, replayed as text |
| 7 | Updates: the two ways, and how long one takes |
| 8 | Get started: the three steps, with copy buttons |
| 9 | References: the docs, design language, runbook, changelog, source, licences |
| Footer | The version |

### Components
| Component | Use | States |
|---|---|---|
| Section heading | `1. Glass is LIGHT.`: the number in the quiet ink, the key word in capitals | — |
| Chart | Horizontal bars to scale; Glass in the accent, the rest in the paper's darker tone; value at the bar's end; a caption with the device and "lower is better" | One per carousel slide |
| Carousel | Slides with ‹ › and dots (keyboard ← →, swipe on phones) | Current dot in the accent |
| TV frame | A screenshot at 16:9 in a thin dark bezel with a soft shadow; caption below | Light and dark captures |
| Terminal | The installer's recording, replayed as real text in a dark window; play/pause and replay; the last frame at rest with Reduced Motion | Playing · paused · done |
| Code block | Mono on the darker paper, a file or context label above, a copy button | Copied |
| Button | The one filled pill (*get Glass*), in the accent | Hover · focus |

### Responsive behaviour
| Width | Change |
|---|---|
| < 600 px | Trait line wraps; the TV frame and terminal take the full column; the terminal scales its text to fit 96 columns, then scrolls sideways inside itself |
| ≥ 1000 px | Pictures widen to 82ch |

### Heuristic findings
| Heuristic | Finding | Severity | Adjustment |
|---|---|---|---|
| Visibility of status | A replay with no controls can't be followed | P2 | Play/pause, replay, and a step count |
| Match with the real world | Benchmarks mean little without a device | P1 | Every chart says which TV, what was measured, and whether lower is better |
| Aesthetic and minimalist design | Every spec on one page would bury the pitch | P1 | The landing page argues; the docs page holds every detail, with summaries first and the rest folded |
| Honesty | Sample content could pass for someone's real use | P2 | Pictures say "sample content" in their captions |

## Layout Proposal: the docs

**Pattern:** the same column and type, with a sticky numbered contents list on the left from 1100 px (and at the
top, folded, below that). Sections are numbered like Bend's subsections (`4.2.`), each opening with a two-line
summary and a table of the facts, then the rest in folds (`<details>`), so that a section shows just enough until
someone asks for more.

Sections: requirements · setup · the installer · the desktop · the remote · Control Center · Settings · the phone
remote · the assistant · updates · privacy and keys · performance · specifications · troubleshooting · restoring the
TV · design · changelog · credits.
