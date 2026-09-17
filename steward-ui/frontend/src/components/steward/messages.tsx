import { Fragment, useEffect, useMemo, useRef, useState } from "react"
import { ChevronDown, ChevronRight, Languages, Lock, RotateCcw } from "lucide-react"

import type { MessageBundle, MessageBundleLocation, MessageEntry } from "@/lib/api"
import { useMessageBundle, useMessageBundles, useSaveMessageBundle } from "@/lib/queries"
import {
  onPendingMessageJump,
  takePendingMessageJump,
  type PendingMessageJump,
} from "@/lib/settings-search"
import { Failure, QueryState } from "@/components/steward/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

/**
 * A service's message bundles, on the service's own page (steward/48).
 *
 * **Its own card, beside `ServiceConfiguration` rather than inside it.** A bundle is not a config
 * file - it has no YAML shape and no schema, keys are merged one at a time rather than a whole
 * file being rewritten, and the design round that asked for this card decided so on purpose: its
 * own card on the service's page, beside the configuration cards - never inside them.
 *
 * **The packaged text shown here comes out of the module's jar, never off disk.** Disk only ever
 * holds the operator's override, and typing a line in the field below is what *creates* one - the
 * worker's `MessageBundles.read` merges the two the same way `eu.nordtal.s2.common.message.Messages`
 * does at runtime, key by key.
 *
 * **Resetting a line deletes the override key. It never fills it with the English text.** A reset
 * line goes back to following the jar, including the next time the jar changes - copying English
 * into it would freeze that line in whatever English said today.
 *
 * **A search hit lands here too (steward/87).** `ServiceSettingsSearch` (in `config-search.tsx`,
 * on the same page) and the command palette's global search both find bundle keys now, and "a hit
 * in a bundle leads into the messages tool, not the configuration form" is the one genuinely new
 * navigation case that ticket added. Both suppliers hand their destination to this component
 * through `setPendingMessageJump` (`lib/settings-search.ts`), never by a prop from
 * `ServiceConfiguration` - that component is frozen for this ticket, and its own `PendingJump` map
 * is a config file's identity, not a bundle's.
 */
