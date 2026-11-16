import { PlusIcon } from "@/components/ui/icons"
import { useNavigate, useParams } from "@tanstack/react-router"

import type { Npc, NpcView } from "@/lib/api"
import { blankNpc } from "@/lib/npc"
import { setNpcDraft, useNpcDraft, useNpcDraftIds } from "@/lib/npc-drafts"
import { useNpcs } from "@/lib/queries-npcs"
import { t } from "@/lib/texts"
import { DataTable, type Column } from "@/components/steward/data-table"
import { NpcEditor, npcTitle } from "@/components/steward/npc/editor"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState, SkeletonText } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

/** A row of the list: a published NPC, or the draft of one that was never published. */
type Row = { id: string; view?: NpcView }

/** What a row shows of its NPC: the draft where the browser holds one, the published NPC otherwise. */
function Shown({ row, children }: { row: Row; children: (npc: Npc, changed: boolean) => React.ReactNode }) {
  const draft = useNpcDraft(row.id)
  const npc = draft?.npc ?? row.view?.npc
  return npc === undefined ? null : <>{children(npc, draft !== undefined)}</>
}

function Coordinate({ axis, value }: { axis: string; value: number }) {
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-xs text-muted-foreground">{axis}</span>
      <span className="font-mono tnum">{Math.floor(value)}</span>
    </span>
  )
}

const COLUMNS: Column<Row>[] = [
  {
    head: t("steward.npcs.name"),
    grow: true,
    phone: { line: 1, side: "start" },
    waiting: <SkeletonText width="long" className="max-w-40" />,
    render: (row) => (
      <Shown row={row}>
        {(npc) => (
          <span className="flex min-w-0 flex-col">
            <span className="truncate font-medium">{npcTitle(npc)}</span>
            <span lang="de" className="truncate text-xs text-muted-foreground">
              {npc.name.de}
            </span>
          </span>
        )}
      </Shown>
    ),
  },
  {
    head: t("steward.npcs.place"),
    phone: { line: 2, side: "wide" },
    render: (row) => (
      <Shown row={row}>
        {(npc) =>
          npc.place.world.trim() === "" ? null : (
            <span className="flex items-baseline gap-x-3 whitespace-nowrap">
              <span>{npc.place.world}</span>
              <Coordinate axis="X" value={npc.place.x} />
              <Coordinate axis="Y" value={npc.place.y} />
              <Coordinate axis="Z" value={npc.place.z} />
            </span>
          )
        }
      </Shown>
    ),
  },
  {
    head: t("steward.npcs.steps"),
    end: true,
    phone: { line: 3, side: "start", labelled: true },
    className: "tnum",
    render: (row) => (
      <Shown row={row}>{(npc) => t("steward.npcs.step-count", { count: npc.dialogue.steps.length })}</Shown>
    ),
  },
  {
    head: t("steward.npcs.versions"),
    end: true,
    phone: { line: 1, side: "end" },
    render: (row) => (
      <Shown row={row}>
        {(_, changed) => (
          <span className="flex flex-wrap items-center justify-end gap-1.5">
            {changed && row.view ? <StatusBadge tone="warn">{t("steward.npcs.changed")}</StatusBadge> : null}
            <Badge variant="secondary">
              {row.view ? t("steward.npcs.live", { version: row.view.version }) : t("steward.npcs.not-published")}
            </Badge>
          </span>
        )}
      </Shown>
    ),
  },
]

/** Every NPC of the game, and the drafts of those that were never published. A row opens its editor. */
export function NpcsPage() {
  const npcs = useNpcs()
  const drafts = useNpcDraftIds()
  const navigate = useNavigate()
  const open = (id: string) => navigate({ to: "/npcs/$id", params: { id } })
  const create = () => {
    const id = crypto.randomUUID()
    setNpcDraft(id, { npc: blankNpc(id), base: 0 })
    void open(id)
  }
  const rows = (views: NpcView[]): Row[] => {
    const published = new Set(views.map((view) => view.npc.id))
    return [
      ...views.map((view) => ({ id: view.npc.id, view })),
      ...drafts.filter((id) => !published.has(id)).map((id) => ({ id })),
    ]
  }
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t("steward.npcs.title")}
        actions={
          <Button type="button" onClick={create}>
            <PlusIcon aria-hidden />
            {t("steward.npcs.create")}
          </Button>
        }
      />
      <QueryState query={npcs} isEmpty={(views) => rows(views).length === 0} empty={{ title: t("steward.npcs.none") }}>
        {(views) => (
          <DataTable
            columns={COLUMNS}
            rows={views === undefined ? undefined : rows(views)}
            rowKey={(row) => row.id}
            waiting={4}
            onRowClick={(row) => void open(row.id)}
          />
        )}
      </QueryState>
    </div>
  )
}

export function NpcPage() {
  const { id } = useParams({ from: "/npcs/$id" })
  return <NpcEditor id={id} />
}
