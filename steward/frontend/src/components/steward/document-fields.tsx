import {
  ArrowDownIcon,
  ArrowUpIcon,
  BracketsCurlyIcon,
  LockSimpleIcon,
  PlusIcon,
  TrashIcon,
  UploadSimpleIcon,
} from "@phosphor-icons/react"
import { useId, useMemo, useRef, type ReactNode } from "react"

import type { SharedPartView, DocumentPart, DocumentValue, DocumentView, MessageArg } from "@/lib/api"
import {
  insertValue,
  readWhen,
  versionsOf,
  withVersions,
  writeWhen,
  type PartPath,
  type When,
} from "@/lib/document-edit"
import type { DocumentText } from "@/lib/documents"
import { languageName } from "@/lib/language-names"
import { ENGLISH, type Language } from "@/lib/message-text"
import { useMessageSyntax, useUploadImage } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { Failure } from "@/components/steward/query-state"
import { ColourControl } from "@/components/steward/colour-control"
import { Media } from "@/components/steward/message-editor/document-preview"
import { RichField } from "@/components/steward/message-editor/rich-field"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"

/** What every field of the editor reads: the document's values, the languages edited and whether a change is allowed. */
export type Context = {
  view: DocumentView
  /** The one language edited and previewed. */
  language: Language
  writable: boolean
  /** The shared parts saved, to insert and to compare a copy with. */
  sharedParts: readonly SharedPartView[]
  /** Returns the draft with the part at `path` changed, or removed when `change` returns nothing. */
  onPart: (path: PartPath, change: (part: DocumentPart) => DocumentPart | undefined) => void
  onMove: (path: PartPath, by: -1 | 1) => void
}

/** An image's address, `attachment://` for a packaged one, with an upload that replaces it by a stored one. */
export function ImageField({
  address,
  disabled,
  onAddress,
}: {
  address: string
  disabled: boolean
  onAddress: (address: string) => void
}) {
  const upload = useUploadImage()
  const input = useRef<HTMLInputElement>(null)
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <div className="flex min-w-0 items-center gap-2">
        {address ? <Media url={address} className="size-10 shrink-0" /> : null}
        <Input
          aria-label={t("steward.document-editor.image")}
          value={address}
          disabled={disabled}
          inputMode="url"
          className="min-w-0 flex-1 font-mono text-xs max-md:text-base"
          onChange={(event) => onAddress(event.target.value)}
        />
        <IconButton
          label={t("steward.document-editor.upload")}
          disabled={disabled || upload.isPending}
          onPress={() => input.current?.click()}
        >
          <UploadSimpleIcon aria-hidden />
        </IconButton>
        <input
          ref={input}
          type="file"
          accept="image/png,image/jpeg,image/gif,image/webp"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ""
            if (file) upload.mutate(file, { onSuccess: (uploaded) => onAddress(uploaded.address) })
          }}
        />
      </div>
      {upload.error ? <Failure error={upload.error} /> : null}
    </div>
  )
}

/** A text in the language edited, each version a field of its own. */
export function TextField({
  text,
  context,
  values,
  onText,
  multiline,
}: {
  text: DocumentText | undefined
  context: Context
  /** A data list's or a select's own values, which only this text may name. */
  values?: Record<string, DocumentValue>
  onText: (text: DocumentText) => void
  multiline?: boolean
}) {
  const { language } = context
  const named = { ...context.view.named, ...values }
  return (
    <Versions
      language={language}
      versions={versionsOf(text, language)}
      names={Object.keys(named)}
      game={context.view.game ? named : undefined}
      disabled={!context.writable}
      multiline={multiline}
      onVersions={(versions) => onText(withVersions(text, language, versions))}
    />
  )
}

function Versions({
  language,
  versions,
  names,
  game,
  disabled,
  multiline,
  onVersions,
}: {
  language: Language
  versions: string[]
  names: string[]
  /** A game text's values, which it is written with in tones, glyphs and values as the game draws it. */
  game?: Record<string, DocumentValue>
  disabled: boolean
  multiline?: boolean
  onVersions: (versions: string[]) => void
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      {versions.map((version, index) =>
        game ? (
          <GameVersion
            key={index}
            language={language}
            revision={`${language}/${index}`}
            source={version}
            values={game}
            disabled={disabled}
            onSource={(source) => onVersions(versions.map((each, at) => (at === index ? source : each)))}
            onRemove={versions.length > 1 ? () => onVersions(versions.filter((_, at) => at !== index)) : undefined}
          />
        ) : (
          <Version
            key={index}
            label={languageName(language)}
            source={version}
            names={names}
            disabled={disabled}
            multiline={multiline}
            onSource={(source) => onVersions(versions.map((each, at) => (at === index ? source : each)))}
            onRemove={versions.length > 1 ? () => onVersions(versions.filter((_, at) => at !== index)) : undefined}
          />
        ),
      )}
      <AddButton
        label={t("steward.message-editor.add-variant")}
        disabled={disabled}
        onPress={() => onVersions([...versions, ""])}
      />
    </div>
  )
}

