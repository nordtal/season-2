package eu.nordtal.season.smp.takein;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.inbox.SmpRequest.TakeInItem;
import eu.nordtal.season.database.inbox.TakeInPurpose;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.papercommon.chat.Previews;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.capture.CaptureMode;
import eu.nordtal.season.smp.capture.CaptureSessions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Takes in the item an admin's player puts into a window, whole, for a wheel prize, a kit's piece or NPC equipment.
 * The request stays running while the window is open and is settled with the item, or empty when it closed so.
 * The window stays open after an item, so the next request of its purpose takes the next item in it.
 */
public final class ItemTakeIn {

    private static final long WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final Inbox<SmpRequest> inbox;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Identities identities;
    private final CaptureSessions sessions;
    // The windows open now, by their request. The main thread writes it, the hub's thread reads it.
    private final Map<Long, Shown> open = new ConcurrentHashMap<>();

    // The window each player was shown last, which takes the next item of its purpose while it stays open. Main thread.
    private final Map<UUID, TakeInWindow> last = new HashMap<>();

    private record Shown(UUID player, TakeInWindow window) {}

    /** Hands the draft preview each take-in window, whose answer goes nowhere, so a put-in item only shows its copy. */
    public static void examples(final BiConsumer<DocumentSpec<?>, Previews.Example> example) {
        for (final TakeInPurpose purpose : TakeInPurpose.values()) {
            example.accept(
                    TakeInWindow.document(purpose),
                    (reader, drafted) -> new TakeInWindow(purpose, drafted, stack -> {}).open(reader));
        }
    }

    public ItemTakeIn(
            final Plugin plugin,
            final DataSource pool,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Identities identities,
            final CaptureSessions sessions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Inbox.over(pool, SmpRequest.TABLE);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** Closes a window once Steward withdrew its request. Call it before the hub starts. */
    public void watch(final SignalHub hub) {
        hub.on(SmpRequest.TABLE.channel(), "the item take-ins", this::closeWithdrawn);
    }

    /**
     * Opens the window for {@code player} on the main thread and leaves {@code request} running while it shows.
     * The window names {@code purpose}. It runs on the hub's thread and is refused while the player is not here.
     */
    public Answer open(final long request, final PlayerId player, final TakeInPurpose purpose) {
        if (!sessions.tryBegin(player.value(), CaptureMode.ITEM_TAKE_IN)) {
            return Answer.refused(ServerRefusal.CAPTURE_ACTIVE.with());
        }
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(TakeInWindow.document(purpose)),
                Viewer.of(identities.languageOf(player.value())),
                new DocumentSpec.None());
        final boolean shown = onMain(() -> {
            final Player here = plugin.getServer().getPlayer(player.value());
            if (here == null) {
                return false;
            }
            final TakeInWindow current = last.get(here.getUniqueId());
            if (current != null && current.showsTo(here) && current.purpose() == purpose && !current.waiting()) {
                // The window open since the last item takes the next one, and keeps showing that item's copy.
                current.await(stack -> answer(request, player.value(), stack));
                open.put(request, new Shown(here.getUniqueId(), current));
                return true;
            }
            final TakeInWindow window =
                    new TakeInWindow(purpose, document, stack -> answer(request, player.value(), stack));
            window.open(here);
            if (!window.showsTo(here)) {
                return false;
            }
            open.put(request, new Shown(here.getUniqueId(), window));
            last.put(here.getUniqueId(), window);
            return true;
        });
        if (!shown) {
            sessions.end(player.value(), CaptureMode.ITEM_TAKE_IN);
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        return Answer.open();
    }

    /** Runs on the main thread once the window ended, and settles off it. */
    private void answer(final long request, final UUID player, final @Nullable ItemStack stack) {
        open.remove(request);
        sessions.end(player, CaptureMode.ITEM_TAKE_IN);
        final TakeInItem.Answer answer = stack == null
                ? TakeInItem.Answer.none()
                : new TakeInItem.Answer(
                        stack.getType().getKey().asString(),
                        Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        PaperScheduler.of(plugin).execute(() -> {
            if (inbox.settle(request, Outcome.done(answer)).isEmpty()) {
                plugin.getLogger()
                        .info("item capture request no longer running, result not recorded: request=" + request);
            }
        });
    }

    private void closeWithdrawn() {
        for (final Map.Entry<Long, Shown> entry : open.entrySet()) {
            final boolean running = inbox.find(entry.getKey())
                    .filter(row -> row.status() == InboxStatus.RUNNING)
                    .isPresent();
            if (!running) {
                PaperScheduler.of(plugin).onMain(() -> {
                    if (open.remove(entry.getKey(), entry.getValue())) {
                        sessions.end(entry.getValue().player(), CaptureMode.ITEM_TAKE_IN);
                        entry.getValue()
                                .window()
                                .withdraw(plugin.getServer()
                                        .getPlayer(entry.getValue().player()));
                    }
                });
            }
        }
    }

    /**
     * Runs {@code work} on the main thread and waits for it.
     * The work and a wait that runs out claim one flag, so work that starts late does nothing, and a wait for work
     * already started lasts until it ends.
     */
    private boolean onMain(final Supplier<Boolean> work) {
        final AtomicBoolean claimed = new AtomicBoolean();
        final CompletableFuture<Boolean> done = CompletableFuture.supplyAsync(
                () -> claimed.compareAndSet(false, true) && work.get(),
                PaperScheduler.of(plugin).mainThread());
        try {
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException late) {
            if (claimed.compareAndSet(false, true)) {
                throw new IllegalStateException(late);
            }
            return done.join();
        } catch (final ExecutionException failed) {
            throw new IllegalStateException(failed);
        } catch (final InterruptedException interrupted) {
            claimed.set(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
