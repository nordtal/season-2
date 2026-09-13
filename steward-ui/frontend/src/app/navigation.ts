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
 * Labels are German - the audience is three German admins - while everything a developer reads
 * (ids, paths, comments) stays English.
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
  /** Words a German admin might type that are not in the label. */
  keywords?: string[]
}

export type NavGroup = {
  id: string
  label: string
  icon: LucideIcon
  entries: NavEntry[]
}

export const NAVIGATION: NavGroup[] = [
  {
    id: "zustand",
    label: "Zustand",
    icon: Activity,
    entries: [
      {
        id: "zustand",
        label: "Übersicht",
        to: "/",
        note: "Ampel, Hostauslastung und die Tabelle aller Dienste.",
        icon: Activity,
        keywords: ["start", "dashboard", "ampel", "status"],
      },
    ],
  },
  {
    id: "dienste",
    label: "Dienste",
    icon: Server,
    entries: SERVICES.map((name) => ({
      id: `dienst-${name}`,
      label: name,
      to: "/dienste/$name",
      params: { name },
      note: `Logfenster und Konsole für ${name}.`,
      icon: Server,
      keywords: ["container", "log", "konsole", "neustart"],
    })),
  },
  {
    id: "betrieb",
    label: "Betrieb",
    icon: Wrench,
    entries: [
      {
        id: "betrieb",
        label: "Übersicht",
        to: "/betrieb",
        note: "Läufe, Drift gegen den Sollzustand und die Sicherungen.",
        icon: Wrench,
        keywords: ["updates", "drift", "backup", "sicherung"],
      },
      {
        id: "betrieb-plan",
        label: "Plan",
        to: "/betrieb/plan",
        note: "Was ein Lauf ändern würde, bevor er startet.",
        icon: Play,
        keywords: ["vorschau", "dry run", "planen"],
      },
      {
        id: "betrieb-lauf",
        label: "Lauf",
        to: "/betrieb/lauf/$id",
        params: { id: "letzter" },
        note: "Der Bericht eines einzelnen Laufs, Zeile für Zeile.",
        icon: Play,
        keywords: ["run", "bericht", "protokoll"],
      },
      {
        id: "betrieb-sicherung",
        label: "Sicherung",
        to: "/betrieb/sicherung/$id",
        params: { id: "letzte" },
        note: "Inhalt und Prüfsumme einer einzelnen Sicherung.",
        icon: Play,
        keywords: ["backup", "archiv", "prüfsumme"],
      },
      {
        id: "betrieb-wiederherstellen",
        label: "Wiederherstellen",
        to: "/betrieb/wiederherstellen",
        note: "Eine Sicherung zurückspielen - der einzige zerstörende Weg im Haus.",
        icon: Play,
        keywords: ["restore", "zurückspielen", "notfall"],
      },
    ],
  },
  {
    id: "konfiguration",
    label: "Konfiguration",
    icon: SlidersHorizontal,
    entries: [
      {
        id: "konfiguration",
        label: "Dateien",
        to: "/konfiguration",
        note: "Kommentiertes YAML als Formular, aus dem @ConfigSpec gelesen.",
        icon: SlidersHorizontal,
        keywords: ["yaml", "config", "einstellungen", "spec"],
      },
    ],
  },
  {
    id: "saison",
    label: "Saison",
    icon: CalendarRange,
    entries: [
      {
        id: "saison",
        label: "Saison",
        to: "/saison",
        note: "Phase, Termine und was am Saisonwechsel zurückgesetzt wird.",
        icon: CalendarRange,
        keywords: ["season", "phase", "reset"],
      },
    ],
  },
  {
    id: "zugaenge",
    label: "Zugänge",
    icon: KeyRound,
    entries: [
      {
        id: "zugaenge",
        label: "Zugänge",
        to: "/zugaenge",
        note: "Wer auf den Server darf, und warum er das darf.",
        icon: KeyRound,
        keywords: ["access", "whitelist", "rollen"],
      },
      {
        id: "zahlungen",
        label: "Zahlungen",
        to: "/zahlungen",
        note: "Eingänge aus bunq und die Beitragsstufe, die daraus folgt.",
        icon: CreditCard,
        keywords: ["bunq", "beitrag", "geld", "payments"],
      },
    ],
  },
  {
    id: "konten",
    label: "Konten",
    icon: Users,
    entries: [
      {
        id: "konten",
        label: "Konten",
        to: "/konten",
        note: "Minecraft-, Discord- und Steward-Identität einer Person an einem Ort.",
        icon: Users,
        keywords: ["accounts", "spieler", "discord", "verknüpfung"],
      },
    ],
  },
  {
    id: "journal",
    label: "Journal",
    icon: BookText,
    entries: [
      {
        id: "journal",
        label: "Journal",
        to: "/journal",
        note: "Jede Änderung, wer sie ausgelöst hat und was sie bewirkt hat.",
        icon: BookText,
        keywords: ["audit", "verlauf", "history", "log"],
      },
    ],
  },
  {
    id: "einstellungen",
    label: "Einstellungen",
    icon: Settings,
    entries: [
      {
        id: "einstellungen",
        label: "Einstellungen",
        to: "/einstellungen",
        note: "Steward selbst: Zugriff auf die Oberfläche, Benachrichtigungen, Anbindungen.",
        icon: Settings,
        keywords: ["settings", "konto", "benachrichtigungen"],
      },
    ],
  },
]

/** Every entry, flattened - what the command palette lists. */
export const ALL_ENTRIES: NavEntry[] = NAVIGATION.flatMap((group) =>
  group.entries.map((entry) => ({ ...entry, icon: entry.icon ?? group.icon })),
)