export function ServiceMessages({ service }: { service: string }) {
  const [open, setOpen] = useState<string | null>(null)
  const [jump, setJump] = useState<PendingMessageJump | null>(null)
  const bundles = useMessageBundles()
  const mine = useMemo(
    () => (bundles.data ?? []).filter((bundle) => bundle.service === service),
    [bundles.data, service],
  )

  // Two arrivals, one handler. `apply()` runs once on mount/service-change to pick up a jump left
  // by the command palette before it navigated here (the same pattern `configuration.tsx` follows
  // for `takePendingJump`) - and it runs again for as long as this stays mounted, because a hit
  // found by `ServiceSettingsSearch` right above this card has nowhere to navigate *to*: both cards
  // are already on screen, so that box calls `setPendingMessageJump` directly rather than a route
  // change, and this is what notices.
  useEffect(() => {
    function apply() {
      const pending = takePendingMessageJump(service)
      if (pending) {
        setOpen(pending.path)
        setJump(pending)
      }
    }
    apply()
    return onPendingMessageJump(apply)
  }, [service])

  return (
    <Card>
      <CardHeader>
        <CardTitle>Messages</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col">
        <QueryState
          query={bundles}
          rows={3}
          isEmpty={() => mine.length === 0}
          empty={{
            title: "This service has no message bundle here.",
            note: "Nothing under messages/ in its jar was found, so there is no packaged text to override.",
          }}
        >
          {() =>
            mine.map((bundle) => (
              <Fragment key={bundle.path}>
                <BundleRow
                  bundle={bundle}
                  open={open === bundle.path}
                  onToggle={() => setOpen((current) => (current === bundle.path ? null : bundle.path))}
                />
                {open === bundle.path ? (
                  <OneBundle
                    path={bundle.path}
                    jump={jump && jump.path === bundle.path ? jump : null}
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

function BundleRow({
  bundle,
  open,
  onToggle,
}: {
  bundle: MessageBundleLocation
  open: boolean
  onToggle: () => void
}) {
  const Chevron = open ? ChevronDown : ChevronRight
  const label = bundle.module || bundle.service

  return (
    <button
      type="button"
      aria-expanded={open}
      onClick={onToggle}
      className="-mx-3 flex h-row items-center justify-between gap-3 rounded-sm px-3 text-left hover:bg-accent"
    >
      <span className="flex min-w-0 items-center gap-2">
        <Chevron className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="truncate text-sm">{label}</span>
      </span>
      {bundle.writable ? null : (
        <Badge variant="outline" className="shrink-0 gap-1">
          <Lock className="size-3" aria-hidden />
          read only
        </Badge>
      )}
    </button>
  )
}

function OneBundle({ path, jump }: { path: string; jump: PendingMessageJump | null }) {
  const document = useMessageBundle(path)

  return (
    <div className="border-t border-border pt-4 pb-6">
      <QueryState query={document} rows={8}>
        {(read) => <BundleForm key={path} path={path} bundle={read} jump={jump} />}
      </QueryState>
    </div>
  )
}

type Language = "en" | "de"

/**
 * One key's draft, in the language currently shown.
 *
 * `undefined` (absent from the map): nothing typed, the saved value stands.
 * `null`: a pending reset - the override will be removed on save.
 * a string: the pending new text for the override, which may be empty.
 */
type Draft = Record<string, string | null>

function packagedOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.english : entry.german
}

function overrideOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.overrideEnglish : entry.overrideGerman
}

function BundleForm({
  path,
  bundle,
  jump,
}: {
  path: string
  bundle: MessageBundle
  /** A search hit (steward/87) landed on this bundle - which language tab and which key. */
  jump: PendingMessageJump | null
}) {
  // Seeded from `jump` where there is one, so a hit found from another page opens straight on the
  // language it matched in rather than flashing English first and then switching.
  const [language, setLanguage] = useState<Language>(() => jump?.language ?? "en")
  const [draft, setDraft] = useState<Draft>({})
  const [warnings, setWarnings] = useState<string[]>([])
  // Which key to scroll to and ring, `null` the rest of the time - the message-tool equivalent of
  // `configuration.tsx`'s `highlight` state, cleared by `MessageRow` itself once it has made its
  // point (steward/58's fourth requirement, extended to bundles by steward/87).
  const [highlightKey, setHighlightKey] = useState<string | null>(jump?.key ?? null)
  const save = useSaveMessageBundle(path)

  // The answer to a save IS the bundle as it now reads, so a successful write empties the draft -
  // the same rule `ConfigForm` follows for a config file's revision. Switching language does the
  // same: a pending English edit has nothing to say about the German field it is no longer next to.
  useEffect(() => {
    setDraft({})
  }, [bundle, language])

  // A second hit into a bundle that is already open (the per-service search box, right above this
  // card) does not remount this component - only the initial `useState` above sees a fresh `jump`.
  // `jump` is referentially stable from `ServiceMessages` unless a new one was actually set, so
  // this only fires for a genuinely new jump, not on every unrelated re-render.
  useEffect(() => {
    if (!jump) return
    setLanguage(jump.language)
    setHighlightKey(jump.key)
  }, [jump])

  const changes = useMemo(() => {
    const result: Record<string, string | null> = {}
    for (const [key, value] of Object.entries(draft)) {
      const entry = bundle.entries.find((candidate) => candidate.key === key)
      const saved = entry ? (overrideOf(entry, language) ?? null) : null
      if (value === saved) continue // typed back to what is already saved: no change at all
      result[key] = value
    }
    return result
  }, [draft, bundle, language])
  const count = Object.keys(changes).length

  function submit() {
    save.mutate(
      { language, changes },
      {
        onSuccess: (saved) => {
          setDraft({})
          setWarnings(saved.warnings)
        },
      },
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Languages className="size-4 text-muted-foreground" aria-hidden />
        <div className="inline-flex rounded-md border border-border p-0.5" role="group" aria-label="Language">
          <Button
            type="button"
            size="sm"
            variant={language === "en" ? "secondary" : "ghost"}
            onClick={() => setLanguage("en")}
          >
            EN
          </Button>
          <Button
            type="button"
            size="sm"
            variant={language === "de" ? "secondary" : "ghost"}
            onClick={() => setLanguage("de")}
          >
            DE
          </Button>
        </div>
      </div>

      {!bundle.writable ? (
        <Alert>
          <Lock aria-hidden />
          <AlertTitle>This bundle is mounted read-only.</AlertTitle>
          <AlertDescription>
            The packaged and overridden text are both readable here; nothing can be saved to them
            from this interface.
          </AlertDescription>
        </Alert>
      ) : null}

      {save.error ? <Failure error={save.error} /> : null}

      {warnings.length > 0 ? (
        <Alert>
          <AlertTitle>Saved, with something worth checking.</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col gap-3">
        {bundle.entries.map((entry) => (
          <MessageRow
            key={entry.key}
            entry={entry}
            language={language}
            draft={draft[entry.key]}
            onChange={(value) => setDraft((current) => ({ ...current, [entry.key]: value }))}
            onReset={() => setDraft((current) => ({ ...current, [entry.key]: null }))}
            highlighted={entry.key === highlightKey}
            onHighlighted={() => setHighlightKey(null)}
          />
        ))}
      </div>

      {bundle.writable ? (
        <div className="flex items-center justify-end gap-2 border-t border-border pt-3">
          <Button
            type="button"
            variant="outline"
            onClick={() => setDraft({})}
            disabled={count === 0 || save.isPending}
          >
            Discard
          </Button>
          <Button type="button" onClick={submit} disabled={count === 0 || save.isPending}>
            {save.isPending
              ? "Saving…"
              : count === 0
                ? "Save"
                : `Save ${count === 1 ? "1 line" : `${count} lines`}`}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function MessageRow({
  entry,
  language,
  draft,
  onChange,
  onReset,
  highlighted = false,
  onHighlighted,
}: {
  entry: MessageEntry
  language: Language
  draft: string | null | undefined
  onChange: (value: string) => void
  onReset: () => void
  /** A search hit (steward/87) landed on this exact key. */
  highlighted?: boolean
  onHighlighted?: () => void
}) {
  const packaged = packagedOf(entry, language)
  const override = overrideOf(entry, language)
  const ref = useRef<HTMLDivElement>(null)

  // Jump-and-highlight, the same rule `configuration.tsx`'s `Field` follows for a config hit
  // (steward/58's fourth requirement) - scrolls once, on the render where `highlighted` turns
  // true, and clears itself after a moment so the ring does not linger once the point is made.
  useEffect(() => {
    if (!highlighted) return
    ref.current?.scrollIntoView({ behavior: "smooth", block: "center" })
    const timeout = window.setTimeout(() => onHighlighted?.(), 2400)
    return () => window.clearTimeout(timeout)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlighted])

  // `draft === undefined`: nothing typed, show what is saved (the override if there is one, else
  // the packaged text). `draft === null`: a pending reset, preview what it reads once saved - the
  // packaged text, since a reset is a delete rather than a copy. Otherwise: what is being typed.
  const value = draft === undefined ? (override ?? packaged ?? "") : draft === null ? (packaged ?? "") : draft
  const isOverridden = draft === null ? false : draft !== undefined ? true : override !== undefined
  const pendingReset = draft === null

  return (
    <div
      ref={ref}
      className={`flex scroll-mt-4 flex-col gap-1.5 rounded-md border border-border p-3 transition-colors duration-300 ${highlighted ? "bg-accent ring-2 ring-primary" : ""}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Label className="font-mono text-xs text-muted-foreground">{entry.key}</Label>
        <div className="flex items-center gap-2">
          {!entry.inBundle ? <Badge variant="outline">not in bundle</Badge> : null}
          {pendingReset ? (
            <Badge variant="outline">reset pending</Badge>
          ) : isOverridden ? (
            <Badge variant="outline">overridden</Badge>
          ) : null}
          {override !== undefined ? (
            <Button type="button" variant="ghost" size="sm" onClick={onReset} disabled={pendingReset}>
              <RotateCcw className="size-3.5" aria-hidden />
              Reset
            </Button>
          ) : null}
        </div>
      </div>
      <Textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        rows={Math.min(6, Math.max(1, value.split("\n").length))}
        className="font-mono text-sm"
      />
      {packaged !== undefined && isOverridden ? (
        <p className="text-xs text-muted-foreground">Packaged text: {packaged}</p>
      ) : null}
    </div>
  )
}
