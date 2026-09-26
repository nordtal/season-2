package eu.nordtal.s2.smp.state;

import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the track has handed out so far, held in memory so the main thread can ask without touching the database.
 *
 * Two questions are asked constantly - "is the Nether open?" on every portal ignition and every balloon click, and
 * "how big is Nordtal's border?" on every unlock - and both are answered from rows that change a handful of times a
 * season. Reading them from PostgreSQL at the point of use would be a main-thread query per click, which is the
 * mistake this repository already made once.
 *
 * Refreshed from an async task; read from anywhere. The fields are volatile rather than synchronised because a
 * reader that is one refresh behind sees the previous truth, which for "the Nether opened four milliseconds ago" is
 * not a problem worth a lock.
 */
public final class SeasonState {

    private volatile Set<Unlock> unlocked = Collections.unmodifiableSet(EnumSet.noneOf(Unlock.class));
    private volatile int borderDiameter;
    private volatile List<String> completedKeys = List.of();
    private volatile Active active = Active.UNREAD;

    /**
     * The milestone being worked on and how far its objectives have got, <b>as one value</b>.
     *
     * They were two volatile fields previously, and two fields is two reads: a HUD line that took the name and then
     * the progress could pair one milestone's name with the next one's bar, which is a line that is wrong about the
     * only two things on it. Narrow - the pair changes about eight times in a season - and free to close, because
     * nothing outside this class ever wanted one without the other.
     *
     * @param key the active milestone's key, or null once the track has run out
     * @param objectives its objectives with their progress, never null
     * @param unread whether nothing has been read from the database yet
     */
    public record Active(@Nullable String key, List<ObjectiveRow> objectives, boolean unread) {

        /** No milestone, because the last one is done. */
        public static final Active NONE = new Active(null, List.of(), false);

        /**
         * Not read yet: what the state holds between enable and the first refresh.
         *
         * It looks exactly like {@link #NONE} - no key, no objectives - so a refresh that genuinely finds nothing
         * active is
         * never mistaken for it. A {@code /smp status} typed in the first second after a restart must not announce that
         * every milestone is finished.
         */
        public static final Active UNREAD = new Active(null, List.of(), true);

        public Active {
            objectives = List.copyOf(objectives);
        }

        /**
         * How far this milestone is, as the mean of its objectives.
         *
         * The mean and not the total: objectives have wildly different targets - "3000 stone" beside "8 players earn an
         * advancement" - and summing the raw amounts would make the large one the only one the bar ever moves for. Each
         * objective is worth the same fraction of the milestone, which is also how the pot is split.
         */
        public double progress() {
            return objectives.isEmpty()
                    ? 0.0
                    : objectives.stream()
                            .mapToDouble(ObjectiveRow::ratio)
                            .average()
                            .orElse(0.0);
        }
    }

    /**
     * Recomputes from the completed milestone keys and the track that defines them.
     *
     * The database holds progress and the file holds definition, so this is where the two meet: a completed key the
     * file
     * no longer declares contributes nothing rather than throwing, because by the time a player is standing at a
     * balloon
     * it is far too late to complain about the config - {@code TrackValidation} does that at load, which is when
     * somebody can act on it.
     */
    public void refresh(final List<String> completed, final MilestoneTrack track) {
        final Set<Unlock> found = EnumSet.noneOf(Unlock.class);
        int border = 0;
        for (final String key : completed) {
            final Milestone milestone = track.milestone(key).orElse(null);
            if (milestone == null) {
                continue;
            }
            found.add(milestone.unlock());
            if (milestone.unlock() == Unlock.BORDER) {
                border = Math.max(border, milestone.borderDiameter());
            }
        }
        // Takes the largest border any completed milestone asked for.
        this.unlocked = Collections.unmodifiableSet(found);
        this.borderDiameter = border;
        this.completedKeys = List.copyOf(completed);
    }

    public Set<Unlock> unlocked() {
        return unlocked;
    }

    public boolean isUnlocked(final Unlock unlock) {
        return unlocked.contains(unlock);
    }

    /** The border every completed milestone adds up to, or 0 when none has moved it yet. */
    public int borderDiameter() {
        return borderDiameter;
    }

    public List<String> completedKeys() {
        return completedKeys;
    }

    /**
     * Records which milestone is being worked on and how far each of its objectives has got.
     *
     * Refreshed on a timer from an async task, read by HUD line 1 and by the objective board - both of which redraw far
     * more often than the numbers change.
     */
    public void refreshActive(final @Nullable String key, final List<ObjectiveRow> objectives) {
        this.active = new Active(key, objectives, false);
    }

    /**
     * The active milestone and its progress, in one read.
     *
     * One accessor rather than three, deliberately: three would each take their own volatile read and the pair could
     * still change between them, which is the whole bug this replaced. A caller that wants the name and the bar takes
     * this once and asks the record.
     */
    public Active active() {
        return active;
    }
}
