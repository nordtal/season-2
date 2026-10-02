import { useState } from "react"
import type { ReactNode } from "react"

import type { ConfigDocument } from "@/lib/api"
import { useConfig } from "@/lib/queries"
import { QueryState } from "@/components/steward/query-state"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { TRACK_FILE } from "@/components/steward/milestone-track"
import { Track } from "@/components/steward/milestones/milestones-editor"
import type { LayoutName } from "@/components/steward/milestones/layouts"
import { trackSchema } from "@/components/steward/milestones/model"

/**
 * The milestones editor's layouts side by side on the real track, each editable on its own copy and none saved.
 *
 * The editor in Settings draws the one picked; this page goes with `app/designs/` once it is.
 */
export function MilestonesConfigPage() {
  const group = useConfig(TRACK_FILE)
  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-semibold tracking-tight">Milestones</h1>
      <QueryState query={group} rows={8}>
        {(document) => (
          <>
            <Proposal mark="A" name="Rows, opened in place" document={document} layout="rows" />
            <Proposal mark="B" name="List and milestone" document={document} layout="split" />
            <Proposal mark="C" name="Everything open" document={document} layout="grid" />
          </>
        )}
      </QueryState>
    </div>
  )
}

function Proposal({
  mark,
  name,
  document,
  layout,
}: {
  mark: string
  name: string
  document: ConfigDocument
  layout: LayoutName
}): ReactNode {
  const schema = trackSchema(document.entries)
  const [draft, setDraft] = useState<SectionValues[] | undefined>(undefined)
  return (
    <section aria-label={`${mark} ${name}`} className="flex flex-col gap-4 border-t border-border pt-4">
      <h2 className="flex items-baseline gap-2 text-sm">
        <span className="font-semibold">{mark}</span>
        <span className="text-muted-foreground">{name}</span>
      </h2>
      {schema ? (
        <Track
          id={`${layout}.${schema.entry.path}`}
          schema={schema}
          stored={schema.entry}
          drafted={draft}
          disabled={false}
          layout={layout}
          onChange={setDraft}
        />
      ) : (
        <p className="text-sm text-muted-foreground">No track in this group.</p>
      )}
    </section>
  )
}
