import { Fragment, useEffect, useMemo, useState } from "react"
import type { CSSProperties } from "react"
import {
  Ban,
  ChevronDown,
  ChevronRight,
  FileCode,
  FileWarning,
  Lock,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react"
import { toast } from "sonner"

import type {
  ConfigChanges,
  ConfigChoices,
  ConfigEntry,
  ConfigLocation,
  GuildList,
  ParsedConfigDocument,
  RawConfigDocument,
} from "@/lib/api"
import {
  useConfig,
  useConfigs,
  useGuildChannels,
  useGuildRoles,
  useSaveConfig,
} from "@/lib/queries"
import { Empty, Failure, QueryState } from "@/components/steward/query-state"
import { SnowflakePicker } from "@/components/steward/snowflake-picker"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"

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

/**
 * The plain-text file name Till asked for, instead of `nordtal-smp/config.yml` verbatim
 * (steward/56) - mechanical, the same way `Labels.of` on the backend turns a YAML key into a
 * label: strip the extension, split on the characters a path uses to separate words, lower-case
 * them, capitalise the first letter of the result. The raw name is still shown beside it, in
 * monospace, because a path is exactly what an error message or a support request will name.
 */
function humanFileName(name: string): string {
  const withoutExtension = name.replace(/\.[a-z0-9]+$/i, "")
  const words = withoutExtension.split(/[-_./]+/).filter(Boolean)
  if (words.length === 0) return name
  const joined = words.map((word) => word.toLowerCase()).join(" ")
  return joined.charAt(0).toUpperCase() + joined.slice(1)
}

/**
 * The short text under a label: the schema's own words when there is a schema, the mechanical
 * comment block for a file with none, and nothing at all when the schema explicitly says there is
 * nothing to add (steward/55, steward/56). Three states, not two - `noExplanationNeeded` and "no
 * comment happened to be written above this key" must not read the same, and only the first of
 * them is drawn as literally no text regardless of what either string holds.
 */
function explanationOf(entry: ConfigEntry): string | null {
  if (entry.noExplanationNeeded) return null
  if (entry.explanation) return entry.explanation
  if (entry.comments.length > 0) return entry.comments.join("\n").trim()
  return null
}

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
  const files = useConfigs()
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
                {open === file.path ? <OneFile file={file.path} /> : null}
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

function OneFile({ file }: { file: string }) {
  const document = useConfig(file)

  return (
    <div className="border-t border-border pt-4 pb-6">
      <QueryState query={document} rows={8}>
        {(read) =>
          // `raw` splits the two forms this route ever answers with (steward/56): a document this
          // class parsed, which gets the whole form below, and a file it could not - a foreign one
          // steward/55's broadened `discover()` now finds, or a `.yml` with a mistake in it - which
          // gets its own component so the hooks below stay unconditional rather than depending on
          // which shape the same `file` happened to come back as.
          read.raw ? (
            <RawConfigView key={file} document={read} />
          ) : (
            <ConfigForm key={file} file={file} document={read} />
          )
        }
      </QueryState>
    </div>
  )
}

/**
 * A file steward could not read as YAML, shown exactly as it stands on disk (steward/56).
 *
 * No form, no save button: there is nothing here this class parsed, so there is nothing a save
 * could be checked against. This is the fallback for a genuinely foreign file - a plugin's
 * `README.txt`, a `.properties` file - and for an ordinary `.yml` with a mistake in it; either way
 * the operator can still read the bytes without them being a 400 with a path in it.
 */
function RawConfigView({ document }: { document: RawConfigDocument }) {
  return (
    <div className="flex flex-col gap-4">
      <Alert>
        <FileCode aria-hidden />
        <AlertTitle>Shown as raw text.</AlertTitle>
        <AlertDescription>
          Steward could not read {humanFileName(document.name)} ({document.name}) as a config file
          {document.reason ? `: ${document.reason}` : "."} It is shown exactly as it stands on disk
          and cannot be edited here.
        </AlertDescription>
      </Alert>
      <Textarea
        readOnly
        value={document.content}
        spellCheck={false}
        rows={Math.min(30, document.content.split("\n").length + 1)}
        className="font-mono text-sm"
      />
    </div>
  )
}

type Draft = Record<string, string | string[]>

function ConfigForm({ file, document }: { file: string; document: ParsedConfigDocument }) {
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
    save.mutate({ revision: document.revision, changes }, {
      onSuccess: () =>
        toast.success(
          count === 1 ? "One setting saved." : `${count} settings saved.`,
          { description: document.name },
        ),
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
      ) : writable ? (
        <Alert>
          <FileWarning aria-hidden />
          <AlertTitle>A saved change does not reach a running service.</AlertTitle>
          <AlertDescription>
            {/* It used to link to the service page. This IS the service page now, so the sentence
                points at the button rather than at the page the reader is standing on. */}
            It is in the file, and {document.service || "the service"} reads it at its next start -
            the Recreate button at the top of this page is what does that.
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
  onChange,
  onReset,
}: {
  entry: ConfigEntry
  first: boolean
  writable: boolean
  draft: Draft
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: string | string[]) => void
  onReset: () => void
}) {
  const depth = entry.path.split(".").length - 1
  const dirty = draft[entry.path] !== undefined
  const explanation = explanationOf(entry)

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
      // The nesting of the YAML, given back as an indent - and a third of one on a phone, where
      // three levels of 16px is a tenth of the screen spent on saying "this key is inside that
      // one". An inline `marginLeft` cannot answer a media query, so the depth is a variable and
      // the two widths are a class.
      style={{ "--depth": depth } as CSSProperties}
      className={`ml-[calc(var(--depth)*0.375rem)] flex flex-col gap-2 border-border py-3 sm:ml-[calc(var(--depth)*1rem)] ${first ? "" : "border-t"}`}
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
 * Which keys hold a Discord id, and whether it is a role or a channel.
 *
 * It is decided on the KEY, not on the value, because the whole point is to help with a key that is
 * still empty - a value-shaped test would offer the picker only once somebody had already typed the
 * thing they needed help typing. The names are the ones jcore writes: `roles.admin`,
 * `channels.admin`, and on each language entry `role`, `contribution-channel`, `link-channel`,
 * `hunger-games-channel`, `status-channel`, `announcement-channel`.
 *
 * `guild-id` matches none of them, and that is the intended answer rather than an oversight: the
 * guild is what the list is READ FROM, so offering to pick it out of itself is circular and would
 * draw an empty select on the one field that always has to be typed. It is asserted in the tests
 * so a later rule - anything keyed on `-id`, say - cannot quietly acquire it.
 */
export function discordId(entry: ConfigEntry): "role" | "channel" | null {
  if (entry.kind !== "SCALAR" || !entry.editable || entry.secret) return null
  const key = entry.key
  const path = entry.path
  if (key === "role" || key.endsWith("-role") || path.startsWith("roles.")) return "role"
  if (key === "channel" || key.endsWith("-channel") || path.startsWith("channels.")) return "channel"
  return null
}

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
  onChange: (value: string | string[]) => void
}) {
  if (entry.kind === "LIST") {
    const items = (draft[entry.path] as string[] | undefined) ?? entry.items ?? []
    return <ListControl id={entry.path} items={items} disabled={disabled} onChange={onChange} />
  }

  const typed = draft[entry.path] as string | undefined

  if (entry.secret) {
    return (
      <div className="flex flex-col gap-1.5">
        <Input
          id={entry.path}
          type="password"
          autoComplete="off"
          disabled={disabled}
          value={typed ?? ""}
          placeholder={entry.filled ? "set - type a new one to replace it" : "empty"}
          onChange={(event) => onChange(event.target.value)}
        />
        <p className="text-sm text-muted-foreground">
          {typed === ""
            ? "Saving it empty deletes this secret from the file."
            : "The stored value is never sent to the browser. It can be overwritten, not read back."}
        </p>
      </div>
    )
  }

  const value = typed ?? entry.value ?? ""

  // A schema's allowed values (steward/55, steward/56) win over the Discord picker below: they are
  // the more specific of the two, being data this particular key actually declared rather than a
  // guess drawn from its name.
  if (entry.choices) {
    return (
      <ChoicesControl
        id={entry.path}
        value={value}
        choices={entry.choices}
        disabled={disabled}
        onChange={onChange}
      />
    )
  }

  const discord = discordId(entry)
  if (discord) {
    return (
      <SnowflakePicker
        id={entry.path}
        value={value}
        directory={discord === "role" ? roles : channels}
        what={discord}
        disabled={disabled}
        onChange={onChange}
      />
    )
  }

  if (entry.type === "BOOLEAN") {
    return (
      <div className="flex items-center gap-3">
        <Switch
          id={entry.path}
          disabled={disabled}
          checked={value === "true"}
          onCheckedChange={(on) => onChange(on ? "true" : "false")}
        />
        <span className="text-sm text-muted-foreground">{value === "true" ? "on" : "off"}</span>
      </div>
    )
  }

  // A value that already spans lines keeps a box it fits in. Typing a newline into the single-line
  // field is allowed too - the backend turns it into a block scalar - but nobody would find that.
  if (value.includes("\n")) {
    return (
      <Textarea
        id={entry.path}
        rows={Math.min(16, value.split("\n").length + 1)}
        disabled={disabled}
        value={value}
        spellCheck={false}
        className="font-mono text-sm"
        onChange={(event) => onChange(event.target.value)}
      />
    )
  }

  return (
    <Input
      id={entry.path}
      disabled={disabled}
      value={value}
      inputMode={entry.type === "INTEGER" || entry.type === "DECIMAL" ? "decimal" : undefined}
      spellCheck={false}
      className="font-mono text-sm"
      onChange={(event) => onChange(event.target.value)}
    />
  )
}

