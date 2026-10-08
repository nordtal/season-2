import { useState } from "react"

import type { ConfigDocument } from "@/lib/api"
import { useConfig } from "@/lib/queries"
import { QueryState } from "@/components/steward/query-state"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { TRACK_FILE } from "@/components/steward/milestone-track"
import { Track } from "@/components/steward/milestones/milestones-editor"
import type { LayoutName } from "@/components/steward/milestones/layouts"
import { trackSchema } from "@/components/steward/milestones/model"

/**
 * The milestones editor's layouts one under another on the real track, each editable on its own copy and none saved.
 *
 * Settings draws A until one is picked; this page goes with `app/designs/` once the pick is built.
 */
export function MilestonesConfigPage() {
  const group = useConfig(TRACK_FILE)
  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-semibold tracking-tight">Milestones</h1>
      <QueryState query={group} rows={8}>
        {(document) => (
          <>
            <Proposal layout="A" name="Rows, opened in place" document={document} />
            <Proposal layout="B" name="List and milestone" document={document} />
            <Proposal layout="C" name="Everything open" document={document} />
          </>
        )}
      </QueryState>
    </div>
  )
}

function Proposal({ layout, name, document }: { layout: LayoutName; name: string; document: ConfigDocument }) {
  const schema = trackSchema(document.entries)
  const [draft, setDraft] = useState<SectionValues[] | undefined>(undefined)
  return (
    <section aria-label={`${layout} ${name}`} className="flex flex-col gap-4 border-t border-border pt-4">
      <h2 className="flex items-baseline gap-2 text-sm">
        <span className="font-semibold">{layout}</span>
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
