package eu.nordtal.season.database.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A server's commands as a flat list of nodes, the root at index 0. */
public record CommandTree(List<Node> nodes) {

    public CommandTree {
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a command tree has at least its root");
        }
    }

    /**
     * A word to type or an argument to fill. A component a node does not need is null.
     *
     * @param name the word, or the argument's name. Empty for the root
     * @param executes true when the input up to this node is a whole command
     * @param children the indexes of the following nodes, words first, then arguments, each by name
     * @param redirect the index of the node whose children follow instead
     */
    public record Node(
            String name,
            @Nullable Boolean argument,
            @Nullable Boolean executes,
            @Nullable List<Integer> children,
            @Nullable Integer redirect) {}

    /** Reads the Brigadier nodes of one platform, so {@link #of} is the only walk. */
    public interface Shape<N> {

        String name(N node);

        boolean argument(N node);

        boolean executes(N node);

        Collection<? extends N> children(N node);

        @Nullable
        N redirect(N node);
    }

    /** Returns the tree below {@code root}, breadth first, each node indexed once. */
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

    /** Orders words before arguments, plain names before namespaced ones, then by name. */
    private static <N> Comparator<N> offered(final Shape<N> shape) {
        return Comparator.<N, Boolean>comparing(shape::argument)
                .thenComparing(node -> shape.name(node).contains(":"))
                .thenComparing(shape::name);
    }
}
