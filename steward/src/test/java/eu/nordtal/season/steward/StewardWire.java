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
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Item;
import eu.nordtal.season.messages.document.Line;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.document.Value;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.schema.SchemaNode;
import eu.nordtal.season.steward.access.AccessApi;
import eu.nordtal.season.steward.access.JournalPage;
import eu.nordtal.season.steward.access.PackExemptionApi;
import eu.nordtal.season.steward.alert.AlertRoutes;
import eu.nordtal.season.steward.alert.PushEndpoints;
import eu.nordtal.season.steward.auth.AdminApi;
import eu.nordtal.season.steward.auth.AuthenticatorApp;
import eu.nordtal.season.steward.auth.Profile;
import eu.nordtal.season.steward.auth.SecondFactor;
import eu.nordtal.season.steward.auth.WebAuthn;
import eu.nordtal.season.steward.game.Announcements;
import eu.nordtal.season.steward.game.CommandApi;
import eu.nordtal.season.steward.game.ConsoleCommands;
import eu.nordtal.season.steward.game.GameActions;
import eu.nordtal.season.steward.game.GameDataRoutes;
import eu.nordtal.season.steward.game.Inventories;
import eu.nordtal.season.steward.messages.DocumentsApi;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.messages.TranslationSearch;
import eu.nordtal.season.steward.metric.Metrics;
import eu.nordtal.season.steward.season.SeasonRoutes;
import eu.nordtal.season.steward.settings.Reloading;
import eu.nordtal.season.steward.settings.Settings;
import eu.nordtal.season.steward.settings.SettingsApi;
import eu.nordtal.season.steward.settings.SettingsDocument;
import eu.nordtal.season.steward.stack.AgentApi;
import eu.nordtal.season.steward.stack.PluginsForward;
import eu.nordtal.season.steward.stack.Routes;
import eu.nordtal.season.steward.stack.StackApi;
import eu.nordtal.season.steward.update.Updates;
import java.util.List;
import java.util.Map;

