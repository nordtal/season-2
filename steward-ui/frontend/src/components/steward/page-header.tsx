import type { ReactNode } from "react"

/**
 * The top of every page: what it is, and the actions that belong to it.
 *
 * **There is no note, and there is no prop for one.** A line under
 * the title saying what the page is for, to somebody already standing on it, is not here, and
 * the prop is not either, so the next one has to be argued for rather than filled in.
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
