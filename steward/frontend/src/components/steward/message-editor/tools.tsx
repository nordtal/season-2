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
 * It scrolls sideways when it does not fit, and stays in view while the field scrolls under it, so a phone's keyboard never pushes it out of reach. Inside a hover it is a plain row.
 */
export function Toolbar({ label, nested, children }: { label: string; nested?: boolean; children: ReactNode }) {
  return (
    <div
      role="toolbar"
      aria-label={label}
      className={cn(
        "flex min-w-0 items-center gap-0.5 overflow-x-auto",
        !nested && "sticky top-(--content-top,0px) z-10 rounded-t-lg border-b border-input bg-background px-1 py-1",
      )}
    >
      {children}
    </div>
  )
}

export function ToolDivider() {
  return <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-border" />
}
