import type { ReactNode } from "react"

/**
 * The top of every page: what it is, one line of what it is for, and the actions that belong to it.
 *
 * One component rather than a heading copied thirteen times, so that the one decision in it - the
 * note is `max-w-prose` and the actions sit on the baseline of the title, not below it - is made
 * once.
 */
export function PageHeader({
  title,
  note,
  actions,
}: {
  title: ReactNode
  note?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-1.5">
        <h1 className="text-2xl font-semibold tracking-tight text-balance">{title}</h1>
        {note ? <p className="max-w-prose text-sm text-muted-foreground">{note}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  )
}