/** A game text's version in the key editor's field, with the game's tones and glyphs and the document's values. */
function GameVersion({
  language,
  revision,
  source,
  values,
  disabled,
  onSource,
  onRemove,
}: {
  language: Language
  revision: string
  source: string
  values: Record<string, DocumentValue>
  disabled: boolean
  onSource: (source: string) => void
  onRemove: (() => void) | undefined
}) {
  const syntax = useMessageSyntax()
  const args = useMemo(() => argsOf(values), [values])
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <RichField
        text={source}
        stored={source}
        onText={onSource}
        format="MINIMESSAGE"
        args={args}
        fill={(name) => values[name]?.example ?? name}
        tones={syntax.data?.tones ?? {}}
        kinds={syntax.data?.kinds ?? {}}
        label={languageName(language)}
        language={language}
        revision={revision}
        disabled={disabled}
        end={
          onRemove ? (
            <IconButton label={t("steward.message-editor.remove-variant")} disabled={disabled} onPress={onRemove}>
              <TrashIcon aria-hidden />
            </IconButton>
          ) : null
        }
      />
    </div>
  )
}

/** A document's values as the key editor's field names them; a value every document has carries a dot. */
export function argsOf(values: Record<string, DocumentValue>): MessageArg[] {
  return Object.entries(values).map(([name, value]) => ({
    name,
    kind: value.kind,
    global: name.includes("."),
    example: value.example,
    action: false,
    exampleWords: {},
  }))
}

