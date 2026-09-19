import { CheckIcon, CopyIcon, ImageBrokenIcon, UserIcon } from "@phosphor-icons/react"
import { useState } from "react"

import { StewardMark } from "@/app/steward-mark"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/**
 * The one display of a person, and the one place a Discord id or a Minecraft UUID may be drawn.
 *
 * Till, 2026-09-15 (steward/45): identifiers belong in exactly one place - this display's popover,
 * for copying. Nowhere else - not in the Access list, not in picker lists, not in channel fields.
 * **Closed**, this component draws an avatar and a name and never the id behind either. Opened,
 * its popover is where both ids live, each next to a button that copies it - see
 * {@link copyToClipboard} for what happens when the browser will not let a script touch the
 * clipboard at all.
 *
 * `steward/46` puts this to work in the Access table; `steward/53` is what is left of the rest of
 * the interface. Neither of those tickets is this file's to enforce - {@link identityLeakPattern}
 * exists so that a *consumer* can assert its own output never shows a raw id outside this
 * component, without this module having to know which pages exist.
 */

/*
 * THERE IS NO STALENESS MARK HERE ANY MORE. Till asked on 2026-09-19 (steward/123) for the
 * asterisk behind the Discord name and the Minecraft name to go.
 *
 * `STALE_AFTER_MS`, `isStale`, the ` *` after a name, the `data-stale` attribute and the two
 * `title` tooltips all stood here until then and are gone together, because once the asterisk went
 * nothing announced any of the rest: a `title` is invisible until hovered, and it is not reachable
 * at all on the phone this interface is designed for first. An attribute no stylesheet reads and a
 * tooltip nothing points at are not a quieter version of the feature - they are the feature
 * removed, with the code left behind.
 *
 * If the distinction is wanted again, it comes back as a design somebody can see, and this comment
 * is the note that it was once a thirty-day threshold.
 */

/**
 * A Minecraft head, composed rather than stored.
 *
 * `eu.nordtal.s2.common.access.MinecraftProfile`'s javadoc is the other half of this: the image is
 * a pure function of the uuid and a configured base, precisely so that reconfiguring the service
 * never leaves a stale rendering behind. `baseUrl` is what `/api/settings` answers as
 * `minecraftHeadBaseUrl` - see `useAvatarBaseUrl`.
 */
export function minecraftHeadUrl(baseUrl: string | undefined, mcUuid: string): string | null {
  if (!baseUrl) return null

  // THE UUID GOES INTO THE PATH, NEVER ONTO THE END OF THE QUERY. The configured base may carry
  // one - the default does, because api.mineatar.io serves 32x32 unless asked otherwise and the
  // identity display draws a head at up to 32 CSS pixels, which is 96 real ones on a phone
  // (steward/122, Till chose `?scale=16`). Appending blindly would produce
  // `…/face?scale=16/<uuid>`, a path that is not the uuid's and an image nobody ever sees.
  const [path, query] = splitQuery(baseUrl)

  // steward/111, re-measured 2026-09-19 for steward/122: hyphens stripped. All three services this
  // has ever pointed at - mineatar, mc-heads, crafatar - answer 200 to BOTH spellings, so this is
  // no longer the compatibility claim it was written as. It stays because one spelling has to be
  // chosen and a stable URL is a cached one: two spellings of the same face are two cache entries.
  return `${path.replace(/\/+$/, "")}/${mcUuid.replace(/-/g, "")}${query}`
}

