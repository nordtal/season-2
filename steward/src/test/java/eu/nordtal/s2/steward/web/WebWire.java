package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.access.AccessGrant;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.database.access.PackExemptions;
import eu.nordtal.s2.database.access.Person;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.phase.DateChange;
import eu.nordtal.s2.database.phase.PhaseChange;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.steward.auth.WebAuthn;
import java.util.List;
import java.util.Map;

/** The records the routes of this package answer and read, for the generated TypeScript types. */
public final class WebWire {

    /** Every record a route here answers or reads at its top level. */
    public static final List<Class<?>> ROOTS = List.of(
            Profile.Me.class,
            PushEndpoints.WebPushPublicKey.class,
            PushEndpoints.PushDevice.class,
            AlertRoutes.Alerts.class,
            AlertRoutes.AlertPreference.class,
            Metrics.Curve.class,
            AgentApi.AgentState.class,
            Updates.Run.class,
            Updates.ActiveRun.class,
            SeasonRoutes.Season.class,
            PhaseChange.class,
            DateChange.class,
            GameActions.SmpTrack.class,
            GameActions.HungerGamesRound.class,
            GameActions.CommandAsked.class,
            CommandApi.CommandRun.class,
            Announcements.RecentAnnouncements.class,
            Announcements.AnnouncementsAsked.class,
            AccessApi.Settled.class,
            AccessApi.AccessRequestRun.class,
            Person.class,
            AccessGrant.class,
            PaymentRequest.class,
            AuditEntry.class,
            SecondFactor.KeyRegistered.class,
            WebAuthn.Held.class,
            SecondFactor.KeyRenamed.class,
            SecondFactor.KeyRemoved.class,
            AdminApi.Granted.class,
            AdminTree.Revocation.class,
            PackExemptionApi.Exempted.class,
            Settings.PageSettings.class);

    /** Records here whose own simple name would say too little or collide. */
    public static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(Metrics.Curve.class, "Metrics"),
            Map.entry(Announcements.RecentAnnouncements.class, "Announcements"),
            Map.entry(AccessGrant.class, "Grant"),
            Map.entry(PaymentRequest.class, "Payment"),
            Map.entry(AuditEntry.class, "JournalEntry"),
            Map.entry(Actor.Kind.class, "ActorKind"),
            Map.entry(UpdateReport.class, "Report"),
            Map.entry(UpdateReport.ServiceLine.class, "ReportLine"),
            Map.entry(UpdateReport.Change.class, "ReportChange"),
            Map.entry(UpdateReport.State.class, "LineState"),
            Map.entry(UpdateReport.Change.State.class, "ChangeState"),
            Map.entry(UpdateReport.Stage.class, "ReportStage"),
            Map.entry(WebAuthn.Held.class, "KeyHeld"),
            Map.entry(AdminApi.Granted.class, "AdminGranted"),
            Map.entry(AdminTree.Grant.class, "GrantOutcome"),
            Map.entry(AdminTree.Revocation.class, "AdminRevoked"),
            Map.entry(AdminTree.Revocation.Outcome.class, "RevokeOutcome"),
            Map.entry(PackExemptions.Outcome.class, "ExemptionOutcome"));

    private WebWire() {}
}