function Version({
  label,
  source,
  names,
  disabled,
  multiline,
  onSource,
  onRemove,
}: {
  label: string
  source: string
  names: string[]
  disabled: boolean
  multiline?: boolean
  onSource: (source: string) => void
  onRemove: (() => void) | undefined
}) {
  const field = useRef<HTMLTextAreaElement & HTMLInputElement>(null)
  const props = {
    ref: field,
    "aria-label": label,
    value: source,
    disabled,
    className: "min-w-0 flex-1",
    onChange: (event: { target: { value: string } }) => onSource(event.target.value),
  }
  return (
    <div className="flex min-w-0 items-start gap-1">
      {multiline ? <Textarea rows={2} {...props} /> : <Input {...props} />}
      {names.length > 0 ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild disabled={disabled}>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("steward.message-editor.placeholder")}
              title={t("steward.message-editor.placeholder")}
            >
              <BracketsCurlyIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-auto">
            {names.map((name) => (
              <DropdownMenuItem
                key={name}
                className="font-mono text-xs"
                onSelect={() => onSource(insertValue(source, name, field.current?.selectionStart ?? null))}
              >
                {name}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
      {onRemove ? (
        <IconButton label={t("steward.message-editor.remove-variant")} disabled={disabled} onPress={onRemove}>
          <TrashIcon aria-hidden />
        </IconButton>
      ) : null}
    </div>
  )
}

/** When a part, a line or a button shows: always, or by one of the document's values. */
export function WhenField({
  view,
  of = "ITEM",
  when,
  disabled,
  onWhen,
}: {
  view: DocumentView
  /** What the condition shows, named beside it, since a part's and its line's conditions stand one under the other. */
  of?: "PART" | "LINE" | "ITEM"
  when: string | undefined
  disabled: boolean
  onWhen: (when: string | undefined) => void
}) {
  const read = readWhen(when)
  const names = Object.keys(view.named)
  const first = names[0]
  if (first === undefined) return null
  const value = read.mode === "ALWAYS" ? first : read.value
  const choose = (next: When) => onWhen(writeWhen(next))
  const label = t("steward.document-editor.when", { of: choice(of) })
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1" aria-label={label}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <Select
        value={read.mode}
        disabled={disabled}
        onValueChange={(mode) =>
          choose(
            mode === "ALWAYS"
              ? { mode }
              : mode === "EQUALS"
                ? { mode, value, constant: read.mode === "EQUALS" ? read.constant : "" }
                : { mode: mode === "UNSET" ? "UNSET" : "SET", value },
          )
        }
      >
        <SelectTrigger size="sm" aria-label={label} className="w-24">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {(["ALWAYS", "SET", "UNSET", "EQUALS"] as const).map((mode) => (
            <SelectItem key={mode} value={mode}>
              {t("steward.document-editor.condition", { condition: choice(mode) })}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {read.mode === "ALWAYS" ? null : (
        <Select value={read.value} disabled={disabled} onValueChange={(next) => choose({ ...read, value: next })}>
          <SelectTrigger size="sm" aria-label={label} className="w-36 font-mono text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {names.map((name) => (
              <SelectItem key={name} value={name} className="font-mono text-xs">
                {name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
      {read.mode === "EQUALS" ? (
        <Input
          aria-label={t("steward.document-editor.constant")}
          value={read.constant}
          disabled={disabled}
          className="h-7 w-24 font-mono text-xs max-md:text-base"
          onChange={(event) => choose({ ...read, constant: event.target.value })}
        />
      ) : null}
    </span>
  )
}

export function AccentField({
  accent,
  disabled,
  onAccent,
}: {
  accent: string | undefined
  disabled: boolean
  onAccent: (accent: string | undefined) => void
}) {
  const id = useId()
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <label htmlFor={id} className="text-xs text-muted-foreground">
        {t("steward.document-editor.accent")}
      </label>
      <ColourControl
        id={id}
        value={accent ?? ""}
        disabled={disabled}
        onChange={(value) => onAccent(value || undefined)}
      />
    </div>
  )
}

export function CodeMark({ slot }: { slot: string }) {
  return (
    <Badge variant="outline" title={t("steward.document-editor.code")} className="shrink-0 font-mono text-[0.625rem]">
      {slot}
    </Badge>
  )
}

export function Labelled({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      {children}
    </div>
  )
}

export function IconButton({
  label,
  disabled,
  onPress,
  children,
}: {
  label: string
  disabled?: boolean
  onPress: () => void
  children: ReactNode
}) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onPress}
    >
      {children}
    </Button>
  )
}

export function AddButton({ label, disabled, onPress }: { label: string; disabled: boolean; onPress: () => void }) {
  return (
    <Button type="button" variant="ghost" size="xs" className="self-start" disabled={disabled} onClick={onPress}>
      <PlusIcon aria-hidden />
      {label}
    </Button>
  )
}

/** The lock of a part or button the code needs, which stays. */
export function Required() {
  return (
    <span title={t("steward.document-editor.required")} className="flex size-7 shrink-0 items-center justify-center">
      <LockSimpleIcon aria-label={t("steward.document-editor.required")} className="size-4 text-muted-foreground" />
    </span>
  )
}

/** The language edited and previewed, one at a time, as the text editor picks it. */
export function LanguageToggle({
  languages,
  language,
  onLanguage,
}: {
  languages: string[]
  language: Language
  onLanguage: (language: Language) => void
}) {
  if (languages.length < 2) return null
  return (
    <Tabs value={language} onValueChange={onLanguage}>
      <TabsList aria-label={t("steward.document-editor.language")} className="group-data-horizontal/tabs:h-8 p-0.5">
        {[ENGLISH, ...languages.filter((each) => each !== ENGLISH)].map((each) => (
          <TabsTrigger key={each} value={each} title={languageName(each)} className="px-2.5 text-xs">
            {each.toUpperCase()}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  )
}

export function Images({
  part,
  most,
  disabled,
  set,
}: {
  part: DocumentPart
  most: number
  disabled: boolean
  set: (change: (part: DocumentPart) => DocumentPart | undefined) => void
}) {
  const media = part.media ?? []
  return (
    <>
      {media.map((address, index) => (
        <div key={index} className="flex min-w-0 items-start gap-1">
          <ImageField
            address={address}
            disabled={disabled}
            onAddress={(next) => set((it) => ({ ...it, media: media.map((each, at) => (at === index ? next : each)) }))}
          />
          {media.length > 1 ? (
            <IconButton
              label={t("steward.document-editor.remove")}
              disabled={disabled}
              onPress={() => set((it) => ({ ...it, media: media.filter((_, at) => at !== index) }))}
            >
              <TrashIcon aria-hidden />
            </IconButton>
          ) : null}
        </div>
      ))}
      {media.length < most ? (
        <AddButton
          label={t("steward.document-editor.add-image")}
          disabled={disabled}
          onPress={() => set((it) => ({ ...it, media: [...media, ""] }))}
        />
      ) : null}
    </>
  )
}

export function Move({
  disabled,
  first,
  last,
  onMove,
}: {
  disabled: boolean
  first: boolean
  last: boolean
  onMove: (by: -1 | 1) => void
}) {
  return (
    <>
      <IconButton label={t("steward.document-editor.move-up")} disabled={disabled || first} onPress={() => onMove(-1)}>
        <ArrowUpIcon aria-hidden />
      </IconButton>
      <IconButton label={t("steward.document-editor.move-down")} disabled={disabled || last} onPress={() => onMove(1)}>
        <ArrowDownIcon aria-hidden />
      </IconButton>
    </>
  )
}
