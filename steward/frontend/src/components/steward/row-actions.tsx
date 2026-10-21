import { DotsThreeIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

export type RowAction = {
  key: string
  /** The control itself. It must not open a dialog of its own. */
  node: ReactNode
}

/**
 * Three actions do not fit a 390px card. An action only sets page state, because a dialog inside the popover would close with it.
 */
export function RowActions({ actions, label, menu = false }: { actions: RowAction[]; label: string; menu?: boolean }) {
  if (actions.length === 0) return null

  if (actions.length <= 2 && !menu) {
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
          {/* A square of the row's height, so the button fits its column and leaves the row as high as the others. */}
          <Button type="button" variant="ghost" size="icon" aria-label={label} className="size-row min-h-0">
            <DotsThreeIcon aria-hidden />
          </Button>
        </PopoverTrigger>
        {/* The trigger ends its row on a desktop and its card on a phone, so the panel opens towards the content. */}
        <PopoverContent align="end" collisionPadding={8} className="w-52 p-1">
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
