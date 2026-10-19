import { CaretRightIcon } from "@phosphor-icons/react"
import { useState, type ReactNode } from "react"

import type { Document, DocumentView } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useDocumentVersions } from "@/lib/queries"
import { t } from "@/lib/texts"
import { cn } from "cn"
import { Actor } from "@/components/steward/entity"
import { Failure } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/**
 * Every save of the document, the newest first, and the packaged document last. The live one is marked; any other is
 * restored in one click.
 */
export function DocumentVersions({
  view,
  disabled,
  onRestore,
}: {
  view: DocumentView
  disabled: boolean
  onRestore: (document: Document | null) => void
}) {
  const [open, setOpen] = useState(false)
  const versions = useDocumentVersions(view.declaration, open)
  const saves = versions.data ?? []
  return (
    <div className="min-w-0 rounded-md border">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex w-full min-w-0 items-center gap-1.5 rounded-sm p-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <CaretRightIcon
          aria-hidden
          className={cn("size-3.5 shrink-0 transition-transform motion-reduce:transition-none", open && "rotate-90")}
        />
        <span className="text-xs font-medium">{t("steward.document-editor.versions")}</span>
      </button>
      {!open ? null : versions.error ? (
        <div className="border-t p-2">
          <Failure error={versions.error} />
        </div>
      ) : (
        <ol className="flex flex-col divide-y border-t">
          {saves.map((version, index) => (
            <VersionRow
              key={version.id}
              live={index === 0 && (version.document ? view.written !== undefined : view.written === undefined)}
              disabled={disabled || (!version.document && !view.written)}
              onRestore={() => onRestore(version.document ?? null)}
            >
              <span className="shrink-0 text-xs tabular-nums">{dateTime(version.saved)}</span>
              <span className="min-w-0 truncate text-xs text-muted-foreground">
                <Actor kind={version.actor.kind} id={version.actor.person ?? ""} />
              </span>
              {version.document ? null : (
                <Badge variant="outline" className="shrink-0">
                  {t("steward.document-editor.version", { version: "packaged" })}
                </Badge>
              )}
            </VersionRow>
          ))}
          <VersionRow
            live={saves.length === 0 && !view.written}
            disabled={disabled || !view.written}
            onRestore={() => onRestore(null)}
          >
            <span className="text-xs">{t("steward.document-editor.version", { version: "packaged" })}</span>
          </VersionRow>
        </ol>
      )}
    </div>
  )
}

function VersionRow({
  live,
  disabled,
  onRestore,
  children,
}: {
  live: boolean
  disabled: boolean
  onRestore: () => void
  children: ReactNode
}) {
  return (
    <li className="flex min-h-10 min-w-0 items-center gap-2 px-2 py-1">
      {children}
      <span className="flex-1" />
      {live ? (
        <Badge variant="secondary" className="shrink-0">
          {t("steward.document-editor.live")}
        </Badge>
      ) : (
        <Button type="button" variant="outline" size="sm" className="shrink-0" disabled={disabled} onClick={onRestore}>
          {t("steward.document-editor.restore")}
        </Button>
      )}
    </li>
  )
}
