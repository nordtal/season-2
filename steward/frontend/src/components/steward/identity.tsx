import { CheckIcon, CopyIcon, QuestionIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Link, useRouter } from "@tanstack/react-router"
import { useEffect, useState } from "react"

import { StewardMark } from "@/app/steward-mark"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"

/** The only place that may draw a Discord id or a Minecraft UUID. */

export function minecraftHeadUrl(baseUrl: string | undefined, mcUuid: string): string | null {
  if (!baseUrl) return null

  /** The UUID goes before any query that the base carries. */
  const [path, query] = splitQuery(baseUrl)

  return `${path.replace(/\/+$/, "")}/${mcUuid.replace(/-/g, "")}${query}`
}

function splitQuery(baseUrl: string): [string, string] {
  const at = baseUrl.search(/[?#]/)
  return at === -1 ? [baseUrl, ""] : [baseUrl.slice(0, at), baseUrl.slice(at)]
}

/**
 * Matches a Discord snowflake or a Minecraft UUID loosely. Tests use it to check that none appears in a page.
 */
export const IDENTIFIER_PATTERN =
  /\b\d{17,20}\b|\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/

/**
 * Copies text to the clipboard where the browser allows it and returns whether it did.
 *
 * It needs a secure context, so the id is also drawn as selectable text.
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
  discordId?: string
  discordUsername?: string
  discordDisplayName?: string
  discordAvatarUrl?: string
  mcUuid?: string
  mcName?: string
  /** Without it no head is drawn. */
  avatarBaseUrl?: string
  className?: string
  system?: boolean
  face?: "discord" | "minecraft" | "both"
  interactive?: boolean
}

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
  const discordFace = (
    <>
      <DiscordAvatar url={props.discordAvatarUrl} name={name.text} />
      <span className={"truncate text-sm " + (name.text ? "text-foreground" : "text-muted-foreground italic")}>
        {name.text ?? t("steward.identity.no-discord-name")}
      </span>
    </>
  )
  const minecraftFace = (
    <>
      <MinecraftHead mcUuid={props.mcUuid ?? ""} baseUrl={props.avatarBaseUrl} />
      <span className={"truncate text-sm " + (props.mcName ? "text-foreground" : "text-muted-foreground italic")}>
        {props.mcName ?? t("steward.identity.no-name")}
      </span>
    </>
  )
  const closed =
    props.face === "minecraft" ? (
      minecraftFace
    ) : props.face === "both" ? (
      <>
        {/* The head overlaps the picture's corner, so the two take the height of one. */}
        <span className="relative shrink-0">
          <DiscordAvatar url={props.discordAvatarUrl} name={name.text} size="size-8" />
          {props.mcUuid ? (
            <span className="absolute -right-1 -bottom-1 rounded-sm ring-2 ring-background">
              <MinecraftHead mcUuid={props.mcUuid} baseUrl={props.avatarBaseUrl} size="size-4" />
            </span>
          ) : null}
        </span>
        <span className="flex min-w-0 flex-col leading-tight">
          <span className={"truncate font-medium " + (name.text ? "text-foreground" : "text-muted-foreground italic")}>
            {name.text ?? t("steward.identity.no-discord-name")}
          </span>
          {/* The account in the game is the second of the two, so it is quieter than the person's name. */}
          {props.mcUuid ? (
            <span className="truncate text-xs text-muted-foreground">
              {props.mcName ?? t("steward.identity.no-name")}
            </span>
          ) : null}
        </span>
      </>
    ) : (
      discordFace
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
            /**
             * `max-w-full` caps the box so that `truncate` works. `min-w-0` only lets it shrink.
             */
            "inline-flex min-w-0 max-w-full items-center gap-2 rounded-full text-left outline-none tap-target " +
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
          <>
            <CopyableId label={t("steward.identity.discord-id")} value={props.discordId} />
            <PersonPageLink discordId={props.discordId} />
          </>
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

/** It is drawn only inside a router, so a card rendered alone stays a card. */
function PersonPageLink({ discordId }: { discordId: string }) {
  const router = useRouter({ warn: false })
  if (!router) return null

  return (
    <Link
      to="/access/$id"
      params={{ id: discordId }}
      className="inline-flex items-center gap-1.5 self-start text-sm underline-offset-4 outline-none tap-target hover:text-primary hover:underline focus-visible:underline"
    >
      <TERM_ICON.person aria-hidden className="size-4" />
      {t("steward.identity.open-person")}
    </Link>
  )
}

function CopyableId({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return undefined
    const timeout = window.setTimeout(() => setCopied(false), 1500)
    return () => window.clearTimeout(timeout)
  }, [copied])

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
            if (ok) setCopied(true)
          }}
        >
          {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
        </Button>
      </div>
    </div>
  )
}

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
      <TERM_ICON.person aria-hidden className="size-3.5" />
    </span>
  )
}

/** It shows a skeleton while waiting or loading and a question mark if it fails. */
export function MinecraftHead({
  mcUuid,
  baseUrl,
  size = "size-5",
  rounded = "rounded-sm",
}: {
  mcUuid?: string
  baseUrl?: string
  size?: string
  rounded?: string
}) {
  const [broken, setBroken] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const url = mcUuid ? minecraftHeadUrl(baseUrl, mcUuid) : undefined

  /** `undefined` waits. `""` means that nobody has a UUID, and the question mark is drawn. */
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
