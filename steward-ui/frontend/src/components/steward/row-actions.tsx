import { DotsThreeIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/** One thing that can be done to the row this sits in. */
export type RowAction = {
  /** Stable across renders, so React keeps the right button when the set changes. */
  key: string
  /** The control itself. It must not open a dialog of its own - see the note below. */
  node: ReactNode
}

/**
 * The actions of one table row: inline while there are at most two, behind a popover past that.
 *
 * <h2>Two is the number, and it is Till's</h2>
 * steward/106 asks for a popover as soon as there are too many actions for a row, and leaves the
 * number open. At 390px a table row is a card about 310px wide and a ghost button is roughly 90px,
 * so three buttons in a line is already wider than the card - the same overflow steward/103 is
 * about. The threshold is therefore not a taste: it is the width at which the row stops fitting.
 *
 * <h2>Nothing in here opens its own dialog, and that is a hard rule</h2>
 * A Radix popover closes on an interaction outside it, and a dialog's overlay is outside it. A
 * dialog rendered *inside* the popover is therefore unmounted by the click that opened it. So every
 * action handed here is a plain button that sets state on the page, and every dialog is rendered by
 * the page, next to the table and never inside a row. That is why `AccessPage` holds `selected`,
 * `revoking`, `granting` and `unlinking` rather than letting each row hold its own.
 *
 * <h2>The set is decided by the row, not by this component</h2>
 * It draws what it is given. "Which actions does this person allow" is a question about one
 * person's state and belongs where that state is read - steward/47's finding was precisely that
 * the answer had been the same for everybody.
 */
export function RowActions({ actions, label }: { actions: RowAction[]; label: string }) {
  if (actions.length === 0) return null

  if (actions.length <= 2) {
    return (
      <div className="flex flex-wrap items-center justify-end gap-1">
        {actions.map((action) => (
          <span key={action.key} className="contents">
            {action.node}
          </span>
        ))}
      </div>
    )
  }

  return (
    <div className="flex items-center justify-end">
      <Popover>
        <PopoverTrigger asChild>
          {/* `outline` rather than `ghost`: in a 390px table card this control sits alone on a
            * line with no label beside it, and a ghost button with only three dots in it reads as
            * a decoration. The border is what says "press me". */}
          <Button type="button" variant="outline" size="sm" aria-label={label}>
            <DotsThreeIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        {/* `align="start"` and a collision padding, because the trigger sits at the LEFT edge of a
          * stacked table card (`index.css` gives a mobile cell `justify-items: start`). Aligned to
          * its end, a 13rem panel resolves to a negative left offset, and Radix then parks it flush
          * against x=0 where it reads as cut off - measured at 390px on 2026-09-17. */}
        <PopoverContent align="start" collisionPadding={8} className="w-52 p-1">
          <div className="flex flex-col items-stretch gap-0.5 [&_[data-slot=button]]:w-full [&_[data-slot=button]]:justify-start">
            {actions.map((action) => (
              <span key={action.key} className="contents">
                {action.node}
              </span>
            ))}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  )
}
