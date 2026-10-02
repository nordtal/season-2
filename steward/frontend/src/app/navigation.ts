import {
  ArchiveIcon,
  ArrowCircleUpIcon,
  BellIcon,
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

import { topologyOf } from "@/components/steward/network/topology"
import { useTopology } from "@/lib/queries"

/** The one list of places, read by the sidebar, the palette and the breadcrumb; a route missing here is unreachable. */

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

/**
 * Four groups: `Services`, `Operations` and two unlabelled ones for the single pages around them.
 *
 * `services` are the names `/api/topology` serves, in the network table's order; {@link useNavigation} passes them.
 */
export function navigation(services: readonly string[]): NavGroup[] {
  return [
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
      entries: services.map((name) => ({
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
        {
          id: "operations-alerts",
          label: "Alerts",
          to: "/alerts",
          note: "What is wrong now, and every alert raised lately.",
          icon: BellIcon,
          keywords: ["alert", "issue", "problem", "warning", "down", "notification"],
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
}

/** The navigation with the services the stack serves, which list none until `/api/topology` has answered. */
export function useNavigation(): NavGroup[] {
  const topology = useTopology()
  return navigation(topology.data ? topologyOf(topology.data).names : [])
}