/** The records that the routes answer and read, for the generated TypeScript types. */
public final class StewardWire {

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
            Inventories.PlayerInventories.class,
            Inventories.StackRemoval.class,
            CommandApi.CommandRun.class,
            Announcements.AnnouncementsAsked.class,
            AccessApi.Settled.class,
            Person.class,
            AccessGrant.class,
            PaymentRequest.class,
            AuditEntry.class,
            JournalPage.class,
            SecondFactor.KeyRegistered.class,
            WebAuthn.Held.class,
            SecondFactor.KeyRenamed.class,
            SecondFactor.KeyRemoved.class,
            AuthenticatorApp.Enrolment.class,
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
            StackApi.RestoreAsked.class,
            PluginsForward.RemovalAsked.class,
            AgentWire.PluginFiles.class,
            PluginsForward.PluginFileView.class,
            PluginsForward.PluginFileSaved.class,
            PluginsForward.PluginFileEdit.class,
            SettingsDocument.Location.class,
            SettingsDocument.Document.class,
            SettingsApi.Saved.class,
            AgentWire.Descriptor.class,
            MessagesApi.Texts.class,
            MessagesApi.Saved.class,
            MessagesApi.Fallback.class,
            MessagesApi.Syntax.class,
            DocumentsApi.DocumentList.class,
            DocumentsApi.DocumentDraft.class,
            DocumentsApi.DocumentDrawing.class,
            DocumentsApi.Uploaded.class,
            DocumentsApi.DocumentVersion.class,
            DocumentsApi.SharedPartChange.class,
            MessageCheck.Problem.class,
            TranslationSearch.Hit.class,
            AgentWire.PluginAdded.class,
            Routes.ConsoleSent.class,
            StackApi.NetworkMap.class);

    public static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(Metrics.Curve.class, "Metrics"),
            Map.entry(GameDataRoutes.Icons.class, "GameIcons"),
            Map.entry(eu.nordtal.season.database.mode.ModeStateStore.Mode.class, "StateMode"),
            Map.entry(eu.nordtal.season.database.mode.ModeStateStore.Container.class, "StateContainer"),
            Map.entry(eu.nordtal.season.database.mode.ModeStateStore.Status.class, "StateStatus"),
            Map.entry(CommandTree.Node.class, "CommandNode"),
            Map.entry(eu.nordtal.season.database.game.GameCatalogue.Entry.class, "GameEntry"),
            Map.entry(eu.nordtal.season.database.game.GameCatalogue.Tag.class, "GameTag"),
            Map.entry(AccessGrant.class, "Grant"),
            Map.entry(PaymentRequest.class, "Payment"),
            Map.entry(AlertRoutes.CurrentAlert.class, "Alert"),
            Map.entry(AuditEntry.class, "JournalEntry"),
            Map.entry(JournalPage.Item.class, "JournalItem"),
            Map.entry(eu.nordtal.season.database.journal.JournalSearch.Term.class, "JournalTerm"),
            Map.entry(eu.nordtal.season.database.journal.JournalSearch.Term.Kind.class, "TermKind"),
            Map.entry(Actor.Kind.class, "ActorKind"),
            Map.entry(Updates.Step.class, "RunStep"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Kind.class, "StepKind"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Outcome.class, "StepOutcome"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Reason.class, "StepReason"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Archive.class, "RunArchive"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Change.class, "RunChange"),
            Map.entry(eu.nordtal.season.database.update.RunStep.Image.class, "ChangeImage"),
            Map.entry(eu.nordtal.season.database.update.RunStep.PruneRule.class, "PruneRule"),
            Map.entry(WebAuthn.Held.class, "KeyHeld"),
            Map.entry(AuthenticatorApp.Enrolment.class, "AuthenticatorEnrolment"),
            Map.entry(AuthenticatorApp.Qr.class, "QrCode"),
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
            Map.entry(SettingsApi.Saved.class, "ConfigSaved"),
            Map.entry(SettingsDocument.Entry.class, "ConfigEntry"),
            Map.entry(SettingsDocument.Shape.class, "ConfigShape"),
            Map.entry(SettingsDocument.Choices.class, "ConfigChoices"),
            Map.entry(SettingsDocument.Condition.class, "ConfigCondition"),
            Map.entry(SchemaNode.ProtectedEntry.class, "ConfigProtectedEntry"),
            Map.entry(SettingsDocument.Reference.class, "ConfigReference"),
            Map.entry(Refers.To.class, "ReferenceKind"),
            Map.entry(Reloading.class, "ReloadOutcome"),
            Map.entry(Reloading.Status.class, "ReloadStatus"),
            Map.entry(MessagesApi.Texts.class, "MessageTexts"),
            Map.entry(MessagesApi.Text.class, "MessageText"),
            Map.entry(MessagesApi.Saved.class, "MessageSaveResult"),
            Map.entry(MessagesApi.Fallback.class, "MessageFallback"),
            Map.entry(MessagesApi.FallbackReason.class, "MessageFallbackReason"),
            Map.entry(MessagesApi.PreviewTarget.class, "MessagePreviewTarget"),
            Map.entry(Display.Surface.class, "MessageSurface"),
            Map.entry(MessagesApi.Syntax.class, "MessageSyntax"),
            Map.entry(MessageCheck.Problem.class, "MessageProblem"),
            Map.entry(TranslationSearch.Hit.class, "TranslationHit"),
            Map.entry(TranslationSearch.Kind.class, "TranslationKind"),
            Map.entry(TranslationSearch.Field.class, "TranslationField"),
            Map.entry(Part.class, "DocumentPart"),
            Map.entry(Part.Type.class, "DocumentPartType"),
            Map.entry(Line.class, "DocumentLine"),
            Map.entry(Line.Style.class, "DocumentLineStyle"),
            Map.entry(Item.class, "DocumentItem"),
            Map.entry(Value.class, "DocumentValue"),
            Map.entry(Slot.class, "DocumentSlot"),
            Map.entry(Slot.Type.class, "DocumentSlotType"),
            Map.entry(DocumentSpec.Shape.class, "DocumentShape"),
            Map.entry(DocumentMerge.Adaptation.class, "DocumentAdaptation"),
            Map.entry(DocumentMerge.Change.class, "DocumentChange"),
            Map.entry(DocumentCheck.Problem.class, "DocumentProblem"),
            Map.entry(Declaration.class, "MessageDeclaration"),
            Map.entry(Kind.class, "ValueKind"));

    private StewardWire() {}
}
