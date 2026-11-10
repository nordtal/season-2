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
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Takes in the item an admin's player puts into a window, which Steward makes a wheel prize or a kit's piece of, whole.
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
    // The windows open now, by their request. The main thread writes it, the hub's thread reads it.
    private final Map<Long, Shown> open = new ConcurrentHashMap<>();

    private record Shown(UUID player, TakeInWindow window) {}

    public ItemTakeIn(
            final Plugin plugin,
            final DataSource pool,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Identities identities) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.inbox = Inbox.over(pool, SmpRequest.TABLE);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.identities = Objects.requireNonNull(identities, "identities");
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
            if (here.getOpenInventory().getTopInventory().getHolder() instanceof TakeInWindow current
                    && current.purpose() == purpose
                    && !current.waiting()) {
                // The window open since the last item takes the next one, and keeps showing that item's copy.
                current.await(stack -> answer(request, stack));
                open.put(request, new Shown(here.getUniqueId(), current));
                return true;
            }
            final TakeInWindow window = new TakeInWindow(purpose, document, stack -> answer(request, stack));
            window.open(here);
            if (!window.showsTo(here)) {
                return false;
            }
            open.put(request, new Shown(here.getUniqueId(), window));
            return true;
        });
        return shown ? Answer.open() : Answer.refused(ServerRefusal.NOT_HERE.with());
    }

    /** Runs on the main thread once the window ended, and settles off it. */
    private void answer(final long request, final @Nullable ItemStack stack) {
        open.remove(request);
        final TakeInItem.Answer answer = stack == null
                ? TakeInItem.Answer.none()
                : new TakeInItem.Answer(
                        stack.getType().getKey().asString(),
                        Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        PaperScheduler.of(plugin).execute(() -> {
            if (inbox.settle(request, Outcome.done(answer)).isEmpty()) {
                plugin.getLogger().info("the take-in " + request + " was withdrawn before its window closed");
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
