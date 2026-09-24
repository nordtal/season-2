import {
  BookOpenTextIcon,
  CalendarIcon,
  CreditCardIcon,
  HardDrivesIcon,
  PlayIcon,
  PulseIcon,
  SlidersHorizontalIcon,
  UsersIcon,
  WrenchIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"

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
  "proxy",
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
  icon?: Icon
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
  icon: Icon
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
    icon: PulseIcon,
    entries: [
      {
        id: "overview",
        label: "Overview",
        to: "/",
        // steward/66, 2026-09-16: three claims, and steward/64 had retired all three. There is no
        // traffic light in the ordinary case any more - it appears only when something is amber or
        // red; "host load" became a metric row of CPU, memory, disk, backlog and the last backup;
        // and the table of every service collapsed into one line saying how many of how many are
        // healthy. A note is a promise about the page behind it, so it names what is there.
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
        id: "operations",
        label: "Overview",
        to: "/operations",
        note: "Runs, drift against the intended state, and the backups.",
        icon: WrenchIcon,
        keywords: ["updates", "drift", "backup", "snapshot"],
      },
      {
        id: "operations-plan",
        label: "Plan",
        to: "/operations/plan",
        note: "What a run would change, before it starts.",
        icon: PlayIcon,
        keywords: ["preview", "dry run", "plan"],
      },
      {
        id: "operations-run",
        label: "Run",
        to: "/operations/runs/$id",
        params: { id: "latest" },
        note: "The report of a single run, line by line.",
        icon: PlayIcon,
        keywords: ["run", "report", "log"],
      },
      {
        // steward/95: this used to point at `/operations/backups/$id` with the id "latest", which
        // is not a backup's name - the report page then asked the worker for a file called
        // "latest". It leads to the list page instead, which is the thing somebody typing "backup"
        // is looking for and which links every report from its own rows.
        id: "operations-backups",
        label: "Backups",
        to: "/operations/backups",
        note: "Runs, archives, retention and where a copy goes that is not on this disk.",
        icon: PlayIcon,
        keywords: ["backup", "archive", "snapshot", "retention", "s3", "storage box", "offsite"],
      },
      {
        id: "operations-restore",
        label: "Restore",
        to: "/operations/restore",
        note: "Put a backup back - the one destructive path in the house.",
        icon: PlayIcon,
        keywords: ["restore", "recover", "emergency"],
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

/** Every entry, flattened - what the command palette lists. */
export const ALL_ENTRIES: NavEntry[] = NAVIGATION.flatMap((group) =>
  group.entries.map((entry) => ({ ...entry, icon: entry.icon ?? group.icon })),
)
