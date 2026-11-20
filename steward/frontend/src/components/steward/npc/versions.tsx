import type { NpcVersion } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useNpcVersions } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"
import { Actor } from "@/components/steward/entity"
import { QueryState } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

/**
 * Every publication of an NPC, the newest first. The first is live; any other is loaded into the draft, which the
 * admin then publishes like any other change.
 */
export function NpcVersions({
  id,
  name,
  open,
  onOpenChange,
  onLoad,
}: {
  id: string
  name: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onLoad: (version: NpcVersion) => void
}) {
  const versions = useNpcVersions(id, open)
  return (
    <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveDialogContent className="sm:max-w-lg" aria-describedby={undefined}>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.npcs.versions-of", { name })}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>
        <QueryState query={versions} rows={4}>
          {(list) => (
            <ol className="flex max-h-list flex-col divide-y overflow-y-auto">
              {list.map((version, index) => (
                <li key={version.number} className="flex min-h-10 min-w-0 flex-wrap items-center gap-x-3 gap-y-1 py-2">
                  <span className="shrink-0 text-sm font-medium tabular-nums">
                    {t("steward.npcs.version", { number: version.number })}
                  </span>
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{dateTime(version.saved)}</span>
                  <span className="min-w-0 truncate text-xs text-muted-foreground">
                    <Actor kind={version.actor.kind} id={version.actor.person ?? ""} />
                  </span>
                  <span className="flex-1" />
                  {version.removal ? (
                    <Badge variant="outline" className="shrink-0">
                      {t("steward.npcs.removed")}
                    </Badge>
                  ) : index === 0 ? (
                    <Badge variant="secondary" className="shrink-0">
                      {t("steward.npcs.is-live")}
                    </Badge>
                  ) : (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="shrink-0"
                      onClick={() => onLoad(version)}
                    >
                      {t("steward.npcs.load")}
                    </Button>
                  )}
                </li>
              ))}
            </ol>
          )}
        </QueryState>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
