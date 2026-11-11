# The website: layout and behaviour

The site is quiet so the TV can be the show: pictures of Glass, sharp and as large as the screen allows, in the mode
you're in; short claims, each with its proof beside it; one moment of motion, the halo, when you change the mode. The
brief named bend-lang.com and LogSnag (a narrow column, wide margins), This Is It Studio (one quiet sans, the pictures
doing the talking), mdxcn (the framed glyph chart and terminals; steps, keys, endpoints, a tree, a diff and the changelog
standing on the page without a frame) and a palette from coolors (Ink, Bone, Teal, Aqua).

## Tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | Bone `#E9E4DB` | Ink `#2B2C28` | The page |
| `--raise` / `--sunk` | `#F3F0EA` / `#DED8CD` | `#34352F` / `#23241F` | Code, chosen tabs / picture wells, tracks |
| `--ink` / `--ink-2` | Ink / `#5F5E57` | Bone / `#AAA69C` | Words / quieter words (both pass AA on their page) |
| `--accent` | Teal `#339989` | Aqua `#7DE2D1` | Underlines, Glass's bars, the halo |
| `--accent-ink` | `#1F6B60` | Aqua | Links and small accent text (Teal itself is too light for small text on Bone) |
| `--add` / `--del` | `#1F7F5C` / `#A8452F` | `#5FD39C` / `#F08C74` | What comes back and what goes, in the diff |
| `--t-*` | | | The installer's recordings: each of the terminal's colours mapped to what it means, so they follow the mode |

Type: Geist for reading, headings 600 with tight tracking, sized by the screen's width and its height so a heading
never pushes a fold's picture off it; Geist Mono for numbers, commands, keys, the charts and the recordings. Both
self-hosted, subset to Latin, with size-matched fallbacks. One ease, `cubic-bezier(.23, 1, .32, 1)`.

## Folds

Each page is a run of folds, each at least one screen high (`100svh`, so a phone's address bar never hides the
bottom), and the page snaps from one to the next (`scroll-snap-type: y mandatory`, one fold per swipe). Nothing ever
scrolls inside a fold: the page is the only thing that scrolls up and down (a row of tabs or chips may still scroll
sideways).

The landing page sits in one column, the bar included, as wide as lets every fold's picture show whole under its words
and never wider than 1040 px: `--col` is the screen's height less the bar, the fold's margins and the most any fold
spends on words and buttons (`--words`), times 16/9. So a fold's heading, its line, its picture and its buttons all
share the same two edges, with wide margins either side (a short laptop screen gets a narrower column, a tall one the
full width), and the fold's contents sit together in the middle of the screen. A picture still gives way if the words
ever need more room (`.stage`: it starts at the column's width and shrinks, flush left, never cropped).

The docs keep their contents down the left and each chapter starts on the same line under the bar, like turning a
page. Long things become tabs, a list beside a view, or a recording sized to fit, instead of a taller page. On a screen
under 520 px tall (a phone on its side) the snapping stops and the page scrolls freely.

A fold that doesn't fit its screen is measured by the script (on load, when the fonts arrive, on resize, and after a
click or a disclosure inside it) and, in this order: lets its picture or recording give (`.give`: the fold is held to
the screen and the picture shrinks, never below 82% of its size); then closes up its spacing below the heading
(`.snug`: the space at its foot and between its parts, the heading staying where every chapter's heading is); and if
it still can't fit, it grows (`.tall`): the page scrolls through it like any page, and a second snap point at its foot
(`.fold-end`) lets the snapping rest on its end as well as its start. Its last line always keeps 8 px clear of the
fold's foot. At 1280 × 720 and larger every fold fits; on a phone, or a screen shorter than that, the longer chapters grow.

The bar is fixed over every fold. Its name always goes to the top of the landing page and Docs to the top of the docs
(on their own page they scroll there; the browser never puts you back halfway down).

## Tabs

Every row of tabs moves on by itself while it's on screen: a line under the chosen tab fills while it waits (5 to 7
seconds, or a film's whole length), then the next one is chosen and scrolled into the middle of its row. Hovering
over it, focusing it or choosing a tab yourself stops it; Reduce Motion never starts it. Reference tabs (commands,
specifications, credits) stay where you put them. Words are underlined tabs; two or three ways of seeing one thing (the
two remotes, a widget's sizes) are a segmented switch; the things to ask are chips, one line each. A row wider than its
column scrolls sideways and fades at its ends; one that fits doesn't fade.

## The mode, and the halo

`:root[data-mode]` is set before the first paint from your choice (kept in the browser), else the viewer's, else the
system's; without script the system decides. Every picture, film, poster and caption comes in both looks (`.l-light`,
`.l-dark`) and only the one for the mode shows. Changing the mode: the coming look's pictures in view are decoded first
(a third of a second at most), then the halo, after Kubai's shockwave halo in its Frost palette (teal, aqua, a
green and a near-white, from the site's own accent): a View Transition reveals the new mode through a soft edge
expanding from the switch, and four broad rings of light ride on that edge. The radius grows as
`(1 − 2^(−9t)) / (1 − 2^(−9))` over a second (fast, then easing out), and the light's strength rises in the first 3%
and falls as `1 − t^2.2`; each ring is a Gaussian band `w` wide (`min(W, H) × 0.125`, held between 70 and 110 px).
Where the page is in its light look the light tints it; where it's in its dark look the light is added (`screen`).
Under the middle of the halo the page softens as through thick glass (a 3 px backdrop blur, as a band); the page's own
rules and borders catch the light as it passes (hairlines drawn over them), and the screen's edges glow as it reaches
them. Everything is drawn from one clock on the page's own thread: the reveal and the frost are paused animations whose
time is set each frame from the same curve as the rings, so however busy the page is they move together. The original's
lens bend and colour split aren't here: a browser can only bend the page with an SVG displacement filter, which comes
out jagged, so the frost stands in for it. Without View Transitions the light passes over the page as it changes; with
Reduce Motion it just changes.

## The landing page

| Fold | What's in it |
|---|---|
| Hero | The promise, one short line, Get Glass and the docs, the desktop on the TV |
| Lighter and quicker | Memory, opening and the slowest frame: each change large, then Glass against the TV's own home as glyph bars (the docs' format) |
| Made of glass | The screens, in tabs that move on |
| Your remote, and your phone | One tab for each: the remote's film with its buttons as cues; the phone from where you sit, each touch shown on its screen, then the TV answering |
| Ask, and it's done | Things to ask, one line each, and the TV answering |
| Set up with one command | The one-line installer to copy, the installer's recording in the page's colours (44 columns on a phone), its steps as chips |
| Updates when you want them | One line on updating and taking Glass off, Get Glass and the docs, and the footer |

