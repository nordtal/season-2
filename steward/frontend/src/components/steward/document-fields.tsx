import { BracketsCurlyIcon, PlusIcon, TrashIcon, UploadSimpleIcon } from "@phosphor-icons/react"
import { useId, useRef, type ReactNode } from "react"

import type { DocumentPart, DocumentValue, DocumentView } from "@/lib/api"
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
import { useUploadImage } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { cn } from "cn"
import { Failure } from "@/components/steward/query-state"
import { ColourControl } from "@/components/steward/colour-control"
import { Media } from "@/components/steward/message-editor/document-preview"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"

/** What every field of the editor reads: the document's values, the languages edited and whether a change is allowed. */
export type Context = {
  view: DocumentView
  /** The language edited beside English, if any. */
  beside: Language | undefined
  writable: boolean
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

/** A text in English and, beside it, in the second language, each version a field of its own. */
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
  const languages = [ENGLISH, ...(context.beside ? [context.beside] : [])]
  const names = [...Object.keys(context.view.named), ...Object.keys(values ?? {})]
  return (
    <div className={cn("grid min-w-0 grid-cols-1 gap-2", languages.length > 1 && "sm:grid-cols-2")}>
      {languages.map((language) => (
        <Versions
          key={language}
          language={language}
          versions={versionsOf(text, language)}
          names={names}
          disabled={!context.writable}
          multiline={multiline}
          onVersions={(versions) => onText(withVersions(text, language, versions))}
        />
      ))}
    </div>
  )
}

function Versions({
  language,
  versions,
  names,
  disabled,
  multiline,
  onVersions,
}: {
  language: Language
  versions: string[]
  names: string[]
  disabled: boolean
  multiline?: boolean
  onVersions: (versions: string[]) => void
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      {versions.map((version, index) => (
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
      ))}
      <AddButton
        label={t("steward.message-editor.add-variant")}
        disabled={disabled}
        onPress={() => onVersions([...versions, ""])}
      />
    </div>
  )
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
  when,
  disabled,
  onWhen,
}: {
  view: DocumentView
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
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1" aria-label={t("steward.document-editor.when")}>
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
        <SelectTrigger size="sm" aria-label={t("steward.document-editor.when")} className="w-24">
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
          <SelectTrigger size="sm" aria-label={t("steward.document-editor.when")} className="w-36 font-mono text-xs">
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
