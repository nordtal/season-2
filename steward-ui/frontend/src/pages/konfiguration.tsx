import { useEffect, useMemo, useState } from "react"
import { Link, useParams } from "@tanstack/react-router"
import { FileWarning, Lock, Plus, RotateCcw, Trash2 } from "lucide-react"
import { toast } from "sonner"

import type { ConfigChanges, ConfigDocument, ConfigEntry } from "@/lib/api"
import { useConfig, useConfigs, useSaveConfig } from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { Empty, Failure, QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"

/**
 * Every configuration in the stack, as a form (concept §10a).
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

// -----------------------------------------------------------------------------------------------
// The list of files
// -----------------------------------------------------------------------------------------------

export function KonfigurationPage() {
  const files = useConfigs()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Konfiguration"
        note="Die Konfigurationsdateien aller Dienste des Stacks, so wie sie auf der Platte stehen."
      />

      <QueryState
        query={files}
        rows={6}
        isEmpty={(found) => found.length === 0}
        empty={{
          title: "Keine Konfigurationsdatei gefunden.",
          note: "Unter dem Einhängepunkt liegt nichts, was auf .yml endet. Sind die Konfigurationsvolumes in dieses Containerabbild eingehängt?",
        }}
      >
        {(found) => (
          <div className="flex flex-col gap-4">
            {group(found).map(([service, entries]) => (
              <Card key={service || "(Wurzel)"}>
                <CardHeader>
                  <CardTitle className="font-mono text-sm">{service || "Ohne Dienst"}</CardTitle>
                  <CardDescription>
                    {service
                      ? `${entries.length} ${entries.length === 1 ? "Datei" : "Dateien"} aus dem Konfigurationsvolume von ${service}.`
                      : "Dateien, die direkt im Einhängepunkt liegen und zu keinem Dienstverzeichnis gehören."}
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col">
                  {entries.map((file) => (
                    <Link
                      key={file.path}
                      to="/konfiguration/$"
                      params={{ _splat: file.path }}
                      className="flex h-row items-center justify-between gap-3 rounded-sm px-3 -mx-3 hover:bg-accent"
                    >
                      <span className="truncate font-mono text-sm">{file.name}</span>
                      {file.writable ? null : (
                        <Badge variant="outline" className="gap-1 shrink-0">
                          <Lock className="size-3" aria-hidden />
                          nur lesbar
                        </Badge>
                      )}
                    </Link>
                  ))}
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </QueryState>
    </div>
  )
}

function group(files: { service: string; name: string; path: string; writable: boolean }[]) {
  const byService = new Map<string, typeof files>()
  for (const file of files) {
    const bucket = byService.get(file.service)
    if (bucket) bucket.push(file)
    else byService.set(file.service, [file])
  }
  return [...byService.entries()]
}

// -----------------------------------------------------------------------------------------------
// One file
// -----------------------------------------------------------------------------------------------

export function KonfigurationDateiPage() {
  const { _splat } = useParams({ from: "/konfiguration/$" })
  const file = _splat ?? ""
  const document = useConfig(file)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={<span className="font-mono">{file.split("/").pop() ?? file}</span>}
        note={file}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link to="/konfiguration">Alle Dateien</Link>
          </Button>
        }
      />

      <QueryState query={document} rows={8}>
        {(read) => <ConfigForm key={file} file={file} document={read} />}
      </QueryState>
    </div>
  )
}

type Draft = Record<string, string | string[]>

function ConfigForm({ file, document }: { file: string; document: ConfigDocument }) {
  const [draft, setDraft] = useState<Draft>({})
  const save = useSaveConfig(file)

  // The answer to a save IS the file as it now reads, so a successful write replaces the document
  // and empties the form's own state. Anything the backend quoted differently is then on screen,
  // rather than the value this browser hoped it had written.
  useEffect(() => setDraft({}), [document])

  const changes = useMemo(() => changed(document, draft), [document, draft])
  const count = Object.keys(changes).length

  function submit() {
    save.mutate(changes, {
      onSuccess: () =>
        toast.success(
          count === 1 ? "Eine Einstellung gespeichert." : `${count} Einstellungen gespeichert.`,
          { description: document.name },
        ),
    })
  }

  return (
    <div className="flex flex-col gap-6">
      {document.header.length > 0 ? (
        <Card>
          <CardContent className="whitespace-pre-wrap text-sm text-muted-foreground">
            {document.header.join("\n").trim()}
          </CardContent>
        </Card>
      ) : null}

      {document.writable ? (
        <Alert>
          <FileWarning aria-hidden />
          <AlertTitle>Eine gespeicherte Änderung erreicht keinen laufenden Dienst.</AlertTitle>
          <AlertDescription>
            Sie steht in der Datei, und der Dienst liest sie beim nächsten Start.{" "}
            {document.service ? (
              <Link
                to="/dienste/$name"
                params={{ name: document.service }}
                className="underline underline-offset-4"
              >
                {document.service} neu starten
              </Link>
            ) : (
              "Der betroffene Dienst muss dafür neu gestartet werden."
            )}
            .
          </AlertDescription>
        </Alert>
      ) : (
        <Alert>
          <Lock aria-hidden />
          <AlertTitle>Diese Datei ist schreibgeschützt eingehängt.</AlertTitle>
          <AlertDescription>
            Die Werte sind lesbar, das Formular nimmt keine Änderung an. Das ist eine Eigenschaft
            des Volumes, nicht dieser Oberfläche.
          </AlertDescription>
        </Alert>
      )}

      {save.error ? <Failure error={save.error} /> : null}

      <Card>
        <CardContent className="flex flex-col gap-0">
          {document.entries.length === 0 ? (
            <Empty
              title="Diese Datei hat keine Schlüssel."
              note="Sie ist leer oder besteht nur aus Kommentaren."
            />
          ) : (
            document.entries.map((entry, index) => (
              <Field
                key={entry.path}
                entry={entry}
                first={index === 0}
                writable={document.writable}
                draft={draft}
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
        </CardContent>
      </Card>

      <div className="sticky bottom-0 flex flex-wrap items-center justify-between gap-3 border-t border-border bg-background/95 py-3 backdrop-blur">
        <p className="text-sm text-muted-foreground">
          {count === 0
            ? "Nichts geändert."
            : count === 1
              ? "Eine Einstellung geändert."
              : `${count} Einstellungen geändert.`}
        </p>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={count === 0 || save.isPending}
            onClick={() => setDraft({})}
          >
            Verwerfen
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={count === 0 || save.isPending || !document.writable}
            onClick={submit}
          >
            {save.isPending ? "Wird gespeichert…" : "Speichern"}
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
function changed(document: ConfigDocument, draft: Draft): ConfigChanges {
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
  onChange,
  onReset,
}: {
  entry: ConfigEntry
  first: boolean
  writable: boolean
  draft: Draft
  onChange: (value: string | string[]) => void
  onReset: () => void
}) {
  const depth = entry.path.split(".").length - 1
  const dirty = draft[entry.path] !== undefined

  if (entry.kind === "MAP") {
    return (
      <div style={{ marginLeft: depth * 16 }} className="pt-6 pb-2">
        <Separator className="mb-4" />
        <h2 className="text-sm font-semibold">{entry.label}</h2>
        <p className="font-mono text-xs text-muted-foreground">{entry.path}</p>
        {entry.comments.length > 0 ? (
          <p className="mt-2 max-w-prose whitespace-pre-wrap text-sm text-muted-foreground">
            {entry.comments.join("\n").trim()}
          </p>
        ) : null}
      </div>
    )
  }

  const disabled = !writable || !entry.editable

  return (
    <div
      style={{ marginLeft: depth * 16 }}
      className={`flex flex-col gap-2 border-border py-3 ${first ? "" : "border-t"}`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <Label htmlFor={entry.path} className="text-sm font-medium">
          {entry.label}
        </Label>
        <div className="flex items-center gap-2">
          {dirty ? (
            <Button type="button" variant="ghost" size="sm" onClick={onReset}>
              <RotateCcw aria-hidden />
              Zurücksetzen
            </Button>
          ) : null}
          <span className="font-mono text-xs text-muted-foreground">{entry.path}</span>
        </div>
      </div>

      {entry.comments.length > 0 ? (
        <p className="max-w-prose whitespace-pre-wrap text-sm text-muted-foreground">
          {entry.comments.join("\n").trim()}
        </p>
      ) : null}

      <Control entry={entry} draft={draft} disabled={disabled} onChange={onChange} />

      {!entry.editable ? (
        <p className="text-sm text-muted-foreground">
          Diese Zeile wird hier nicht geschrieben – sie ist eine Liste aus Abschnitten. Zeile{" "}
          {entry.line} der Datei.
        </p>
      ) : null}
    </div>
  )
}

function Control({
  entry,
  draft,
  disabled,
  onChange,
}: {
  entry: ConfigEntry
  draft: Draft
  disabled: boolean
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
          placeholder={entry.filled ? "gesetzt – zum Ersetzen neu eintippen" : "leer"}
          onChange={(event) => onChange(event.target.value)}
        />
        <p className="text-sm text-muted-foreground">
          {typed === ""
            ? "Leer speichern löscht dieses Geheimnis aus der Datei."
            : "Der gespeicherte Wert wird nicht an den Browser gesendet. Er lässt sich überschreiben, nicht nachlesen."}
        </p>
      </div>
    )
  }

  const value = typed ?? entry.value ?? ""

  if (entry.type === "BOOLEAN") {
    return (
      <div className="flex items-center gap-3">
        <Switch
          id={entry.path}
          disabled={disabled}
          checked={value === "true"}
          onCheckedChange={(on) => onChange(on ? "true" : "false")}
        />
        <span className="text-sm text-muted-foreground">{value === "true" ? "an" : "aus"}</span>
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
 * A list, one row per entry.
 *
 * The whole list is sent on save rather than a single added entry, which is what makes a
 * concurrent edit impossible to lose silently: two browsers sending whole lists disagree
 * visibly, two browsers sending "add one" both succeed and neither is what anybody meant.
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
        <p className="text-sm text-muted-foreground">Leere Liste.</p>
      ) : (
        items.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              id={index === 0 ? id : undefined}
              disabled={disabled}
              value={item}
              spellCheck={false}
              className="font-mono text-sm"
              aria-label={`Eintrag ${index + 1}`}
              onChange={(event) =>
                onChange(items.map((old, at) => (at === index ? event.target.value : old)))
              }
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={disabled}
              aria-label={`Eintrag ${index + 1} entfernen`}
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
          Eintrag hinzufügen
        </Button>
      </div>
    </div>
  )
}
