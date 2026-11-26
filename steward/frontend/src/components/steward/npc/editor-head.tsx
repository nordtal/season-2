import { ArrowLeftIcon } from "@/components/ui/icons"
import { Link } from "@tanstack/react-router"
import type { ReactNode } from "react"

import { NPCS_PATH } from "@/lib/npc-paths"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"

/** The editor's head: back link, NPC title, status badge, and actions, capped to two rows on phones. */
export function EditorHead({ title, status, actions }: { title: string; status?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:gap-3">
      <div className="-ml-2 flex min-w-0 items-center justify-between gap-2 sm:justify-start">
        <div className="flex min-w-0 items-center gap-1">
          <Button asChild variant="ghost" size="icon-sm">
            <Link {...NPCS_PATH} aria-label={t("steward.npcs.back")}>
              <ArrowLeftIcon aria-hidden />
            </Link>
          </Button>
          <h2 className="truncate text-xl font-semibold font-heading tracking-tight">{title}</h2>
        </div>
        {status ? <div className="shrink-0 sm:hidden">{status}</div> : null}
      </div>
      {actions ? (
        <div className="flex max-w-full flex-nowrap items-center justify-end gap-1.5 overflow-x-auto sm:flex-wrap sm:gap-2">
          {status ? <div className="hidden shrink-0 sm:flex">{status}</div> : null}
          {actions}
        </div>
      ) : null}
    </header>
  )
}
