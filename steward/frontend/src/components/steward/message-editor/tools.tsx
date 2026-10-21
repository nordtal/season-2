import type { ReactNode } from "react"
import { ArrowElbowDownLeftIcon, HashStraightIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"

export function ToolButton({
  label,
  pressed,
  disabled,
  onPress,
  children,
}: {
  label: string
  pressed?: boolean
  disabled?: boolean
  onPress?: () => void
  children: ReactNode
}) {
  return (
    <Button
      type="button"
      size="icon-sm"
      variant={pressed ? "secondary" : "ghost"}
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      className="shrink-0"
      // The selection stays where it is, because a button that took the focus would drop it.
      onMouseDown={(event) => event.preventDefault()}
      onClick={onPress}
    >
      {children}
    </Button>
  )
}

export function MenuTrigger({
  label,
  pressed,
  disabled,
  children,
  ...props
}: {
  label: string
  pressed?: boolean
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <Button
      type="button"
      size="icon-sm"
      variant={pressed ? "secondary" : "ghost"}
      aria-label={label}
      title={label}
      disabled={disabled}
      className="shrink-0"
      onMouseDown={(event) => event.preventDefault()}
      {...props}
    >
      {children}
    </Button>
  )
}

export function BreakButton({ onPress, disabled }: { onPress: () => void; disabled?: boolean }) {
  return (
    <ToolButton label={t("steward.message-editor.line-break")} onPress={onPress} disabled={disabled}>
      <ArrowElbowDownLeftIcon aria-hidden />
    </ToolButton>
  )
}

export function NumberButton({ onPress, disabled }: { onPress: () => void; disabled?: boolean }) {
  return (
    <ToolButton label={t("steward.message-editor.the-number")} onPress={onPress} disabled={disabled}>
      <HashStraightIcon aria-hidden />
    </ToolButton>
  )
}

/**
 * Its tools wrap into rows of equal columns, so each one stays in reach at every width. It stays in view while the field
 * scrolls under it, so a phone's keyboard never pushes it out of reach. Inside a hover it has no frame.
 */
export function Toolbar({
  label,
  nested,
  end,
  children,
}: {
  label: string
  nested?: boolean
  /** Drawn after the tools, at the row's end. */
  end?: ReactNode
  children: ReactNode
}) {
  return (
    <div
      role="toolbar"
      aria-label={label}
      className={cn(
        "grid min-w-0 grid-cols-[repeat(auto-fill,minmax(var(--spacing-control),1fr))] items-center justify-items-center",
        !nested && "sticky top-(--content-top,0px) z-10 rounded-t-lg border-b border-input bg-background px-1 py-1",
      )}
    >
      {children}
      {end ? <span className="col-span-2 flex">{end}</span> : null}
    </div>
  )
}
