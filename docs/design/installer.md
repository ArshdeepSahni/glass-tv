# The `glass` installer: layout and behaviour

Composed against [DESIGN.md](../../DESIGN.md) (§2 Terminal, §8 Installer). One screen, which never scrolls: a box
that fills the terminal, a list of steps, and the one thing happening now. Logs go to a file; the screen shows only
what someone needs to know or do.

## Layout Proposal: setup

**Pattern:** list-detail, as in the macOS Installer: the steps on the left, the current step on the right
(stacked below the steps when the terminal is narrow).
**Grid:** terminal cells. The box fills the whole terminal; inside it, 3 columns of padding (2 below 72 columns) and
1 row (2 at 30 rows and up).
**Density:** comfortable: one question at a time, at most 3 choices in view as a rule.

### Zone map
| Zone | What's in it | Notes |
|---|---|---|
| Top border | `Glass` (bold) on the left; the command and the step count (`Setup · 3 of 7`) on the right | The title sits in the border line: no header bar |
| Heading | What this does (bold), then one line on how long it takes and what it changes (secondary) | Two lines, always the same for a command |
| Steps | One row per step: glyph, name, result | Glyphs: `✓` done (success) · spinner (accent) · `●` your turn (accent) · `·` to do (hint) · `✗` stopped (error) · `–` skipped (hint) |
| Status | The current step: title (bold), what's happening (secondary, wrapped), then a progress bar, sub-steps, a question, or what went wrong | A vertical hairline divides it from the steps when side by side |
| Bottom border | The keys that work now (left); the log file (right) | Drops the log path first, then shortens the keys, when narrow |

### Components (gap request, approved with the brief)
The design system had no terminal components; these are added to DESIGN.md §4 under the installer template.

| Component | Purpose | States |
|---|---|---|
| Frame | The box, with words set into its top and bottom lines | — |
| Step row | A step and its outcome | to do · running · your turn · done · skipped · stopped |
| Progress | `━━━━━━━━╸━━━━` fill in the accent (the sunrise gradient in truecolor), track in hint; the count or % after it | Determinate · indeterminate (a short segment sweeping) |
| Choice | One of a few options: ↑↓ and Enter | The chosen row sits on a platter (a quiet background), never a ring; the recommended one is first |
| Field | A typed answer, in a well; inline error under it | Rest · typing · error |
| Secret | A pasted key: shows `•` for each character and the count, never the key | — |
| Notice | Done, or stopped: glyph + title, what happened, the one next step, at most 3 log lines | Success · error |

### The states of one step (status zone)
```
Find your TV                                   ← title (bold)
Looking on this network for a TV with network  ← secondary, wrapped
debugging on.

━━━━━━━━━━━━━━━━━━━━╸━━━━━━━━━━━  140 of 254    ← progress

0:04                                           ← elapsed (hint), once a step takes over 3 s
```
```
Your TV's address
On the TV: Settings › Network & Internet › your Wi-Fi.

 192.168.1.█                                   ← field (well)
✗ That isn't an address like 192.168.1.20      ← only after Enter
```
```
✗ Build stopped
Gradle couldn't download what it needs. Check that this computer is online, then try again.

  Could not resolve com.android.tools.build:gradle:8.5.2.     ← at most 3 log lines (hint)

R try again · L show the log · Q quit
```

### Responsive behaviour
| Terminal | Layout |
|---|---|
| < 60 × 18 | Only "Make the window a little bigger" (centred) |
| 60–99 columns | Steps, a hairline, then the status below them |
| ≥ 100 columns | Steps on the left (40 columns), the status on the right |
| ≥ 30 rows | More air: 2 rows of top padding |
| Not a terminal | Plain lines (`✓ Tools: Java 17 · Android SDK 34`), no colour, questions answered by flags |

### Heuristic findings (and what the design does)
| Heuristic | Finding | Severity | Adjustment |
|---|---|---|---|
| Visibility of system status | Long steps (first build 5–10 min, tool downloads) look stuck without feedback | P1 | Progress with counts or %, a plain phase ("Compiling"), and elapsed time after 3 s |
| Match with the real world | People know their TV's menus, not adb | P1 | Menu paths as the TV words them (Settings › Device Preferences › About › Build); "allow this computer", not "authorize RSA key" |
| User control and freedom | A 15-minute setup must survive a stop | P1 | Esc goes back; Q asks before stopping; every step is safe to run again, so `./glass setup` picks up where it stopped |
| Error prevention | Removing Glass loses its settings | P0 | `restore` explains first; the safe choice (Keep Glass) is the default |
| Recognition over recall | Keys differ per screen | P2 | The bottom border always lists the keys that work now |
| Help users recover from errors | Gradle and adb errors are cryptic | P1 | Known failures are translated (another key, older version, no space, offline); the rest show 3 log lines and where the log is |
| Aesthetic and minimalist design | Logs crowd out the one thing that matters | P1 | Logs go to a file; L peeks at the last lines on demand |

## The other commands
- `./glass` alone: setup on a computer that never set Glass up, otherwise a short menu (Update · Status ·
  Check and fix · Set up again · Back to the TV as it shipped).
- `update`: newest code (git, when this is a clone) → your TV → build → install.
- `status`: one view of the TV and Glass (version, home, helpers, memory, crashes).
- `doctor`: the same as checks, each with a fix it can apply.
- `restore`: back to the TV as it shipped, after saying what goes.
- `demo`: the whole interface with nothing real happening (for trying it, and for the website's recording).

Flags for scripts and impatient people: `--tv ADDRESS`, `--yes` (take the recommended answer), `--plain`,
`--no-color`.