/** `["https://host/face", "?scale=16"]`, with an empty second half when there is no query. */
function splitQuery(baseUrl: string): [string, string] {
  const at = baseUrl.search(/[?#]/)
  return at === -1 ? [baseUrl, ""] : [baseUrl.slice(0, at), baseUrl.slice(at)]
}

/**
 * The pattern `identity.test.tsx` and `access.test.tsx` hold a page's rendered text against.
 *
 * A Discord snowflake is a 17-20 digit decimal number (Discord's own range as ids have grown since
 * 2015); a Minecraft UUID is the standard 8-4-4-4-12 hex form proxy and the database both
 * use. Deliberately loose about digits either side - a test wants to catch an id sitting in plain
 * text, not verify it is exactly one of those two shapes.
 */
export const IDENTIFIER_PATTERN = /\b\d{17,20}\b|\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/

/**
 * Copies text to the clipboard where the browser allows it, and never fails silently.
 *
 * `navigator.clipboard` needs a secure context - true on `https://steward.dev.nordtal.eu`, false on
 * `http://127.0.0.1:8080` while developing (steward/45's footnote). Rather than a button that does
 * nothing and says nothing on the second of those, the id is always ALSO drawn as selectable text
 * next to it - see {@link CopyableId} - so the fallback is not this function succeeding, it is the
 * text having been selectable the entire time.
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
  /**
   * Not required when {@link system} is set - a system-triggered action has no Discord id to draw
   * a popover of, and forcing a caller to invent one would be exactly the raw-identifier leak this
   * component exists to prevent.
   */
  discordId?: string
  discordUsername?: string
  discordDisplayName?: string
  discordAvatarUrl?: string
  mcUuid?: string
  mcName?: string
  /** Where a Minecraft head is composed from - `useAvatarBaseUrl()`. Absent draws no head at all. */
  avatarBaseUrl?: string
  className?: string
  /**
   * Steward itself did this, not a person (steward/82) - the nightly backup clock, an orphan
   * settle, a journal line the bot wrote with nobody behind it. Drawn as the server's own mark and
   * the word "Steward", in the same closed shape a person gets, and with no popover: there is no id
   * behind this one to reveal, so opening one would show nothing rather than something.
   */
  system?: boolean
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
      <span
        className={
          "inline-flex min-w-0 items-center gap-2 " + (props.className ?? "")
        }
      >
        <StewardMark className="size-5 shrink-0" />
        <span className="truncate text-sm text-foreground">Steward</span>
      </span>
    )
  }

  const name = displayName(props)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={
            // `max-w-full` alongside `min-w-0` (steward/103): `min-w-0` only lets this box shrink,
            // it does not cap it, so with nothing to shrink against the button sized itself to the
            // name and the cell clipped the remainder with no ellipsis - `no Discord name on
            // recora`, measured at 1440px on 2026-09-17. The `truncate` below cannot act until the
            // box it lives in has an upper bound.
            "inline-flex min-w-0 max-w-full items-center gap-2 rounded-full text-left outline-none " +
            "hover:opacity-80 focus-visible:ring-[3px] focus-visible:ring-ring/50 " +
            (props.className ?? "")
          }
        >
          <DiscordAvatar url={props.discordAvatarUrl} name={name.text} />
          <span
            className={
              "truncate text-sm " + (name.text ? "text-foreground" : "text-muted-foreground italic")
            }
          >
            {name.text ?? "no Discord name on record"}
          </span>
        </button>
      </PopoverTrigger>
      <PopoverContent className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <DiscordAvatar url={props.discordAvatarUrl} name={name.text} size="size-8" />
          <div className="flex min-w-0 flex-col">
            <span className="truncate text-sm font-medium">
              {name.text ?? "no Discord name on record"}
            </span>
            {name.text ? null : (
              <span className="text-xs text-muted-foreground">
                Never observed, or no longer a guild member.
              </span>
            )}
          </div>
        </div>

        <CopyableId label="Discord-ID" value={props.discordId ?? ""} />

        {props.mcUuid ? (
          <>
            <div className="flex items-center gap-2 border-t border-border pt-3">
              <MinecraftHead
                mcUuid={props.mcUuid}
                baseUrl={props.avatarBaseUrl}
                size="size-8"
                rounded="rounded-md"
              />
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-sm font-medium">
                  {props.mcName ?? "no Minecraft name on record"}
                </span>
                {props.mcName ? null : (
                  <span className="text-xs text-muted-foreground">
                    Linked, but never seen joining yet.
                  </span>
                )}
              </div>
            </div>
            <CopyableId label="Minecraft-UUID" value={props.mcUuid} />
          </>
        ) : (
          <p className="border-t border-border pt-3 text-xs text-muted-foreground">
            No Minecraft account linked.
          </p>
        )}
      </PopoverContent>
    </Popover>
  )
}

/**
 * A row that shows an id as selectable text AND offers to copy it - see the module comment on why
 * both exist rather than only the button.
 */
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
          className="min-w-0 flex-1 truncate rounded-sm border border-border bg-muted px-2 py-1 font-mono text-xs select-all"
        />
        <Button
          type="button"
          variant="outline"
          size="icon-xs"
          aria-label={`Copy ${label}`}
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
function DiscordAvatar({
  url,
  name,
  size = "size-5",
}: {
  url?: string
  name: string | null
  size?: string
}) {
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
      title={name ? undefined : "No avatar on record."}
    >
      <UserIcon aria-hidden className="size-3.5" />
    </span>
  )
}

/**
 * The small rounded-square Minecraft head, drawn from a composed URL.
 *
 * A head that fails to load is a placeholder, not a broken-image icon - Crafatar is a free service
 * with no uptime promise (steward/45), and this page must not wait on it or look damaged when it is
 * briefly gone.
 */
export function MinecraftHead({
  mcUuid,
  baseUrl,
  size = "size-5",
  rounded = "rounded-sm",
}: {
  mcUuid: string
  baseUrl?: string
  size?: string
  rounded?: string
}) {
  const [broken, setBroken] = useState(false)
  const url = minecraftHeadUrl(baseUrl, mcUuid)

  if (url && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- this is not Next.js
      <img
        src={url}
        alt=""
        className={`${size} shrink-0 ${rounded} border border-border object-cover`}
        onError={() => setBroken(true)}
      />
    )
  }
  return (
    <span
      className={`${size} inline-flex shrink-0 items-center justify-center ${rounded} border border-border bg-secondary text-muted-foreground`}
      title="No head image available right now."
    >
      <ImageBrokenIcon aria-hidden className="size-3" />
    </span>
  )
}

/**
 * The Minecraft half on its own - the Access table's "Minecraft" column and the person dialog's
 * "Minecraft" stat both draw a name and a head and nothing that can be copied, because the one
 * place to copy the uuid is the identity popover already on the row.
 */
export function MinecraftFace({
  mcUuid,
  mcName,
  avatarBaseUrl,
}: {
  mcUuid: string
  mcName?: string
  avatarBaseUrl?: string
}) {
  return (
    // `min-w-0` on both boxes and `truncate` on the text (steward/103): without it the name is
    // drawn at its full width straight past the right edge of a 390px card, where the table
    // container clips it with no ellipsis - `no name observed ye`.
    <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
      <MinecraftHead mcUuid={mcUuid} baseUrl={avatarBaseUrl} />
      <span
        className={
          mcName
            ? "min-w-0 truncate text-sm"
            : "min-w-0 truncate text-sm text-muted-foreground italic"
        }
      >
        {mcName ?? "no name yet"}
      </span>
    </span>
  )
}
