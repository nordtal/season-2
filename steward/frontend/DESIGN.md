# Design

How Steward looks, for people and agents. Every value lives once, as a token in `src/index.css` on top of
Tailwind's default theme. The brand blue is a token in the generated `src/brand.gen.css`. This file names
the tokens and never repeats a value.

`src/design-tokens.test.ts` fails `:steward:viteTest` on a class with a bracketed value that names no token,
and on a spacing step off the scale. A token of Tailwind's default theme counts as a token. The test reads
classes only: a declaration in a stylesheet and the rules of Surfaces are checked in review.

## Direction

- **Personality**: a quiet console for one admin. The brand blue marks only what can be clicked.
- **Foundation**: dark only, on warm greys. `primary` is the brand blue and the one accent.
- **Depth**: tone and a hairline. A raised surface is a lighter tone with a hairline ring. A floating
  surface adds a shadow.
- **Steward is designed for the phone first.** The desktop layout follows from the narrow one. Where the
  two disagree, the phone wins. _(touch sizes and grids checked in `fits-on-a-phone.test.ts`)_
- **Steward shows data, not explanatory text.** A sentence stays only when it keeps a state honest
  ("Some of the readings could not be fetched") or reports an error. A confirmation keeps the one sentence
  that names what is lost. Every other sentence moves into the doc comment where its meaning matters.
- A tooltip carries only what its badge or button does not show, such as a date, a name or who set it.
- Every tooltip is Steward's own: `Hint` puts it on the element itself, a control or a mark that takes no focus, and
  `Tip` wraps a mark in a focusable span. The browser's `title` looks like no other part of Steward and never opens on
  a phone; a name for a screen reader is `aria-label`.
  _(checked in `no-native-tooltip.test.ts`)_

## Spacing

One scale for padding, margin, gap and space, in steps of `--spacing`: 1px, every 2px up to 12px,
every 4px up to 24px, every 8px up to 48px, then 64, 80, 96 and 128px. `SPACING_STEPS` in
`design-tokens.test.ts` lists these steps, and the test refuses any other.

- Parts that belong together are closer to each other than to the padding around them.
- Groups that do not belong together are further apart than that padding.
- One component keeps to few steps: one for its padding, one between its parts.
- A size (width, height, an inset) is any multiple of `--spacing`. A size that recurs, or that is
  not a multiple, becomes a token.
- `row`, `cell` and `control` are the height of a table row, the padding of a cell and the least height
  of a control. A coarse pointer raises `row` and `control`.
- `max-w-page` is the widest the page's content grows. `max-w-person` is the widest a person grows in a
  row. `rows` is the width of a table's own box from which its rows fit. A narrower box draws every row
  as a card.
- `list` is the height of a list that scrolls inside a page, a dialog or a drawer.

## Surfaces

Surfaces stack in three levels, each with a background token and the foreground token that goes on
it:

| Level    | What                                                          | Tokens                                                                          |
| -------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| page     | the background of every page, and the sidebar                 | `background`, `foreground`, and `sidebar` with `sidebar-border`                 |
| raised   | a card, a table row on a phone, the console, an input, a pill | `card`, `card-foreground`, `secondary`, `secondary-foreground`, `muted` as tint |
| floating | a popover, a menu, a tooltip, a dialog, a drawer, a toast     | `popover`, `popover-foreground`                                                 |

- A surface takes its level's tokens and no other level's.
- A level above sets itself apart by the depth that Direction names.
- A border is one hairline in the border token. A divider is the same line.
- A nested surface's radius is the outer radius less the space between them, never more.
- `rounded-inherit` gives a scrolling viewport its box's radius.
- A modal surface has a backdrop in `overlay`. A surface that does not block the page has none.
- Every control shows focus as the same ring, in the ring token.
- A colour comes from a token. A token with an opacity (`bg-muted/50`) still counts as the token.
- **Bordered elements are not nested.** Cards lie flat on the background, side by side. An inner card
  becomes a heading. _(a table inside a card checked in `no-card-around-a-table.test.ts`)_

