import {
  Activity,
  BookText,
  CalendarRange,
  CreditCard,
  KeyRound,
  Play,
  Server,
  Settings,
  SlidersHorizontal,
  Users,
  Wrench,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"

/**
 * The one list of places in this interface.
 *
 * The sidebar renders it, the command palette renders it and the breadcrumb resolves titles out of
 * it. There is deliberately no second copy: a route that exists and is not here is a route nobody
 * can reach, and that is a bug we would rather have loudly than quietly.
 *
 * Everything here is English - labels, ids, paths, notes and comments alike. Steward had German
 * labels while the audience was three German admins; that split meant every string existed in one
 * language and every identifier beside it in another, and the seam was a standing invitation to
 * leave half of a rename behind.
 */

/** The ten containers of the season 2 stack, in the order the sidebar lists them. */
export const SERVICES = [
  "smp",
  "hunger-games",
  "limbo",
  "network-control",
  "discord-bot",
  "postgres",
  "caddy",
  "steward-ui",
  "steward-worker",
  "steward-deployer",
] as const

export type ServiceName = (typeof SERVICES)[number]

export type NavEntry = {
  /** Stable id, used as a React key and as the command palette's search value. */
  id: string
  label: string
  to: string
  /** Route params, for the parameterised routes. */
  params?: Record<string, string>
  /** One line of what the page is for. The command palette shows it; so does the placeholder. */
  note: string
  icon?: LucideIcon
  /** Words an admin might type that are not in the label. */
  keywords?: string[]
}

export type NavGroup = {
  id: string
  /**
   * The heading over the group - and **optional on purpose**.
   *
   * Eight of the ten groups here used to hold exactly one entry, so the sidebar drew "Season" as a
   * heading and "Season" as the row under it, eight times over. That is about a sixth of the
   * sidebar's height spent saying each thing twice. A group with no label is drawn without a
   * heading, and the command palette lists it without one; the entries are their own names.
   */
  label?: string
  icon: LucideIcon
  entries: NavEntry[]
}

/**
 * Four groups, not ten.
 *
 * Only `Services` and `Operations` are a set of things that belong together and need saying so.
 * Everything else is one page with one name, and those are gathered into two unlabelled groups -
 * the one above (the start page) and the one below (everything that is neither a container nor a
 * run). Keeping the shape as groups rather than a flat list is what lets the separators and the
 * command palette's sections stay where they are.
 */
export const NAVIGATION: NavGroup[] = [
  {
    id: "overview",
    icon: Activity,
    entries: [
      {
        id: "status",
        label: "Overview",
        to: "/",
        note: "The traffic light, host load and the table of every service.",
        icon: Activity,
        keywords: ["home", "dashboard", "health", "overview", "status"],
      },
    ],
  },
  {
    id: "services",
    label: "Services",
    icon: Server,
    entries: SERVICES.map((name) => ({
      id: `service-${name}`,
      label: name,
      to: "/services/$name",
      params: { name },
      note: `Log window and console for ${name}.`,
      icon: Server,
      keywords: ["container", "log", "console", "restart"],
    })),
  },
  {
    id: "operations",
    label: "Operations",
    icon: Wrench,
    entries: [
      {
        id: "operations",
        label: "Overview",
        to: "/operations",
        note: "Runs, drift against the intended state, and the backups.",
        icon: Wrench,
        keywords: ["updates", "drift", "backup", "snapshot"],
      },
      {
        id: "operations-plan",
        label: "Plan",
        to: "/operations/plan",
        note: "What a run would change, before it starts.",
        icon: Play,
        keywords: ["preview", "dry run", "plan"],
      },
      {
        id: "operations-run",
        label: "Run",
        to: "/operations/runs/$id",
        params: { id: "latest" },
        note: "The report of a single run, line by line.",
        icon: Play,
        keywords: ["run", "report", "log"],
      },
      {
        id: "operations-backup",
        label: "Backup",
        to: "/operations/backups/$id",
        params: { id: "latest" },
        note: "Contents and checksum of a single backup.",
        icon: Play,
        keywords: ["backup", "archive", "checksum"],
      },
      {
        id: "operations-restore",
        label: "Restore",
        to: "/operations/restore",
        note: "Put a backup back - the one destructive path in the house.",
        icon: Play,
        keywords: ["restore", "recover", "emergency"],
      },
    ],
  },
  {
    id: "the-rest",
    icon: SlidersHorizontal,
    entries: [
      {
        id: "season",
        label: "Season",
        to: "/season",
        note: "Phase, dates, and what a season change resets.",
        icon: CalendarRange,
        keywords: ["season", "phase", "reset", "launch"],
      },
      {
        id: "access",
        label: "Access",
        to: "/access",
        note: "Who may join the server, and why they may.",
        icon: KeyRound,
        keywords: ["access", "whitelist", "roles"],
      },
      {
        id: "payments",
        label: "Payments",
        to: "/payments",
        note: "Incoming bunq payments and the tier that follows from them.",
        icon: CreditCard,
        keywords: ["bunq", "contribution", "money", "payments"],
      },
      {
        id: "accounts",
        label: "Accounts",
        to: "/accounts",
        note: "One person's Minecraft, Discord and Steward identity in one place.",
        icon: Users,
        keywords: ["accounts", "players", "discord", "link"],
      },
      {
        id: "journal",
        label: "Journal",
        to: "/journal",
        note: "Every change, who triggered it and what it did.",
        icon: BookText,
        keywords: ["audit", "history", "trail", "log"],
      },
      {
        id: "settings",
        label: "Settings",
        to: "/settings",
        note: "Steward itself: who reaches the interface, notifications, integrations.",
        icon: Settings,
        keywords: ["settings", "account", "notifications"],
      },
    ],
  },
]

/** Every entry, flattened - what the command palette lists. */
export const ALL_ENTRIES: NavEntry[] = NAVIGATION.flatMap((group) =>
  group.entries.map((entry) => ({ ...entry, icon: entry.icon ?? group.icon })),
)
