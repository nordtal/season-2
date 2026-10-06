package eu.nordtal.season.steward;

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
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.schema.SchemaNode;
import eu.nordtal.season.steward.access.AccessApi;
import eu.nordtal.season.steward.access.PackExemptionApi;
import eu.nordtal.season.steward.alert.AlertRoutes;
import eu.nordtal.season.steward.alert.PushEndpoints;
import eu.nordtal.season.steward.auth.AdminApi;
import eu.nordtal.season.steward.auth.Profile;
import eu.nordtal.season.steward.auth.SecondFactor;
import eu.nordtal.season.steward.auth.WebAuthn;
import eu.nordtal.season.steward.game.Announcements;
import eu.nordtal.season.steward.game.CommandApi;
import eu.nordtal.season.steward.game.ConsoleCommands;
import eu.nordtal.season.steward.game.GameActions;
import eu.nordtal.season.steward.game.GameDataRoutes;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.metric.Metrics;
import eu.nordtal.season.steward.season.SeasonRoutes;
import eu.nordtal.season.steward.settings.Reloading;
import eu.nordtal.season.steward.settings.Settings;
import eu.nordtal.season.steward.settings.SettingsDocument;
import eu.nordtal.season.steward.stack.ActionEntry;
import eu.nordtal.season.steward.stack.AgentApi;
import eu.nordtal.season.steward.stack.PluginsForward;
import eu.nordtal.season.steward.stack.Routes;
import eu.nordtal.season.steward.stack.StackApi;
import eu.nordtal.season.steward.update.Updates;
import java.util.List;
import java.util.Map;

/** The records the routes answer and read, for the generated TypeScript types. */
public final class StewardWire {

    /** Every record a route answers or reads at its top level. */
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
            ConsoleCommands.ConsoleTree.class,
            StackApi.ServiceTable.class,
            StackApi.Host.class,
            StackApi.Schedule.class,
            AgentWire.Archive.class,
            AgentWire.Plugins.class,
            AgentWire.PluginSearch.class,
            AgentWire.Resolve.class,
            ActionEntry.Action.class,
            StackApi.RestoreAsked.class,
            PluginsForward.RemovalAsked.class,
            SettingsDocument.Location.class,
            SettingsDocument.Document.class,
            AgentWire.Descriptor.class,
            MessagesApi.BundleLocation.class,
            MessagesApi.Bundle.class,
            MessagesApi.Saved.class,
            MessagesApi.Fallback.class,
            MessagesApi.Syntax.class,
            MessageCheck.Problem.class,
            AgentWire.PluginAdded.class,
            Routes.ConsoleSent.class,
            StackApi.NetworkMap.class);

    /** Records whose own simple name would say too little or collide. */
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
            Map.entry(PackExemptions.Outcome.class, "ExemptionOutcome"),
            Map.entry(AgentWire.Archive.class, "Backup"),
            Map.entry(AgentWire.Descriptor.class, "PluginDescriptor"),
            Map.entry(AgentWire.Plugin.class, "ServicePlugin"),
            Map.entry(AgentWire.Plugins.class, "ServicePlugins"),
            Map.entry(AgentWire.Resolve.class, "Available"),
            Map.entry(AgentWire.ResolvedChange.class, "AvailableChange"),
            Map.entry(ImageResult.State.class, "ImageState"),
            Map.entry(SettingsDocument.Location.class, "ConfigLocation"),
            Map.entry(SettingsDocument.Document.class, "ConfigDocument"),
            Map.entry(SettingsDocument.Entry.class, "ConfigEntry"),
            Map.entry(SettingsDocument.Shape.class, "ConfigShape"),
            Map.entry(SchemaNode.Choices.class, "ConfigChoices"),
            Map.entry(SchemaNode.ProtectedEntry.class, "ConfigProtectedEntry"),
            Map.entry(SettingsDocument.Reference.class, "ConfigReference"),
            Map.entry(Refers.To.class, "ReferenceKind"),
            Map.entry(Reloading.class, "ReloadOutcome"),
            Map.entry(Reloading.Status.class, "ReloadStatus"),
            Map.entry(MessagesApi.BundleLocation.class, "MessageBundleLocation"),
            Map.entry(MessagesApi.Bundle.class, "MessageBundle"),
            Map.entry(MessagesApi.Saved.class, "MessageSaveResult"),
            Map.entry(MessagesApi.Fallback.class, "MessageFallback"),
            Map.entry(MessagesApi.FallbackReason.class, "MessageFallbackReason"),
            Map.entry(MessagesApi.PreviewTarget.class, "MessagePreviewTarget"),
            Map.entry(MessagesApi.Syntax.class, "MessageSyntax"),
            Map.entry(MessageCheck.Problem.class, "MessageProblem"));

    private StewardWire() {}
}
