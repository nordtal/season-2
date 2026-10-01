package eu.nordtal.s2.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * Conditions on how code is wired, read from the bytecode: what a method calls, and in which order.
 *
 * A target is {@code Owner#member} by the owner's simple name; a call through a subclass or a lambda counts.
 * Order is the order of source lines, which is the order of straight code.
 */
final class Wiring {

    private Wiring() {}

    /** Returns whether an access (a call, a method reference or a field read) reaches {@code owner.member}. */
    static DescribedPredicate<JavaAccess<?>> reaches(final String owner, final String member) {
        return DescribedPredicate.describe(
                owner + "." + member,
                access -> access.getTarget().getName().equals(member)
                        && access.getTargetOwner().isAssignableTo(owner));
    }

    /** Returns whether a class is one of {@code names} or nested in one, lambdas and anonymous classes included. */
    static DescribedPredicate<JavaClass> isOrIsNestedIn(final String... names) {
        return DescribedPredicate.describe(
                "one of " + String.join(", ", names),
                type -> java.util.Arrays.stream(names)
                        .anyMatch(name ->
                                type.getName().equals(name) || type.getName().startsWith(name + "$")));
    }

    /** Returns whether a class is one of {@code names}, failing when a listed class is not there at all. */
    static DescribedPredicate<JavaClass> isListed(final String... names) {
        for (final String name : names) {
            Codebase.classes().get(name);
        }
        return DescribedPredicate.describe(
                "one of " + String.join(", ", names),
                type -> java.util.Arrays.asList(names).contains(type.getName()));
    }

    /** Returns that the class's method {@code method} reaches every target, in any order. */
    static ArchCondition<JavaClass> callFrom(final String method, final String... targets) {
        return condition("call " + List.of(targets) + " from " + method, method, targets, false);
    }

    /** Returns that the class's method {@code method} reaches the targets, each first reached after the one before. */
    static ArchCondition<JavaClass> callInOrder(final String method, final String... targets) {
        return condition("call " + List.of(targets) + " in this order from " + method, method, targets, true);
    }

    /** Returns that some single source line of the class's method {@code method} reaches every target. */
    static ArchCondition<JavaClass> callOnOneLine(final String method, final String... targets) {
        return new ArchCondition<>("call " + List.of(targets) + " on one line of " + method) {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                final List<JavaAccess<?>> accesses = units(type, method, events).stream()
                        .flatMap(unit -> unit.getAccessesFromSelf().stream())
                        .toList();
                final boolean together = accesses.stream()
                        .map(JavaAccess::getLineNumber)
                        .anyMatch(line -> java.util.Arrays.stream(targets)
                                .allMatch(target -> accesses.stream()
                                        .anyMatch(access -> access.getLineNumber() == line
                                                && named(target).test(access))));
                if (!together) {
                    events.add(SimpleConditionEvent.violated(
                            type,
                            type.getName() + "." + method + " never reaches " + List.of(targets) + " on one line"));
                }
            }
        };
    }

    /** Returns that the class's method {@code method} reaches none of the targets. */
    static ArchCondition<JavaClass> neverCallFrom(final String method, final String... targets) {
        return new ArchCondition<>("never call " + List.of(targets) + " from " + method) {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                final List<JavaCodeUnit> units = units(type, method, events);
                for (final String target : targets) {
                    final OptionalInt line = firstLine(units, target);
                    if (line.isPresent()) {
                        events.add(SimpleConditionEvent.violated(
                                type,
                                type.getName() + "." + method + " reaches " + target + " on line " + line.getAsInt()));
                    }
                }
            }
        };
    }

    /** Returns that the class's method {@code method} reaches the target from exactly one source line. */
    static ArchCondition<JavaClass> callOnceFrom(final String method, final String target) {
        return new ArchCondition<>("call " + target + " from one line of " + method) {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                final long lines = units(type, method, events).stream()
                        .flatMap(unit -> unit.getAccessesFromSelf().stream())
                        .filter(named(target))
                        .mapToInt(JavaAccess::getLineNumber)
                        .distinct()
                        .count();
                if (lines != 1) {
                    events.add(SimpleConditionEvent.violated(
                            type, type.getName() + "." + method + " reaches " + target + " from " + lines + " lines"));
                }
            }
        };
    }

    /** Returns that no code unit of the class or of a class nested in it reaches the target, except {@code method}. */
    static ArchCondition<JavaClass> callOnlyFrom(final String method, final String target) {
        return new ArchCondition<>("call " + target + " from " + method + " alone") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                units(type, method, events);
                for (final JavaClass each : withNested(type)) {
                    for (final JavaCodeUnit unit : each.getCodeUnits()) {
                        if (each.equals(type) && unit.getName().equals(method)) {
                            continue;
                        }
                        final OptionalInt line = firstLine(List.of(unit), target);
                        if (line.isPresent()) {
                            events.add(SimpleConditionEvent.violated(
                                    type,
                                    each.getName() + "." + unit.getName() + " reaches " + target + " on line "
                                            + line.getAsInt()));
                        }
                    }
                }
            }
        };
    }

    /** Returns that every access of the class matching {@code first} has one matching {@code second} on its line. */
    static ArchCondition<JavaClass> alwaysOnOneLine(
            final DescribedPredicate<JavaAccess<?>> first, final DescribedPredicate<JavaAccess<?>> second) {
        return new ArchCondition<>(
                "reach " + first.getDescription() + " only on a line that reaches " + second.getDescription()) {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                for (final JavaCodeUnit unit : type.getCodeUnits()) {
                    for (final JavaAccess<?> one : unit.getAccessesFromSelf()) {
                        if (first.test(one)
                                && unit.getAccessesFromSelf().stream()
                                        .noneMatch(other ->
                                                second.test(other) && other.getLineNumber() == one.getLineNumber())) {
                            events.add(SimpleConditionEvent.violated(
                                    type, one.getDescription() + " without " + second.getDescription()));
                        }
                    }
                }
            }
        };
    }

    /** Returns that no code unit of the class reaches both kinds of target on one source line. */
    static ArchCondition<JavaClass> neverOnOneLine(
            final DescribedPredicate<JavaAccess<?>> first, final DescribedPredicate<JavaAccess<?>> second) {
        return new ArchCondition<>(
                "never reach " + first.getDescription() + " and " + second.getDescription() + " on one line") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                for (final JavaCodeUnit unit : type.getCodeUnits()) {
                    for (final JavaAccess<?> one : unit.getAccessesFromSelf()) {
                        if (!first.test(one)) {
                            continue;
                        }
                        for (final JavaAccess<?> other : unit.getAccessesFromSelf()) {
                            if (second.test(other) && other.getLineNumber() == one.getLineNumber()) {
                                events.add(SimpleConditionEvent.violated(
                                        type,
                                        one.getDescription() + " beside "
                                                + other.getTarget().getFullName()));
                            }
                        }
                    }
                }
            }
        };
    }

    /** Returns that the class or one of its nested classes reaches a target matching {@code target}. */
    static ArchCondition<JavaClass> reachInside(final DescribedPredicate<JavaAccess<?>> target) {
        return new ArchCondition<>("reach " + target.getDescription() + " in the class or a nested one") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                final boolean reached = withNested(type).stream()
                        .flatMap(each -> each.getAccessesFromSelf().stream())
                        .anyMatch(target);
                if (!reached) {
                    events.add(SimpleConditionEvent.violated(
                            type, type.getName() + " never reaches " + target.getDescription()));
                }
            }
        };
    }

    /** Returns the class with every class nested in it, at any depth. */
    static List<JavaClass> withNested(final JavaClass type) {
        final List<JavaClass> all = new ArrayList<>(List.of(type));
        for (int at = 0; at < all.size(); at++) {
            all.addAll(nestedIn(all.get(at)));
        }
        return all;
    }

    private static List<JavaClass> nestedIn(final JavaClass type) {
        return type.getPackage().getClasses().stream()
                .filter(candidate -> candidate
                        .getEnclosingClass()
                        .map(enclosing -> enclosing.equals(type))
                        .orElse(false))
                .toList();
    }

    private static ArchCondition<JavaClass> condition(
            final String description, final String method, final String[] targets, final boolean ordered) {
        return new ArchCondition<>(description) {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                final List<JavaCodeUnit> units = units(type, method, events);
                int previous = Integer.MIN_VALUE;
                String before = "";
                for (final String target : targets) {
                    final OptionalInt line = firstLine(units, target);
                    if (line.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(
                                type, type.getName() + "." + method + " never reaches " + target));
                        return;
                    }
                    if (ordered && line.getAsInt() <= previous) {
                        events.add(SimpleConditionEvent.violated(
                                type,
                                type.getName() + "." + method + " reaches " + target + " on line " + line.getAsInt()
                                        + ", not after " + before + " on line " + previous));
                        return;
                    }
                    previous = line.getAsInt();
                    before = target;
                }
            }
        };
    }

    private static List<JavaCodeUnit> units(final JavaClass type, final String method, final ConditionEvents events) {
        final List<JavaCodeUnit> units = type.getCodeUnits().stream()
                .filter(unit -> unit.getName().equals(method))
                .map(JavaCodeUnit.class::cast)
                .toList();
        if (units.isEmpty()) {
            events.add(SimpleConditionEvent.violated(type, type.getName() + " has no " + method + " any more"));
        }
        return units;
    }

    /** Returns the first source line in {@code units} reaching {@code target}, given as {@code Owner#member}. */
    private static OptionalInt firstLine(final List<JavaCodeUnit> units, final String target) {
        final Predicate<JavaAccess<?>> matches = named(target);
        return units.stream()
                .flatMap(unit -> unit.getAccessesFromSelf().stream())
                .filter(matches)
                .mapToInt(JavaAccess::getLineNumber)
                .min();
    }

    private static Predicate<JavaAccess<?>> named(final String target) {
        final int hash = target.indexOf('#');
        if (hash <= 0) {
            throw new IllegalArgumentException(target + " is not Owner#member");
        }
        final String owner = target.substring(0, hash);
        final String member = target.substring(hash + 1);
        return access -> access.getTarget().getName().equals(member)
                && (access.getTargetOwner().getSimpleName().equals(owner)
                        || access.getTarget()
                                .resolveMember()
                                .map(JavaMember::getOwner)
                                .map(JavaClass::getSimpleName)
                                .filter(owner::equals)
                                .isPresent()
                        || Objects.equals(access.getTargetOwner().getName(), owner));
    }
}