/**
 * A schema's allowed (or suggested) values (steward/55, steward/56).
 *
 * `strict` is the whole of the difference: a closed list is a select and nothing else, because
 * anything else it could hold is not a valid save. A suggestion is the same select beside a
 * free-text field that still takes anything - so the common case is a click and the uncommon one
 * is still just typing, the way it always was.
 */
function ChoicesControl({
  id,
  value,
  choices,
  disabled,
  onChange,
}: {
  id: string
  value: string
  choices: ConfigChoices
  disabled: boolean
  onChange: (value: string) => void
}) {
  // Radix refuses an item with an empty value, and a value the schema did not list is a normal
  // state here - typed by hand before this shipped, or (when not strict) simply a suggestion not
  // taken. Passing it through as "" leaves the select showing its placeholder rather than a value
  // it does not have.
  const known = choices.values.includes(value)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Select value={known ? value : ""} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={choices.strict ? id : undefined} className="min-w-48">
          <SelectValue placeholder={choices.strict ? "choose one" : "choose a suggestion"} />
        </SelectTrigger>
        <SelectContent>
          {choices.values.map((option) => (
            <SelectItem key={option} value={option}>
              {option}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {choices.strict ? null : (
        <Input
          id={id}
          aria-label="Free text"
          disabled={disabled}
          value={value}
          spellCheck={false}
          className="min-w-40 flex-1 font-mono text-sm"
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </div>
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
