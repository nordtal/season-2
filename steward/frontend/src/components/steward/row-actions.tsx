import { DotsThreeIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/** One thing that can be done to the row this sits in. */
export type RowAction = {
  /** Stable across renders, so React keeps the right button when the set changes. */
  key: string
  /** The control itself, which must not open a dialog of its own. */
  node: ReactNode
}

/**
 * The actions of one table row: inline while there are at most two, behind a popover past that.
 *
 * Three do not fit a 390px card. An action only sets page state; a dialog inside the popover would close with it.
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
          {/* `outline` rather than `ghost`, since a lone three dot button without a border reads as decoration. */}
          <Button type="button" variant="outline" size="sm" aria-label={label}>
            <DotsThreeIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        {/* `align="start"`, since the trigger sits at the card's left edge and an end aligned panel is cut off. */}
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
