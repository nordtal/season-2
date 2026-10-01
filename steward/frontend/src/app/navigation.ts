import {
  ArchiveIcon,
  ArrowCircleUpIcon,
  BookOpenTextIcon,
  CalendarIcon,
  CreditCardIcon,
  HardDrivesIcon,
  MegaphoneIcon,
  PulseIcon,
  SlidersHorizontalIcon,
  UsersIcon,
  WrenchIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import type { LinkProps } from "@tanstack/react-router"

/** The one list of places, read by the sidebar, the palette and the breadcrumb; a route missing here is unreachable. */

/** The ten containers of the season 2 stack, in the order the sidebar lists them. */
export const SERVICES = [
  "smp",
  "hunger-games",
  "limbo",
  "proxy",
  "discord-bot",
  "postgres",
  "caddy",
  "steward",
  "steward-agent",
  "steward-bunq",
] as const

export type ServiceName = (typeof SERVICES)[number]

export type NavEntry = {
  /** Stable id, used as a React key and as the command palette's search value. */
  id: string
  label: string
  /** One line of what the page is for, shown by the command palette and the placeholder. */
  note: string
  icon?: Icon
  /** Words an admin might type that are not in the label. */
  keywords?: string[]
} & (
  | { to: "/services/$name"; params: { name: string } }
  | { to: Exclude<LinkProps["to"], undefined | "/services/$name">; params?: undefined }
)

export type NavGroup = {
  id: string
  /** The heading over the group, left out where a group's single entry names itself. */
  label?: string
  icon: Icon
  entries: NavEntry[]
}

/** Four groups: `Services`, `Operations` and two unlabelled ones for the single pages around them. */
export const NAVIGATION: NavGroup[] = [
  {
    id: "overview",
    icon: PulseIcon,
    entries: [
      {
        id: "overview",
        label: "Overview",
        to: "/",
        note: "Whether anything needs attention, the numbers behind it, and the season.",
        icon: PulseIcon,
        keywords: ["home", "dashboard", "health", "overview", "status"],
      },
    ],
  },
  {
    id: "services",
    label: "Services",
    icon: HardDrivesIcon,
    entries: SERVICES.map((name) => ({
      id: `service-${name}`,
      label: name,
      to: "/services/$name",
      params: { name },
      note: `Log window and console for ${name}.`,
      icon: HardDrivesIcon,
      keywords: ["container", "log", "console", "restart"],
    })),
  },
  {
    id: "operations",
    label: "Operations",
    icon: WrenchIcon,
    entries: [
      {
        id: "operations-updates",
        label: "Updates",
        to: "/operations/updates",
        note: "What a run would install, the images, the update runs and their schedule.",
        icon: ArrowCircleUpIcon,
        keywords: ["update", "drift", "image", "plugin", "restart", "schedule"],
      },
      {
        /** The list page rather than a backup id, since "latest" is not a backup's name. */
        id: "operations-backups",
        label: "Backups",
        to: "/operations/backups",
        note: "Runs, archives, retention, the offsite copy and the way back.",
        icon: ArchiveIcon,
        keywords: ["backup", "archive", "snapshot", "retention", "s3", "storage box", "offsite", "restore"],
      },
    ],
  },
  {
    id: "the-rest",
    icon: SlidersHorizontalIcon,
    entries: [
      {
        id: "season",
        label: "Season",
        to: "/season",
        note: "Phase, dates, and what a season change resets.",
        icon: CalendarIcon,
        keywords: ["season", "phase", "reset", "launch"],
      },
      {
        id: "announcements",
        label: "Announcements",
        to: "/announcements",
        note: "Write an announcement in every language, and read the latest ones.",
        icon: MegaphoneIcon,
        keywords: ["announce", "announcement", "discord", "news", "broadcast"],
      },
      {
        id: "access",
        label: "Users",
        to: "/access",
        note: "Who may join the server, and why they may.",
        icon: UsersIcon,
        keywords: ["access", "whitelist", "roles", "accounts", "players", "discord", "link"],
      },
      {
        id: "payments",
        label: "Payments",
        to: "/payments",
        note: "Incoming bunq payments and the tier that follows from them.",
        icon: CreditCardIcon,
        keywords: ["bunq", "contribution", "money", "payments"],
      },
      {
        id: "journal",
        label: "Journal",
        to: "/journal",
        note: "Every change, who triggered it and what it did.",
        icon: BookOpenTextIcon,
        keywords: ["audit", "history", "trail", "log"],
      },
    ],
  },
]

/** Every entry, flattened, as the command palette lists them. */
export const ALL_ENTRIES: NavEntry[] = NAVIGATION.flatMap((group) =>
  group.entries.map((entry) => ({ ...entry, icon: entry.icon ?? group.icon })),
)
