package eu.nordtal.s2.database.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A server's commands as its Brigadier dispatcher holds them: node 0 is the root, and every node is one index.
 *
 * A node reached twice and a redirect are an index too, so a tree looping back, as {@code execute run} does, is finite.
 * @param nodes every node by its index, the root first
 */
public record CommandTree(List<Node> nodes) {

    public CommandTree {
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a command tree has at least its root");
        }
    }

    /**
     * A word to type or an argument to fill; every component a node does not need is absent rather than false or empty.
     *
     * @param name the word to type, or the argument's name, which a reader shows as {@code <name>}; the root's is empty
     * @param argument true for an argument, absent for a word
     * @param executes true when what was typed up to here is a whole command, absent when it is not
     * @param children the indexes of the nodes that may follow, words first and then arguments, each by name
     * @param redirect the index of the node whose children follow instead of this one's own
     */
    public record Node(
            String name,
            @Nullable Boolean argument,
            @Nullable Boolean executes,
            @Nullable List<Integer> children,
            @Nullable Integer redirect) {}

    /** How to read one platform's Brigadier nodes, which never reach this module, so the walk exists once for both. */
    public interface Shape<N> {

        String name(N node);

        boolean argument(N node);

        boolean executes(N node);

        Collection<? extends N> children(N node);

        @Nullable
        N redirect(N node);
    }

    /** Returns the tree below {@code root}, each node indexed once, breadth first in the order a reader offers. */
    public static <N> CommandTree of(final N root, final Shape<N> shape) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(shape, "shape");
        final Comparator<N> offered = offered(shape);
        final IdentityHashMap<N, Integer> indexes = new IdentityHashMap<>();
        final List<N> order = new ArrayList<>();
        final Deque<N> waiting = new ArrayDeque<>();
        indexes.put(root, 0);
        order.add(root);
        waiting.add(root);
        while (!waiting.isEmpty()) {
            final N node = waiting.poll();
            final List<N> next = new ArrayList<>(shape.children(node));
            final N redirect = shape.redirect(node);
            if (redirect != null) {
                next.add(redirect);
            }
            next.sort(offered);
            for (final N child : next) {
                if (!indexes.containsKey(child)) {
                    indexes.put(child, order.size());
                    order.add(child);
                    waiting.add(child);
                }
            }
        }
        final List<Node> nodes = new ArrayList<>(order.size());
        nodes.add(new Node("", null, null, childrenOf(root, shape, offered, indexes), null));
        for (final N node : order.subList(1, order.size())) {
            final N redirect = shape.redirect(node);
            nodes.add(new Node(
                    shape.name(node),
                    shape.argument(node) ? Boolean.TRUE : null,
                    shape.executes(node) ? Boolean.TRUE : null,
                    childrenOf(node, shape, offered, indexes),
                    redirect == null ? null : indexes.get(redirect)));
        }
        return new CommandTree(nodes);
    }

    private static <N> @Nullable List<Integer> childrenOf(
            final N node,
            final Shape<N> shape,
            final Comparator<N> offered,
            final IdentityHashMap<N, Integer> indexes) {
        final List<Integer> children =
                shape.children(node).stream().sorted(offered).map(indexes::get).toList();
        return children.isEmpty() ? null : children;
    }

    /** Words before arguments, a namespaced word such as {@code minecraft:give} after the plain ones, then by name. */
    private static <N> Comparator<N> offered(final Shape<N> shape) {
        return Comparator.<N, Boolean>comparing(shape::argument)
                .thenComparing(node -> shape.name(node).contains(":"))
                .thenComparing(shape::name);
    }
}
