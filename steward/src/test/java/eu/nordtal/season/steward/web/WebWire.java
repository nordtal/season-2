package eu.nordtal.season.steward.web;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.access.AccessGrant;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.access.PackExemptions;
import eu.nordtal.season.database.access.Person;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.phase.DateChange;
import eu.nordtal.season.database.phase.PhaseChange;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.steward.auth.WebAuthn;
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
            Settings.PageSettings.class,
            GameDataRoutes.GameData.class,
            ConsoleCommands.ConsoleTree.class);

    /** Records here whose own simple name would say too little or collide. */
    public static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(Metrics.Curve.class, "Metrics"),
            Map.entry(GameDataRoutes.Icons.class, "GameIcons"),
            Map.entry(CommandTree.Node.class, "CommandNode"),
            Map.entry(eu.nordtal.season.database.game.GameCatalogue.Entry.class, "GameEntry"),
            Map.entry(eu.nordtal.season.database.game.GameCatalogue.Tag.class, "GameTag"),
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
