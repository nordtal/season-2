package eu.nordtal.season.database.npc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What an NPC says, as steps from {@code start}. A step shows one text and then offers up to three answers, hands the
 * player to a menu, or goes to its {@code next} step; without a next step the dialogue ends.
 */
public record Dialogue(String start, List<Step> steps) {

    /** The most answers a step offers. */
    public static final int MOST_ANSWERS = 3;

    public Dialogue {
        Objects.requireNonNull(start, "start");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** Returns the step called {@code id}. */
    public Optional<Step> step(final String id) {
        return steps.stream().filter(step -> step.id().equals(id)).findFirst();
    }

    /** Returns what keeps this dialogue from playing, each as one English sentence; empty when it plays. */
    public List<String> problems(final Set<String> menus) {
        final List<String> problems = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        for (final Step step : steps) {
            if (!ids.add(step.id())) {
                problems.add("Two steps are called " + step.id() + ".");
            }
        }
        if (!ids.contains(start)) {
            problems.add("The dialogue starts at " + start + ", which is no step.");
        }
        for (final Step step : steps) {
            problems.addAll(step.problems(ids, menus));
        }
        return List.copyOf(problems);
    }

    /** One text, and what follows it. */
    public record Step(
            String id,
            int x,
            int y,
            Words text,
            List<Answer> answers,
            @Nullable String next,
            @Nullable String menu) {

        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            answers = answers == null ? List.of() : List.copyOf(answers);
        }

        /** Returns whether the step hands the player to a menu. */
        public boolean handsOff() {
            return menu != null;
        }

        List<String> problems(final Set<String> ids, final Set<String> menus) {
            final List<String> problems = new ArrayList<>();
            if (text.en().isBlank() || text.de().isBlank()) {
                problems.add("Step " + id + " says nothing in one of its languages.");
            }
            if (answers.size() > MOST_ANSWERS) {
                problems.add("Step " + id + " offers " + answers.size() + " answers, at most " + MOST_ANSWERS + ".");
            }
            if (menu != null && (!answers.isEmpty() || next != null)) {
                problems.add("Step " + id + " hands over to a menu, so it has no answers and no next step.");
            }
            if (!answers.isEmpty() && next != null) {
                problems.add("Step " + id + " offers answers, so it has no next step of its own.");
            }
            if (menu != null && !menus.contains(menu)) {
                problems.add("Step " + id + " opens the menu " + menu + ", which no server offers.");
            }
            if (next != null && !ids.contains(next)) {
                problems.add("Step " + id + " goes on to " + next + ", which is no step.");
            }
            for (final Answer answer : answers) {
                if (answer.text().en().isBlank() || answer.text().de().isBlank()) {
                    problems.add("An answer of step " + id + " says nothing in one of its languages.");
                }
                if (answer.next() != null && !ids.contains(answer.next())) {
                    problems.add("An answer of step " + id + " goes on to " + answer.next() + ", which is no step.");
                }
            }
            return problems;
        }
    }

    /** An answer the player picks, and the step it leads to; without one the dialogue ends. */
    public record Answer(Words text, @Nullable String next) {

        public Answer {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A text in English and German. */
    public record Words(String en, String de) {

        public Words {
            en = en == null ? "" : en;
            de = de == null ? "" : de;
        }

        /** Returns the German text for a German {@code locale}, the English one otherwise. */
        public String in(final Locale locale) {
            return locale.getLanguage().equals(Locale.GERMAN.getLanguage()) ? de : en;
        }
    }
}
