import { CheckIcon, CopyIcon, QuestionIcon, UserIcon } from "@phosphor-icons/react"
import { useState } from "react"

import { StewardMark } from "@/app/steward-mark"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"

/**
 * The one display of a person, and the only place a Discord id or a Minecraft UUID may be drawn.
 *
 * Closed it shows an avatar and a name; its popover holds both ids, each with a copy button.
 */

/** A Minecraft head URL, composed from the uuid and the configured base, so a new base leaves nothing stale. */
export function minecraftHeadUrl(baseUrl: string | undefined, mcUuid: string): string | null {
  if (!baseUrl) return null

  /** The uuid goes into the path, before any query the base carries such as `?scale=16`. */
  const [path, query] = splitQuery(baseUrl)

  /** Hyphens stripped, so each face has one spelling and one cache entry. */
  return `${path.replace(/\/+$/, "")}/${mcUuid.replace(/-/g, "")}${query}`
}

/** `["https://host/face", "?scale=16"]`, with an empty second half when there is no query. */
function splitQuery(baseUrl: string): [string, string] {
  const at = baseUrl.search(/[?#]/)
  return at === -1 ? [baseUrl, ""] : [baseUrl.slice(0, at), baseUrl.slice(at)]
}

/** Loosely matches a Discord snowflake or a Minecraft UUID, for tests that assert none leaks into a page. */
export const IDENTIFIER_PATTERN =
  /\b\d{17,20}\b|\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/

/**
 * Copies text to the clipboard where the browser allows it, and reports whether it did.
 *
 * Needs a secure context, so the id is also drawn as selectable text.
 */
export async function copyToClipboard(value: string): Promise<boolean> {
  try {
    if (!navigator.clipboard?.writeText) return false
    await navigator.clipboard.writeText(value)
    return true
  } catch {
    return false
  }
}

export type PersonIdentityProps = {
  /** Not required with {@link system}, which has no id to show. */
  discordId?: string
  discordUsername?: string
  discordDisplayName?: string
  discordAvatarUrl?: string
  mcUuid?: string
  mcName?: string
  /** Where a Minecraft head is composed from, `useAvatarBaseUrl()`; absent draws no head. */
  avatarBaseUrl?: string
  className?: string
  /** Steward itself did this, not a person: the server's mark and the word "Steward", with no popover. */
  system?: boolean
  /** Which half is drawn closed: `discord` (default) or `minecraft`; the popover is the same. */
  face?: "discord" | "minecraft"
  /** `false` draws the closed face alone, for a place that is itself interactive. */
  interactive?: boolean
}

/** The name shown closed. */
function displayName(props: PersonIdentityProps): { text: string | null } {
  if (props.discordDisplayName) {
    return { text: props.discordDisplayName }
  }
  if (props.discordUsername) {
    return { text: props.discordUsername }
  }
  return { text: null }
}

export function PersonIdentity(props: PersonIdentityProps) {
  if (props.system) {
    return (
      <span className={"inline-flex min-w-0 items-center gap-2 " + (props.className ?? "")}>
        <StewardMark className="size-5 shrink-0" />
        <span className="truncate text-sm text-foreground">{t("steward.shell.steward")}</span>
      </span>
    )
  }

  const name = displayName(props)
  const minecraft = props.face === "minecraft"
  const closed = (
    <>
      {minecraft ? (
        <MinecraftHead mcUuid={props.mcUuid ?? ""} baseUrl={props.avatarBaseUrl} />
      ) : (
        <DiscordAvatar url={props.discordAvatarUrl} name={name.text} />
      )}
      {minecraft ? (
        <span className={"truncate text-sm " + (props.mcName ? "text-foreground" : "text-muted-foreground italic")}>
          {props.mcName ?? t("steward.identity.no-name")}
        </span>
      ) : (
        <span className={"truncate text-sm " + (name.text ? "text-foreground" : "text-muted-foreground italic")}>
          {name.text ?? t("steward.identity.no-discord-name")}
        </span>
      )}
    </>
  )

  if (props.interactive === false) {
    return (
      <span className={"inline-flex min-w-0 max-w-full items-center gap-2 " + (props.className ?? "")}>{closed}</span>
    )
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={
            /** `max-w-full` caps the box so `truncate` can act; `min-w-0` only lets it shrink. */
            "inline-flex min-w-0 max-w-full items-center gap-2 rounded-full text-left outline-none " +
            "hover:opacity-80 focus-visible:ring-[3px] focus-visible:ring-ring/50 " +
            (props.className ?? "")
          }
        >
          {closed}
        </button>
      </PopoverTrigger>
      <PopoverContent className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <DiscordAvatar url={props.discordAvatarUrl} name={name.text} size="size-8" />
          <div className="flex min-w-0 flex-col">
            <span className="truncate text-sm font-medium">{name.text ?? t("steward.identity.no-discord-name")}</span>
            {name.text ? null : (
              <span className="text-xs text-muted-foreground">{t("steward.identity.never-observed")}</span>
            )}
          </div>
        </div>

        {props.discordId ? (
          <CopyableId label={t("steward.identity.discord-id")} value={props.discordId} />
        ) : (
          <p className="text-xs text-muted-foreground">{t("steward.identity.no-discord")}</p>
        )}

        {props.mcUuid ? (
          <>
            <div className="flex items-center gap-2 border-t border-border pt-3">
              <MinecraftHead mcUuid={props.mcUuid} baseUrl={props.avatarBaseUrl} size="size-8" rounded="rounded-md" />
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-sm font-medium">
                  {props.mcName ?? t("steward.identity.no-minecraft-name")}
                </span>
                {props.mcName ? null : (
                  <span className="text-xs text-muted-foreground">{t("steward.identity.never-joined")}</span>
                )}
              </div>
            </div>
            <CopyableId label={t("steward.identity.minecraft-uuid")} value={props.mcUuid} />
          </>
        ) : (
          <p className="border-t border-border pt-3 text-xs text-muted-foreground">
            {t("steward.identity.no-minecraft")}
          </p>
        )}
      </PopoverContent>
    </Popover>
  )
}

/** An id as selectable text plus a copy button, for a browser without clipboard access. */
function CopyableId({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false)

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <div className="flex items-center gap-1.5">
        <input
          readOnly
          value={value}
          aria-label={label}
          onFocus={(event) => event.currentTarget.select()}
          className="min-w-0 flex-1 truncate rounded-sm border border-border bg-muted px-2 py-1 font-mono text-xs select-all max-md:text-base"
        />
        <Button
          type="button"
          variant="outline"
          size="icon-xs"
          aria-label={t("steward.identity.copy", { what: label })}
          onClick={async () => {
            const ok = await copyToClipboard(value)
            if (ok) {
              setCopied(true)
              setTimeout(() => setCopied(false), 1500)
            }
          }}
        >
          {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
        </Button>
      </div>
    </div>
  )
}

/** The small round Discord avatar, closed or in the popover header. */
function DiscordAvatar({ url, name, size = "size-5" }: { url?: string; name: string | null; size?: string }) {
  const [broken, setBroken] = useState(false)

  if (url && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- this is not Next.js
      <img
        src={url}
        alt=""
        className={`${size} shrink-0 rounded-full border border-border object-cover`}
        onError={() => setBroken(true)}
      />
    )
  }
  return (
    <span
      className={`${size} inline-flex shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-muted-foreground`}
      title={name ? undefined : t("steward.identity.no-avatar")}
    >
      <UserIcon aria-hidden className="size-3.5" />
    </span>
  )
}

/**
 * The small rounded-square Minecraft head, drawn from a composed URL.
 *
 * A skeleton while waiting or loading, a question mark if it fails, and never a change of size.
 */
export function MinecraftHead({
  mcUuid,
  baseUrl,
  size = "size-5",
  rounded = "rounded-sm",
}: {
  /** Absent while the row around this head is still loading. */
  mcUuid?: string
  baseUrl?: string
  size?: string
  rounded?: string
}) {
  const [broken, setBroken] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const url = mcUuid ? minecraftHeadUrl(baseUrl, mcUuid) : undefined

  /** `undefined` waits; `""` means nobody has one and draws the question mark. */
  if (mcUuid === undefined) {
    return <Skeleton className={`${size} shrink-0 ${rounded}`} />
  }
  if (url && !broken) {
    return (
      <span className={`${size} relative inline-block shrink-0`}>
        {loaded ? null : <Skeleton className={`absolute inset-0 ${rounded}`} />}
        {/* eslint-disable-next-line @next/next/no-img-element -- this is not Next.js */}
        <img
          src={url}
          alt=""
          className={`absolute inset-0 size-full ${rounded} border border-border object-cover transition-opacity duration-150 ${
            loaded ? "opacity-100" : "opacity-0"
          }`}
          onLoad={() => setLoaded(true)}
          onError={() => setBroken(true)}
        />
      </span>
    )
  }
  return (
    <span
      className={`${size} inline-flex shrink-0 items-center justify-center ${rounded} border border-border bg-secondary text-muted-foreground`}
      title={t("steward.identity.no-head")}
    >
      <QuestionIcon aria-hidden className="size-3" />
    </span>
  )
}
