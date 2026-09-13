import { useParams } from "@tanstack/react-router"

import { Placeholder } from "@/app/placeholder"
import { SERVICES } from "@/app/navigation"

/**
 * Placeholder pages, one per route.
 *
 * They are in one file on purpose: there is nothing in any of them yet but a heading and a
 * sentence, and fourteen files that each hold four lines is fourteen files to delete when the real
 * pages arrive. The moment a page gets content it moves out of here.
 */

export function ZustandPage() {
  return (
    <Placeholder
      title="Zustand"
      note="Die Landeseite: eine Ampel für das ganze Netzwerk, die Auslastung des Hosts und eine Tabelle aller zehn Dienste."
      detail="Hier kommen später die Ampel, die Host-Kennzahlen und die Diensttabelle hin - alles aus der Steward-API, im Sekundentakt nachgeladen."
    />
  )
}

export function DienstPage() {
  const { name } = useParams({ from: "/dienste/$name" })
  const known = (SERVICES as readonly string[]).includes(name)
  return (
    <Placeholder
      title={name}
      note="Ein einzelner Dienst: Logfenster, Konsole und die Schalter, die ihn anhalten oder neu starten."
      detail={
        known
          ? "Hier kommen das Logfenster (gestreamt) und die Konsole hin, dazu Image, Digest und Gesundheitszustand des Containers."
          : `„${name}" ist keiner der zehn Dienste des Stacks. Die Seite bleibt trotzdem erreichbar, damit ein falscher Link nicht ins Leere führt.`
      }
    />
  )
}

export function BetriebPage() {
  return (
    <Placeholder
      title="Betrieb"
      note="Läufe, Drift gegen den Sollzustand und die vorhandenen Sicherungen."
      detail="Hier kommen die Liste der Läufe, der Vergleich zwischen Soll und Ist sowie die Sicherungen mit Zeitpunkt und Größe hin."
    />
  )
}

export function BetriebPlanPage() {
  return (
    <Placeholder
      title="Plan"
      note="Was ein Lauf ändern würde, bevor er startet."
      detail="Hier kommt die Vorschau hin: je Dienst eine Zeile mit alter und neuer Version, und die Entscheidung, ob der Lauf ausgeführt wird."
    />
  )
}

export function BetriebLaufPage() {
  const { id } = useParams({ from: "/betrieb/lauf/$id" })
  return (
    <Placeholder
      title={`Lauf ${id}`}
      note="Der Bericht eines einzelnen Laufs, Zeile für Zeile."
      detail="Hier kommt der Bericht aus update_request.result hin - jede Zeile mit ihrem Ergebnis, und die Dauer des Laufs."
    />
  )
}

export function BetriebSicherungPage() {
  const { id } = useParams({ from: "/betrieb/sicherung/$id" })
  return (
    <Placeholder
      title={`Sicherung ${id}`}
      note="Inhalt und Prüfsumme einer einzelnen Sicherung."
      detail="Hier kommen Zeitpunkt, Größe, Prüfsumme und der Inhalt des Archivs hin - und der Weg zum Wiederherstellen."
    />
  )
}

export function BetriebWiederherstellenPage() {
  return (
    <Placeholder
      title="Wiederherstellen"
      note="Eine Sicherung zurückspielen - der einzige zerstörende Weg in dieser Oberfläche."
      detail="Hier kommt der mehrstufige Ablauf hin: Sicherung wählen, Auswirkung lesen, tippend bestätigen. Ohne Bestätigung passiert nichts."
    />
  )
}

export function KonfigurationPage() {
  const { datei } = useParams({ from: "/konfiguration/$datei" })
  return (
    <Placeholder
      title={datei}
      note="Kommentiertes YAML als Formular - die Beschreibung kommt aus dem @ConfigSpec, nicht aus einer zweiten Liste."
      detail="Hier kommt das Formular hin: je Eigenschaft ein Feld mit dem Kommentar als Hilfetext, dazu der Hinweis, wenn eine Umgebungsvariable den Wert überschreibt."
    />
  )
}

export function SaisonPage() {
  return (
    <Placeholder
      title="Saison"
      note="Phase, Termine und was am Saisonwechsel zurückgesetzt wird."
      detail="Hier kommen die laufende Phase, die geplanten Termine und die Liste der Dinge hin, die eine neue Saison von Grund auf neu aufsetzt."
    />
  )
}

export function ZugaengePage() {
  return (
    <Placeholder
      title="Zugänge"
      note="Wer auf den Server darf, und warum er das darf."
      detail="Hier kommt die Tabelle der Zugänge hin: Person, Quelle des Zugangs, Gültigkeit - und der Weg, einen von Hand zu erteilen oder zu entziehen."
    />
  )
}

export function ZahlungenPage() {
  return (
    <Placeholder
      title="Zahlungen"
      note="Eingänge aus bunq und die Beitragsstufe, die daraus folgt."
      detail="Hier kommen die Zahlungseingänge hin, jeweils mit der Person, der sie zugeordnet wurden, und der Stufe, die sie auslösen."
    />
  )
}

export function KontenPage() {
  return (
    <Placeholder
      title="Konten"
      note="Minecraft-, Discord- und Steward-Identität einer Person an einem Ort."
      detail="Hier kommt die Kontenliste hin, mit den Verknüpfungen zwischen den drei Identitäten und dem Stand ihrer Bestätigung."
    />
  )
}

export function JournalPage() {
  return (
    <Placeholder
      title="Journal"
      note="Jede Änderung, wer sie ausgelöst hat und was sie bewirkt hat."
      detail="Hier kommt das durchsuchbare Protokoll hin - eine Zeile je Änderung, mit Zeitpunkt, auslösender Person und betroffenem Objekt."
    />
  )
}

export function EinstellungenPage() {
  return (
    <Placeholder
      title="Einstellungen"
      note="Steward selbst: Zugriff auf die Oberfläche, Benachrichtigungen, Anbindungen."
      detail="Hier kommen die Einstellungen der Oberfläche hin - wer sie öffnen darf, worüber sie benachrichtigt und woran sie hängt."
    />
  )
}

export function NotFoundPage() {
  return (
    <Placeholder
      title="Seite nicht gefunden"
      note="Diese Adresse gehört zu keiner Seite dieser Oberfläche."
      detail="Mit Strg+K lässt sich jede vorhandene Seite in der Suche finden."
    />
  )
}
