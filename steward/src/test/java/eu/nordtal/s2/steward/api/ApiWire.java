package eu.nordtal.s2.steward.api;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.messages.text.MessageCheck;
import eu.nordtal.s2.settings.Refers;
import java.util.List;
import java.util.Map;

/** The records the routes of this package answer and read, for the generated TypeScript types. */
public final class ApiWire {

    /** Every record a route here answers or reads at its top level. */
    public static final List<Class<?>> ROOTS = List.of(
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

    /** Records here whose own simple name would say too little or collide. */
    public static final Map<Class<?>, String> NAMES = Map.ofEntries(
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

    private ApiWire() {}
}
