import { useRef } from "react"

import type { ConfigDocument, PluginFile, PluginFileView } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { usePluginFile, useSavePluginFile } from "@/lib/queries"
import { Failure, QueryState } from "@/components/steward/query-state"
import { SaveDraft } from "@/components/steward/group-draft"
import { DraftForm } from "@/components/steward/settings-config"
import { BackToTop, PinnedRow, type Target } from "@/components/steward/settings-view"
import { CodeEditor } from "@/components/steward/code-editor"
import { t } from "@/lib/texts"

/**
 * A plugin's file opens as a form when Steward can rewrite its values in place, else as its text. Either way the server reads it at its next start.
 */
export function PluginFileEditor({
  id,
  file,
  label,
  target,
}: {
  id: string
  file: PluginFile
  label: string
  target: Target | null
}) {
  const view = usePluginFile(file.service, file.plugin, file.path)
  return (
    <QueryState query={view} rows={8}>
      {(read) =>
        read.entries ? (
          <PluginForm id={id} label={label} view={read} entries={read.entries} target={target} />
        ) : (
          <PluginText id={id} label={label} view={read} />
        )
      }
    </QueryState>
  )
}

function PluginForm({
  id,
  label,
  view,
  entries,
  target,
}: {
  id: string
  label: string
  view: PluginFileView
  entries: NonNullable<PluginFileView["entries"]>
  target: Target | null
}) {
  const { file } = view
  const save = useSavePluginFile(file.service, file.plugin, file.path)
  const document: ConfigDocument = {
    service: file.service,
    name: label,
    path: id,
    label,
    live: false,
    readable: file.readable,
    writable: file.writable,
    revision: view.revision,
    restartRequired: true,
    entries,
  }
  return <DraftForm file={id} document={document} target={target} save={save} />
}

function isTextDraft(value: unknown): value is Record<string, string> {
  return typeof value === "object" && value !== null && Object.values(value).every((text) => typeof text === "string")
}

function PluginText({ id, label, view }: { id: string; label: string; view: PluginFileView }) {
  const { file } = view
  const save = useSavePluginFile(file.service, file.plugin, file.path)
  const draft = useDraft(id, isTextDraft).text
  const top = useRef<HTMLParagraphElement>(null)
  const notice = file.writable ? t("steward.settings.restart-needed") : t("steward.settings.read-only")

  function submit() {
    if (draft === undefined) return
    save.mutate(
      { revision: view.revision, text: draft },
      {
        onSuccess: (saved) => {
          clearDraft(id)
          announceSave(t("steward.settings.file-saved", { file: label }), saved.reload, label)
        },
      },
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <p ref={top} className="text-sm text-muted-foreground">
        {notice}
      </p>
      {save.error ? <Failure error={save.error} /> : null}
      <CodeEditor
        path={file.path}
        label={label}
        value={draft ?? view.text}
        readOnly={!file.writable}
        onChange={(text) => setDraftValue(id, "text", text === view.text ? undefined : text)}
      />
      <PinnedRow>
        <BackToTop at={top} />
        <SaveDraft
          count={draft === undefined ? 0 : 1}
          writable={file.writable}
          pending={save.isPending}
          onSave={submit}
          onDiscard={() => clearDraft(id)}
        />
      </PinnedRow>
    </div>
  )
}
