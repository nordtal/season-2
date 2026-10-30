import { TrashIcon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

import { AskThenAct } from "@/components/steward/ask-then-act"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { t } from "@/lib/texts"

/** The grid of read-only tiles that an awards or a prize section draws. */
export function Tiles({ children }: { children: ReactNode }) {
  return <ul className="grid grid-cols-3 gap-2 @lg:grid-cols-4 @2xl:grid-cols-5 @4xl:grid-cols-6">{children}</ul>
}

/** One tile: a click opens its dialog. `mark` is the game icon, `details` the values under the name. */
export function Tile({
  mark,
  name,
  details,
  onOpen,
}: {
  mark: ReactNode
  name: string
  details: ReactNode
  onOpen: () => void
}) {
  return (
    <li>
      <button
        type="button"
        aria-haspopup="dialog"
        className="flex h-full w-full min-w-0 flex-col items-center gap-1 rounded-lg border px-1.5 py-2.5 text-center transition-colors hover:bg-muted/50"
        onClick={onOpen}
      >
        {mark}
        <span className="line-clamp-2 w-full text-xs break-words">{name}</span>
        <span className="mt-auto flex flex-wrap items-center justify-center gap-x-2 text-sm font-medium tabular-nums">
          {details}
        </span>
      </button>
    </li>
  )
}

/**
 * Everything about one entry: its values, and removing it. The values edit the draft at once, so Done only closes.
 */
export function EntryDialog({
  open,
  mark,
  name,
  description,
  disabled,
  children,
  onRemove,
  onClose,
}: {
  open: boolean
  mark: ReactNode
  name: string
  description?: string
  disabled: boolean
  children: ReactNode
  onRemove: () => void
  onClose: () => void
}) {
  const [asking, setAsking] = useState(false)
  return (
    <>
      <ResponsiveDialog open={open} onOpenChange={(next) => next || onClose()}>
        <ResponsiveDialogContent className="sm:max-w-md">
          <ResponsiveDialogHeader>
            <div className="flex min-w-0 items-center gap-3">
              {mark}
              <div className="flex min-w-0 flex-col gap-1 text-left">
                <ResponsiveDialogTitle>{name}</ResponsiveDialogTitle>
                {description ? <ResponsiveDialogDescription>{description}</ResponsiveDialogDescription> : null}
              </div>
            </div>
          </ResponsiveDialogHeader>
          <div className="flex flex-col gap-2">{children}</div>
          <ResponsiveDialogFooter>
            <Button
              type="button"
              variant="destructive"
              className="sm:mr-auto"
              disabled={disabled}
              onClick={() => setAsking(true)}
            >
              <TrashIcon aria-hidden />
              {t("steward.form.remove")}
            </Button>
            <Button type="button" onClick={onClose}>
              {t("steward.form.done")}
            </Button>
          </ResponsiveDialogFooter>
        </ResponsiveDialogContent>
      </ResponsiveDialog>
      <AskThenAct
        open={asking}
        onOpenChange={setAsking}
        title={t("steward.game.remove-ask", { name })}
        cancel={t("steward.game.keep")}
        action={t("steward.game.remove-it")}
        destructive
        act={() => {
          onRemove()
          onClose()
        }}
      />
    </>
  )
}

/** A field in an entry's dialog, its label above it. */
export function Labelled({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}