## The docs

A rail of contents down the left from 1100 px (Contents in the bar below that, opening it as a sheet), and one fold
each: the title and Requirements (rows, each thing and what it is); Set up (mdxcn's steps, the two ways to get it as
commands of the same size, a note on the signing key); The installer (its recording, its steps as chips); Commands
(tabs: update, status, doctor and restore as recordings, the keys, the options); Components (the kinds of thing in a
list, each thing large beside it, so it stays one fold however many there are: Desktop, Bar and dock, Shortcuts,
Screens, Glass's own apps, Make it yours, Notifications; the bar marked on the whole screen, a widget's three sizes as a
switch over one picture); The remotes (a switch: the remote's film beside every button, the ones in the film starting
it from their part; the phone and how to connect it); Updates (from your computer, from the TV, and your own update
service folded away); Privacy (what leaves the TV as mdxcn's endpoints, what's kept on your computer as its tree);
Performance (every measure in one framed chart, one format, nothing twice, no table); Specifications (tabs of
two-column tables); Troubleshooting (a two-column table); Remove Glass (the command, and mdxcn's diff of what changes);
Changelog (mdxcn's changelog); Credits.

## Speed, search and answers

Plain HTML with the CSS inline and one small deferred script (minified by the build); no framework. Both fonts
preloaded; pictures as AVIF at three widths with the JPEG behind them, the mark too; films as AV1 with H.264 behind;
nothing loads until it's near, a film's still included (it's set a screen before the film is reached, so the first
screen loads only what it shows). On the real site every asset address carries its hash, so files are cached for a
year; a cache worker makes a second visit instant; the docs are prerendered when you're likely to open them; the
GitHub people in the credits are asked for only once the credits are in view. Addresses are clean (`/` and `/docs`, as
Cloudflare serves them, so no link costs a redirect), and an address that isn't there gets the site's own Not here page
(never indexed). Lighthouse, phone and desktop, both pages: 100 for performance, accessibility, best practices and SEO.

Each page has a title and description written for search, its canonical address, a share card with its alt text, and
structured data as one linked graph: the site, its author, the app (free, its requirements, screenshots, release
notes), the page, and on the docs a breadcrumb, how to set it up and the common questions. For AI answers: llms.txt (a
summary with links), each page as Markdown (`/index.md`, `/docs.md`, both in `llms-full.txt`), and a robots.txt that
welcomes the AI assistants' crawlers by name. A sitemap with dates and the main pictures, and `install.sh`, the
one-line installer.

## Where everything comes from

Nothing is typed twice (site/build.py's docstring has the list). The TV's pictures and films are drawn from Glass's
own screens: `tools/scenes.py` renders `tools/scenes/tv.html`, which lays them out from the launcher's own
measurements, fonts, glyphs, glass, wallpapers and the apps' icons, in both looks, at 2560 × 1440 (twice the TV's
design size), with the same content in each: by day in the light look, in the evening in the dark one. Nothing private,
no stray keyboard or volume bar, and the same clock everywhere. The films (`tools/scenes/films.js`) are drawn frame by
frame at 60 a second with Glass's own springs and timings, and they write their timelines, so the cues and the
phone's touches land as the TV answers. The installer recordings are `./glass demo`, at 100 × 30 and at 44 × 30 for
phones. The phone remote's screens are its real page, with the TV's answers stood in.

Each picture of the TV records what it was drawn from (`site/data/pictures.json`), and the build stops when a screen
changed after its picture, so the site never shows a screen the TV no longer has. The README's pictures are made from
the site's (`tools/readme_pictures.py`) and its words from `docs/readme/README.template.md`. And the site goes live
only with what's released: never ahead of the TVs, never behind them.