## Colour

- **Only the theme's colours.** A colour value appears only in `src/index.css` and `src/brand.gen.css`.
  All other code uses their tokens. A colour that no token fits becomes a new token in `index.css`.
  Content keeps its own colours: Minecraft's chat colours, the game's and Discord's surfaces in a
  preview, and a colour a person picks. _(checked in `only-theme-colours.test.ts` and
  `design-tokens.test.ts`)_
- `primary` is the brand blue. It marks an action and nothing else. `ring` is the same blue, because
  only an action takes focus.
- Status has three colours: `success`, `warning` and `destructive`. A status badge also shows a word,
  so colour is never the only signal.
- `muted-foreground` is secondary text: a label, a date, a detail.
- `secondary-hover` is a secondary button under the pointer.
- `mark` and `mark-wash` draw a placeholder in a text and the mark of hover text.
- `chart-1` to `chart-5` draw chart series. None of them is blue, because a series cannot be clicked.
- `overlay` is the backdrop behind a dialog, a sheet or a drawer.

## Type

- `font-sans` (DM Sans) sets the interface. `font-heading` (JetBrains Mono) sets a heading and a stat's
  label. `font-mono` sets identifiers, code and the console. Monocraft sets the game's text in a preview.
- Text sizes are Tailwind's, plus three. `text-caption` is a small label: the line under the brand, a
  console line on a phone, the details of a network node. `text-badge` is the label of a small badge
  and a count drawn over an icon. `text-inline` is a chip inside running text, sized to that text.
- A number in a table, an input or a `tnum` element is set in tabular figures.
- No class draws a text in capitals. _(checked in `no-uppercase-text.test.ts`)_

## Components

- **Button** (`components/ui/button.tsx`): `default` for the one action of a view, `secondary` and
  `outline` beside it, `ghost` for an icon or a quiet action, `destructive` for a step that loses data,
  `link` inside text. `xs`, `sm` and the `icon-*` sizes fit dense rows.
- **Panel** (`components/steward/panel.tsx`): a titled section without a border. It holds a table.
- **Card** (`components/ui/card.tsx`): a raised section with a hairline ring. It never holds a table.
- **DataTable** (`components/steward/data-table.tsx`): every list of rows. Below `rows` each row is a
  card.
- **StatusBadge** (`components/steward/status.tsx`): a state in one of the three status colours, or
  `idle`.
- **Dialog**: `components/ui/responsive-dialog.tsx` draws a drawer below 640px and a dialog above. Both
  are floating and modal. _(checked in `dialogs-are-responsive.test.ts`)_
- **Popover** and **Tooltip**: floating, not modal. A popover is at most the screen's width less
  `--spacing(8)`. A tooltip lets the pointer through, so it never covers a target the pointer moves to.
- **Skeleton** (`components/ui/skeleton.tsx`): the waiting state of data, the height of the line it
  replaces. Every view waits through `QueryState`. _(checked in `one-waiting-state.test.ts`)_
- **Scroll area**: every scroll container draws the same thin scrollbar in the border colour.
  _(checked in `one-scrollbar.test.ts`)_
- **Term icons** (`components/steward/term-icons.tsx`): one icon per domain term, as `CONVENTIONS.md`
  says.

## Content

The message and document previews (`components/steward/message-editor/preview.tsx` and
`document-preview.tsx`) draw the game's and Discord's own look. So does the Discord text in
`visual-editor.tsx`. Their classes that `design-tokens.test.ts` exempts by name follow the game and
Discord, not this file.

## Decisions

| Decision                                                   | Why                                                                           |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Tailwind's default theme stays                             | The project's tokens add to it rather than restate it.                        |
| `cn` comes from `lib/utils.ts`, configured with the tokens | The package alone reads `text-caption` as a colour and drops the real colour. |
| One `list` height for scrolling lists                      | The console and three lists each had their own share of the screen.           |
| `text-inline` is one size for every chip in a text         | The editor and the preview drew the same placeholder chip at 0.7em and 0.9em. |
