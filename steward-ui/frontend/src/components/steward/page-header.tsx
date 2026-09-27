import type { ReactNode } from "react"

/**
 * The top of every page: its title and its actions.
 *
 * There is deliberately no prop for a note under the title.
 */
export function PageHeader({ title, actions }: { title: ReactNode; actions?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-center justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-1.5">
        <h1 className="text-3xl font-semibold font-heading tracking-tight text-balance">{title}</h1>
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  )
}
