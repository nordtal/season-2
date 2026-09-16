import { Fragment, useEffect, useMemo, useRef, useState } from "react"
import type { CSSProperties } from "react"
import {
  Ban,
  ChevronDown,
  ChevronRight,
  FileWarning,
  Lock,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react"
import { toast } from "sonner"

import type {
  ConfigChanges,
  ConfigEntry,
  ConfigLocation,
  ConfigReloadOutcome,
  EditableRawConfigDocument,
  GuildList,
  ParsedConfigDocument,
  RawConfigDocument,
  ReloadAwareConfigDocument,
} from "@/lib/api"
import {
  useConfig,
  useConfigs,
  useGuildChannels,
  useGuildRoles,
  useSaveConfig,
} from "@/lib/queries"
import { takePendingJump } from "@/lib/settings-search"
import { explanationOf, humanFileName, ScalarControl } from "@/components/steward/config-controls"
import { ServiceSettingsSearch } from "@/components/steward/config-search"
import { RawConfigEditor } from "@/components/steward/raw-config-editor"
import { Empty, Failure, QueryState } from "@/components/steward/query-state"
import {
  type SectionValues,
  RepeatableCards,
  sectionsFromEntry,
} from "@/components/steward/repeatable-cards"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"

/**
 * `discordId` used to be defined here and stayed exported under this name for
 * `snowflake-picker.test.tsx`, which imports it from this module. The implementation moved to
 * `config-controls.tsx` (steward/57) so `repeatable-cards.tsx` could use it too without importing
 * this file back - a card's own fields need the same "is this a role or a channel" heuristic as a
 * top-level key, and a cycle between the two files would follow from importing it the other way.
 */
export { discordId } from "@/components/steward/config-controls"

/**
 * A service's configuration files, on the service's own page (concept §10a).
 *
 * **There was a page of its own for this until 2026-09-14 and it is gone.** Every file the mount
 * holds sits under exactly one service directory - twenty-five of them, counted in the running
 * container that day, and not one loose - so a second place that listed them all was a second
 * place to look for something that had a home already. The listing, the form and the service it
 * belongs to are now one screen, which is also the screen with the restart button on it: a saved
 * change reaches nothing until the service starts again, and that is now one card away rather
 * than one page away.
 *
 * **The file is the model, not a `@ConfigSpec`.** Drawing this out of the Java interfaces would
 * mean steward-ui depending on every module in the repo, one of which would put a Paper API on a
 * web server's classpath. jcore writes its `@Comment`s into the YAML, so a file it wrote documents
 * itself and the backend reads the form straight out of it.
 *
 * Labels are the readable ones Till asked for - "Base url", not `worker.base-url` - with the
 * machine-readable path kept in monospace beside them, because the path is what an error message
 * and a log line will name.
 */

/** The marker for a key the file has but the schema does not mention (steward/50, steward/55). */
function NotInSchemaBadge() {
  return (
    <Badge variant="outline" className="shrink-0 gap-1 text-muted-foreground">
      not in schema
    </Badge>
  )
}

export function ServiceConfiguration({ service }: { service: string }) {
  // Which file is open, not whether one is. Only the open file is fetched, which is also why the
  // form is mounted rather than hidden: an unopened file is a request nobody made.
  const [open, setOpen] = useState<string | null>(null)
  // Which entry a hit should land on and light up (steward/58) - `null` the rest of the time, and
  // cleared by the Field itself once it has scrolled to it and shown it for a moment.
  const [highlight, setHighlight] = useState<string | null>(null)
  const files = useConfigs()

  // A hit found by the command palette's global search (steward/58) left its destination here
  // before navigating, since this component is mounted fresh on arrival rather than handed a prop
  // for it. Runs on mount and again whenever `service` changes under an already-mounted page - both
  // are "arriving at this service" as far as a pending jump is concerned.
  useEffect(() => {
    const jump = takePendingJump(service)
    if (jump) {
      setOpen(jump.file)
      setHighlight(jump.path)
    }
  }, [service])
  // A file with no service is one sitting directly in the mount point rather than in a service's
  // directory, and there were none of those on 2026-09-14 - all twenty-five were under exactly one
  // service. It is shown here anyway rather than filtered into nothing, because `/configs` is
  // steward-ui's own mount: if a stray one ever appears, this is the page it arrived on, and a
  // config file that no page lists is a config file nobody can find.
  const mine = useMemo(
    () =>
      (files.data ?? []).filter(
        (file) => file.service === service || (file.service === "" && service === "steward-ui"),
      ),
    [files.data, service],
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>Configuration</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col">
        <ServiceSettingsSearch
          files={mine}
          onJump={(file, path) => {
            setOpen(file)
            setHighlight(path)
          }}
        />
        <QueryState
          query={files}
          rows={3}
          isEmpty={() => mine.length === 0}
          empty={{
            title: "This service has no configuration file here.",
            note: `Nothing under ${service}/ in the mount point is a readable text file. Its settings are somewhere else, or it has none.`,
          }}
        >
          {() =>
            mine.map((file) => (
              <Fragment key={file.path}>
                <FileRow
                  file={file}
                  open={open === file.path}
                  onToggle={() => setOpen((current) => (current === file.path ? null : file.path))}
                />
                {open === file.path ? (
                  <OneFile
                    file={file.path}
                    highlight={highlight}
                    onHighlighted={() => setHighlight(null)}
                  />
                ) : null}
              </Fragment>
            ))
          }
        </QueryState>
      </CardContent>
    </Card>
  )
}

function FileRow({
  file,
  open,
  onToggle,
}: {
  file: ConfigLocation
  open: boolean
  onToggle: () => void
}) {
  const Chevron = open ? ChevronDown : ChevronRight

  return (
    <button
      type="button"
      aria-expanded={open}
      onClick={onToggle}
      // Not openable rather than openable-into-an-error. There is nothing behind it: the service
      // cannot read the file, so the form would be an alert with a path in it.
      disabled={!file.readable}
      title={
        file.readable
          ? undefined
          : "Steward can see this file but may not open it. It belongs to another user - the mount that would let Steward read it is missing, or its permissions changed."
      }
      className="-mx-3 flex h-row items-center justify-between gap-3 rounded-sm px-3 text-left hover:bg-accent disabled:cursor-not-allowed disabled:opacity-70 disabled:hover:bg-transparent"
    >
      <span className="flex min-w-0 items-center gap-2">
        <Chevron className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="flex min-w-0 flex-col">
          <span className="truncate text-sm">{humanFileName(file.name)}</span>
          <span className="truncate font-mono text-xs text-muted-foreground">{file.name}</span>
        </span>
        {file.service === "" ? (
          <Badge variant="outline" className="shrink-0">
            no service
          </Badge>
        ) : null}
      </span>
      {/*
        Three states, not two. "not readable" is the one that used to be invisible: the row looked
        ordinary and opening it produced an error alert with the file's path in it.
      */}
      {!file.readable ? (
        <Badge variant="outline" className="shrink-0 gap-1 text-destructive">
          <Lock className="size-3" aria-hidden />
          not readable
        </Badge>
      ) : file.writable ? null : (
        <Badge variant="outline" className="shrink-0 gap-1">
          <Lock className="size-3" aria-hidden />
          read only
        </Badge>
      )}
    </button>
  )
}

function OneFile({
  file,
  highlight,
  onHighlighted,
}: {
  file: string
  /** The entry path a search hit (steward/58) landed on, or `null` the rest of the time. */
  highlight: string | null
  onHighlighted: () => void
}) {
  const document = useConfig(file)

  return (
    <div className="border-t border-border pt-4 pb-6">
      <QueryState query={document} rows={8}>
        {(read) =>
          // `raw` splits the two forms this route ever answers with (steward/56): a document this
          // class parsed, which gets the whole form below, and a file it could not - a foreign one
          // steward/55's broadened `discover()` now finds, or a `.yml` with a mistake in it - which
          // gets its own component so the hooks below stay unconditional rather than depending on
          // which shape the same `file` happened to come back as. A raw document has no entries, so
          // a search hit never points into one and `highlight` has nothing to do here.
          read.raw ? (
            <RawConfigView key={file} file={file} document={read} />
          ) : (
            <ConfigForm
              key={file}
              file={file}
              // The worker sends `restartRequired` on every GET as well as every PUT (steward/59);
              // `ParsedConfigDocument` itself is left alone because other work lands in this file
              // tonight, so the widened shape is its own type rather than a change to that one.
              document={read as ReloadAwareConfigDocument}
              highlight={highlight}
              onHighlighted={onHighlighted}
            />
          )
        }
      </QueryState>
    </div>
  )
}

/**
 * A file steward could not read as YAML, shown exactly as it stands on disk (steward/56) - and,
 * since steward/60, editable as the plain text it is when the mount underneath it allows a write
 * at all. There is nothing here this class parsed, so there is no form and no per-field save; the
 * whole file is one draft, checked for the syntax its own name implies only once the operator
 * saves it, and never refused for what that check finds. See `RawConfigEditor` for the rest of
 * this - format detection, highlighting, the save itself and its warnings all live there so this
 * function stays just the hand-off steward/56 left it as.
 */
function RawConfigView({ file, document }: { file: string; document: RawConfigDocument }) {
  return (
    <RawConfigEditor
      file={file}
      // The worker sends `revision` on every raw document too, since steward/60 gave this shape a
      // save path of its own (`ConfigApi#rawDocument`). `RawConfigDocument` itself is left alone,
      // the same way `ParsedConfigDocument` is above: other work lands in this file the same
      // night, so the widened shape is its own type in api.ts rather than a change to this one.
      document={document as EditableRawConfigDocument}
    />
  )
}

type Draft = Record<string, string | string[] | SectionValues[]>

function ConfigForm({
  file,
  document,
  highlight,
  onHighlighted,
}: {
  file: string
  document: ReloadAwareConfigDocument
  highlight: string | null
  onHighlighted: () => void
}) {
  const [draft, setDraft] = useState<Draft>({})
  const save = useSaveConfig(file)
  // Asked for on every config file, not only the bot's: both answers are cached for five minutes
  // and come back `available: false` in one round trip when there is no token, which is cheaper
  // than working out per file whether any key on it might turn out to be a snowflake.
  const roles = useGuildRoles()
  const channels = useGuildChannels()

  // The answer to a save IS the file as it now reads, so a successful write replaces the document
  // and empties the form's own state. Anything the backend quoted differently is then on screen,
  // rather than the value this browser hoped it had written.
  useEffect(() => setDraft({}), [document])

  const changes = useMemo(() => changed(document, draft), [document, draft])
  const count = Object.keys(changes).length

  // database.yml holds what this service connects to Postgres with, and there is no health check
  // between "saved" and "broken" the way there is for a plugin that gets recreated - a mistake here
  // takes the datasource down directly. Till has not asked for a way to edit it from this page, so
  // until he does it stays visibly read-only regardless of what the mount underneath it actually
  // permits (steward/56) - the generic "mounted read-only" sentence below is true of a volume, and
  // this is stronger than that on purpose.
  //
  // Matched on the LAST path segment, not on the whole name. `name` is the path under the service
  // directory, so a plugin's file is `smp/database.yml` or `hunger-games/hunger-games/database.yml`
  // and only the three services that keep theirs at the top - discord-bot, steward-worker,
  // steward-ui - are called `database.yml` outright. Measured against the running mount on
  // 2026-09-16: an equality check caught three of the seven and left four editable.
  const databaseFile = document.name.split("/").pop() === "database.yml"
  const writable = document.writable && !databaseFile

  function submit() {
    const label = count === 1 ? "One setting saved." : `${count} settings saved.`
    save.mutate({ revision: document.revision, changes }, {
      // One click, one request - saving already asks the affected service to pick the change up
      // (steward/59), so there is no second button here for "now actually use it". `reload` says
      // which of the three outcomes that request had, and the three must not read alike: applied
      // and unanswered both sent a command, a restart requirement sent none at all.
      onSuccess: (saved) => announceSave(label, saved.reload, document.name),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      {document.header.length > 0 ? (
        <p className="whitespace-pre-wrap text-sm text-muted-foreground">
          {document.header.join("\n").trim()}
        </p>
      ) : null}

      {databaseFile ? (
        <Alert variant="destructive">
          <Ban aria-hidden />
          <AlertTitle>This file is read-only in Steward.</AlertTitle>
          <AlertDescription>
            {document.name} holds what this service connects to Postgres with. Steward shows it so
            it can be checked, and never offers to save a change to it here - edit it on the host
            if it ever has to change.
          </AlertDescription>
        </Alert>
      ) : writable && document.restartRequired ? (
        <Alert>
          <FileWarning aria-hidden />
          <AlertTitle>Saving here does not reach a running service.</AlertTitle>
          <AlertDescription>
            {/* It used to link to the service page. This IS the service page now, so the sentence
                points at the button rather than at the page the reader is standing on. Named here,
                not only in the toast after a save (steward/59's third case): a setting nothing
                reloads live says so before anybody has typed a change into it. */}
            Nothing here reloads live. It is written to the file, and{" "}
            {document.service || "the service"} reads it again only at its next start - the
            Recreate button at the top of this page is what does that.
          </AlertDescription>
        </Alert>
      ) : writable ? (
        <Alert>
          <FileWarning aria-hidden />
          <AlertTitle>Saving also reloads it.</AlertTitle>
          <AlertDescription>
            A save here is sent straight to {document.service || "the service"}'s own console, so
            the change is live as soon as it is saved - no restart, and no second button. What the
            save itself found out about that appears as its own message underneath.
          </AlertDescription>
        </Alert>
      ) : (
        <Alert>
          <Lock aria-hidden />
          <AlertTitle>This file is mounted read-only.</AlertTitle>
          <AlertDescription>
            The values are readable, the form accepts no change. That is a property of the volume,
            not of this interface.
          </AlertDescription>
        </Alert>
      )}

      {save.error ? <Failure error={save.error} /> : null}

      <div className="flex flex-col gap-0">
        {document.entries.length === 0 ? (
          <Empty
            title="This file has no keys."
            note="It is empty, or consists only of comments."
          />
        ) : (
          document.entries.map((entry, index) => (
            <Field
              key={entry.path}
              entry={entry}
              first={index === 0}
              writable={writable}
              draft={draft}
              roles={roles.data}
              channels={channels.data}
              highlighted={entry.path === highlight}
              onHighlighted={onHighlighted}
              onChange={(value) => setDraft((old) => ({ ...old, [entry.path]: value }))}
              onReset={() =>
                setDraft((old) => {
                  const next = { ...old }
                  delete next[entry.path]
                  return next
                })
              }
            />
          ))
        )}
      </div>

      <div className="sticky bottom-0 flex flex-wrap items-center justify-between gap-3 border-t border-border bg-background/95 py-3 backdrop-blur">
        <p className="text-sm text-muted-foreground">
          {count === 0
            ? "Nothing changed."
            : count === 1
              ? "One setting changed."
              : `${count} settings changed.`}
        </p>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={count === 0 || save.isPending}
            onClick={() => setDraft({})}
          >
            Discard
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={count === 0 || save.isPending || !writable}
            onClick={submit}
          >
            {save.isPending ? "Saving…" : "Save"}
          </Button>
        </div>
      </div>
    </div>
  )
}

/**
 * The one toast a save produces, shaped by what `reload` says happened (steward/59).
 *
 * Three outcomes, three different toast types - not three variations of the same green success,
 * which is exactly the "look alike" the ticket rules out. `APPLIED` and `NO_ANSWER` both mean a
 * command was actually sent; only the message says which. `undefined` is the GET-era shape kept
 * for a document nothing has re-fetched yet, and is treated the same as `APPLIED` was always
 * shown: a plain confirmation with the file's name.
 */
function announceSave(
  label: string,
  reload: ConfigReloadOutcome | undefined,
  fallbackDescription: string,
) {
  if (!reload || reload.status === "APPLIED") {
    toast.success(label, { description: reload?.message ?? fallbackDescription })
    return
  }
  if (reload.status === "NO_ANSWER") {
    toast.warning(label, { description: reload.message })
    return
  }
  toast.info(label, { description: reload.message })
}

/**
 * What the form would send: only the keys that actually differ from the file.
 *
 * Sending everything would be simpler and would rewrite every line of the file on every save,
 * which turns a one-word change into a diff nobody reads.
 */
function changed(document: ParsedConfigDocument, draft: Draft): ConfigChanges {
  const changes: ConfigChanges = {}
  for (const entry of document.entries) {
    const value = draft[entry.path]
    if (value === undefined) continue
    if (entry.kind === "LIST") {
      if (JSON.stringify(value) !== JSON.stringify(entry.items ?? [])) {
        changes[entry.path] = value
      }
      continue
    }
    // A card's whole list of sections (steward/57) is sent the same way a plain LIST is: as one
    // value under the parent path, compared whole against the sections the file itself held - a
    // removed card is invisible in a diff of individual keys, since there is no key left to differ.
    if (entry.kind === "SECTIONS") {
      if (JSON.stringify(value) !== JSON.stringify(sectionsFromEntry(entry))) {
        changes[entry.path] = value as SectionValues[]
      }
      continue
    }
    // A secret has no value here to compare against, so any typed value is a change - including an
    // empty one, which empties it. The field says so out loud rather than doing it quietly.
    if (entry.secret || value !== (entry.value ?? "")) {
      changes[entry.path] = value
    }
  }
  return changes
}

// -----------------------------------------------------------------------------------------------
// One key
// -----------------------------------------------------------------------------------------------

function Field({
  entry,
  first,
  writable,
  draft,
  roles,
  channels,
  highlighted = false,
  onHighlighted,
  onChange,
  onReset,
}: {
  entry: ConfigEntry
  first: boolean
  writable: boolean
  draft: Draft
  roles: GuildList | undefined
  channels: GuildList | undefined
  /** A search hit (steward/58) landed on this exact entry. */
  highlighted?: boolean
  onHighlighted?: () => void
  onChange: (value: string | string[] | SectionValues[]) => void
  onReset: () => void
}) {
  const depth = entry.path.split(".").length - 1
  const dirty = draft[entry.path] !== undefined
  const explanation = explanationOf(entry)
  const ref = useRef<HTMLDivElement>(null)

  // Jump-and-highlight, not just "the file opened" (steward/58's fourth requirement). Scrolls once,
  // on the render where `highlighted` turns true, and clears itself after a moment so the ring does
  // not linger once the point has been made - `onHighlighted` is what tells the parent to forget it.
  useEffect(() => {
    if (!highlighted) return
    ref.current?.scrollIntoView({ behavior: "smooth", block: "center" })
    const timeout = window.setTimeout(() => onHighlighted?.(), 2400)
    return () => window.clearTimeout(timeout)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlighted])

  if (entry.kind === "MAP") {
    // Headings come purely from the file's own nesting (steward/56) - there is no second grouping
    // concept beside it. Past two levels a heading stops helping and starts being mostly indent, so
    // the fallback is what a leaf field already shows beside its own label: the full dotted path,
    // which names the third level and deeper without a heading of its own.
    if (depth >= 2) return null
    return (
      <div
        style={{ "--depth": depth } as CSSProperties}
        className="ml-[calc(var(--depth)*0.375rem)] pt-6 pb-2 sm:ml-[calc(var(--depth)*1rem)]"
      >
        <Separator className="mb-4" />
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-sm font-semibold">{entry.label}</h2>
          {!entry.inSchema ? <NotInSchemaBadge /> : null}
        </div>
        <p className="font-mono text-xs text-muted-foreground">{entry.path}</p>
        {explanation ? (
          <p className="mt-2 max-w-prose whitespace-pre-wrap text-sm text-muted-foreground">
            {explanation}
          </p>
        ) : null}
      </div>
    )
  }

  const disabled = !writable || !entry.editable

  return (
    <div
      ref={ref}
      // The nesting of the YAML, given back as an indent - and a third of one on a phone, where
      // three levels of 16px is a tenth of the screen spent on saying "this key is inside that
      // one". An inline `marginLeft` cannot answer a media query, so the depth is a variable and
      // the two widths are a class.
      style={{ "--depth": depth } as CSSProperties}
      className={`ml-[calc(var(--depth)*0.375rem)] flex scroll-mt-4 flex-col gap-2 rounded-md border-border py-3 transition-colors duration-300 sm:ml-[calc(var(--depth)*1rem)] ${first ? "" : "border-t"} ${highlighted ? "-mx-3 bg-accent px-3 ring-2 ring-primary" : ""}`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="flex flex-wrap items-center gap-2">
          <Label htmlFor={entry.path} className="text-sm font-medium">
            {entry.label}
          </Label>
          {!entry.inSchema ? <NotInSchemaBadge /> : null}
        </span>
        <div className="flex items-center gap-2">
          {dirty ? (
            <Button type="button" variant="ghost" size="sm" onClick={onReset}>
              <RotateCcw aria-hidden />
              Reset
            </Button>
          ) : null}
          <span className="font-mono text-xs text-muted-foreground">{entry.path}</span>
        </div>
      </div>

      {explanation ? (
        <p className="max-w-prose whitespace-pre-wrap text-sm text-muted-foreground">
          {explanation}
        </p>
      ) : null}

      <Control
        entry={entry}
        draft={draft}
        disabled={disabled}
        roles={roles}
        channels={channels}
        onChange={onChange}
      />

      {!entry.editable ? (
        <p className="text-sm text-muted-foreground">
          This row is not written here - it is a list of sections. Line {entry.line} of the file.
        </p>
      ) : null}
    </div>
  )
}

/**
 * Which control an entry gets: the repeatable cards for a `SECTIONS` entry (steward/57), the plain
 * scalar list rows for a `LIST`, or a leaf's own scalar control - a secret, a schema's choices, a
 * Discord id, a boolean or plain text, in that order of precedence. The leaf branch is
 * `ScalarControl` in `config-controls.tsx`, shared with a field drawn inside a card, so the two
 * never drift apart over what a "choices" or a "boolean" looks like.
 */
function Control({
  entry,
  draft,
  disabled,
  roles,
  channels,
  onChange,
}: {
  entry: ConfigEntry
  draft: Draft
  disabled: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: string | string[] | SectionValues[]) => void
}) {
  if (entry.kind === "LIST") {
    const items = (draft[entry.path] as string[] | undefined) ?? entry.items ?? []
    return <ListControl id={entry.path} items={items} disabled={disabled} onChange={onChange} />
  }

  if (entry.kind === "SECTIONS") {
    const value = (draft[entry.path] as SectionValues[] | undefined) ?? sectionsFromEntry(entry)
    return (
      <RepeatableCards
        entry={entry}
        value={value}
        disabled={disabled}
        roles={roles}
        channels={channels}
        onChange={onChange}
      />
    )
  }

  const typed = draft[entry.path] as string | undefined
  const value = typed ?? entry.value ?? ""

  return (
    <ScalarControl
      id={entry.path}
      entry={entry}
      value={value}
      edited={typed !== undefined}
      disabled={disabled}
      roles={roles}
      channels={channels}
      onChange={onChange}
    />
  )
}

/**
 * A list, one row per entry.
 *
 * The whole list is sent on save rather than a single added entry: two browsers sending "add one"
 * both succeed and the result is neither of the two lists anybody was looking at. Sending the list
 * is only half of it - the two saves would still have overwritten each other, one silently - and
 * the other half is the `revision` every save carries, which makes the second one a 409.
 */
function ListControl({
  id,
  items,
  disabled,
  onChange,
}: {
  id: string
  items: string[]
  disabled: boolean
  onChange: (items: string[]) => void
}) {
  return (
    <div className="flex flex-col gap-2">
      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Empty list.</p>
      ) : (
        items.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              id={index === 0 ? id : undefined}
              disabled={disabled}
              value={item}
              spellCheck={false}
              className="font-mono text-sm"
              aria-label={`Entry ${index + 1}`}
              onChange={(event) =>
                onChange(items.map((old, at) => (at === index ? event.target.value : old)))
              }
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={disabled}
              aria-label={`Remove entry ${index + 1}`}
              onClick={() => onChange(items.filter((_, at) => at !== index))}
            >
              <Trash2 aria-hidden />
            </Button>
          </div>
        ))
      )}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => onChange([...items, ""])}
        >
          <Plus aria-hidden />
          Add entry
        </Button>
      </div>
    </div>
  )
}
