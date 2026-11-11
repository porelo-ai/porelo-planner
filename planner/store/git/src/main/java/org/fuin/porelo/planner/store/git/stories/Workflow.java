package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** The states in board order and the phases used to display their progress. */
public final class Workflow {
    /** A named display group, in the file's phase order. */
    public record Phase(String name, List<String> states) {
        /** Keeps the group immutable. */
        public Phase {
            states = List.copyOf(states);
        }
    }

    private record State(boolean closed, boolean measured) {}

    private final String file;
    private final Map<String, State> definitions;
    private final List<String> states;
    private final List<Phase> phases;

    private Workflow(String file, Map<String, State> definitions, List<Phase> phases) {
        this.file = file;
        this.definitions = Map.copyOf(definitions);
        this.states = List.copyOf(definitions.keySet());
        this.phases = List.copyOf(phases);
    }

    /**
     * Reads one strict YAML document; malformed input names its file and line.
     *
     * @param file the workflow file
     * @return the immutable workflow
     * @throws IOException when the file is missing, unreadable or invalid
     */
    public static Workflow read(Path file) throws IOException {
        return parse(file.toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Reads the text of a workflow file, one strict YAML document, as {@link #read(Path)} reads the
     * file.
     *
     * @param text the text of {@code workflow.yml}
     * @return the immutable workflow
     * @throws IllegalArgumentException when the text is invalid, in the words {@link #read(Path)}
     *     gives with the file named {@code workflow.yml}
     */
    public static Workflow read(String text) {
        try {
            return parse("workflow.yml", text);
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private static Workflow parse(String file, String text) throws IOException {
        try {
            final Node document = new Yaml().compose(new StringReader(text));
            if (document == null) throw new IOException(file + ":1: expected a workflow mapping");
            final Map<String, Node> root = mapping(file, document, Set.of("states", "phases"));
            final Map<String, State> definitions = new LinkedHashMap<>();
            for (final Node node : sequence(file, required(file, document, root, "states"))) {
                final Map<String, Node> state =
                        mapping(file, node, Set.of("name", "closed", "measured"));
                final String name = name(file, required(file, node, state, "name"));
                final boolean closed = bool(file, required(file, node, state, "closed"));
                final boolean measured =
                        state.containsKey("measured") && bool(file, state.get("measured"));
                if (definitions.putIfAbsent(name, new State(closed, measured)) != null)
                    throw fault(file, node, "duplicate state " + name);
            }
            if (definitions.isEmpty())
                throw fault(
                        file, required(file, document, root, "states"), "states must not be empty");
            final List<Phase> phases = new ArrayList<>();
            final Set<String> names = new HashSet<>();
            final Set<String> grouped = new HashSet<>();
            for (final Node node : sequence(file, required(file, document, root, "phases"))) {
                final Map<String, Node> phase = mapping(file, node, Set.of("name", "states"));
                final String name = name(file, required(file, node, phase, "name"));
                if (!names.add(name)) throw fault(file, node, "duplicate phase " + name);
                final List<String> members = new ArrayList<>();
                for (final Node member : sequence(file, required(file, node, phase, "states"))) {
                    final String state = name(file, member);
                    if (!definitions.containsKey(state))
                        throw fault(file, member, "unknown state " + state);
                    if (!grouped.add(state))
                        throw fault(
                                file, member, "state in more than one phase or repeated: " + state);
                    members.add(state);
                }
                phases.add(new Phase(name, members));
            }
            return new Workflow(file, definitions, phases);
        } catch (MarkedYAMLException ex) {
            throw new IOException(
                    file + ":" + (ex.getProblemMark().getLine() + 1) + ": " + ex.getProblem(), ex);
        } catch (org.yaml.snakeyaml.error.YAMLException ex) {
            throw new IOException(file + ":1: invalid YAML: " + ex.getMessage(), ex);
        }
    }

    /**
     * @return state names in board order
     */
    public List<String> states() {
        return states;
    }

    /**
     * @param state a known state
     * @return whether it is closed
     */
    public boolean closed(String state) {
        return state(state).closed();
    }

    /**
     * Rejected is the one closed state that is not done work: it needs no tested date and
     * contributes no completed-work sample to stage statistics.
     *
     * @param state a known state
     * @return whether the work was rejected
     */
    public boolean rejected(String state) {
        state(state);
        return state.equals("Rejected");
    }

    /**
     * @param state a known state
     * @return whether moving into it needs a stage record
     */
    public boolean measured(String state) {
        return state(state).measured();
    }

    /**
     * @return phase groups in display order
     */
    public List<Phase> phases() {
        return phases;
    }

    /**
     * @param state a known state
     * @return its zero-based board position
     */
    public int order(String state) {
        state(state);
        return states.indexOf(state);
    }

    /**
     * The state a story's issues give it, the rule the stories index applies to a story whose head
     * leaves its state to its issues and checks a stated one against: the stated state when it has
     * no issues; otherwise the earliest open issue state in board order; otherwise, every issue
     * being closed, the first issue's state when all are rejected, else the workflow's first closed
     * state that is not rejected, its first completed state.
     *
     * @param stated the state the story's head states, or null when it states none; returned as
     *     given when there are no issues, and otherwise not read
     * @param issueStates the states of the story's issues, in the order its head names them
     * @return the story's state, null only when there are no issues and none is stated
     * @throws NullPointerException when {@code issueStates} or one of its states is null
     * @throws IllegalArgumentException when an issue state is unknown to the workflow; the index's
     *     guard "workflow.yml has no completed state" is kept, though a workflow this class reads
     *     always has the closed, not rejected state an issue then stands in
     */
    public @Nullable String storyState(@Nullable String stated, List<String> issueStates) {
        if (issueStates.isEmpty()) return stated;
        return issueStates.stream()
                .filter(state -> !closed(state))
                .min(Comparator.comparingInt(this::order))
                .orElseGet(
                        () ->
                                issueStates.stream().allMatch(this::rejected)
                                        ? issueStates.getFirst()
                                        : states.stream()
                                                .filter(state -> closed(state) && !rejected(state))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalArgumentException(
                                                                        "workflow.yml has no completed state")));
    }

    private State state(String name) {
        final State state = definitions.get(name);
        if (state == null) throw new IllegalArgumentException(file + ": unknown state: " + name);
        return state;
    }

    private static IOException fault(String file, Node node, String message) {
        return new IOException(file + ":" + (node.getStartMark().getLine() + 1) + ": " + message);
    }

    private static Map<String, Node> mapping(String file, Node node, Set<String> allowed)
            throws IOException {
        if (!(node instanceof MappingNode mapping) || !node.getTag().equals(Tag.MAP))
            throw fault(file, node, "expected a mapping");
        final Map<String, Node> result = new LinkedHashMap<>();
        for (final var tuple : mapping.getValue()) {
            final String key = name(file, tuple.getKeyNode());
            if (!allowed.contains(key)) throw fault(file, tuple.getKeyNode(), "unknown key " + key);
            if (result.putIfAbsent(key, tuple.getValueNode()) != null)
                throw fault(file, tuple.getKeyNode(), "duplicate key " + key);
        }
        return result;
    }

    private static Node required(String file, Node node, Map<String, Node> values, String key)
            throws IOException {
        final Node value = values.get(key);
        if (value == null) throw fault(file, node, "missing " + key);
        return value;
    }

    private static List<Node> sequence(String file, Node node) throws IOException {
        if (!(node instanceof SequenceNode list) || !node.getTag().equals(Tag.SEQ))
            throw fault(file, node, "expected a list");
        return list.getValue();
    }

    private static String name(String file, Node node) throws IOException {
        if (!(node instanceof ScalarNode scalar)
                || !node.getTag().equals(Tag.STR)
                || scalar.getValue().isBlank()
                || !scalar.getValue().equals(scalar.getValue().strip())
                || scalar.getValue()
                        .codePoints()
                        .anyMatch(
                                c ->
                                        c == '\n'
                                                || c == '\r'
                                                || c == 0x85
                                                || c == 0x2028
                                                || c == 0x2029)
                || node.getEndMark().getLine() != node.getStartMark().getLine())
            throw fault(
                    file, node, "name must be one nonempty line without surrounding whitespace");
        return scalar.getValue();
    }

    private static boolean bool(String file, Node node) throws IOException {
        if (!(node instanceof ScalarNode scalar)
                || !node.getTag().equals(Tag.BOOL)
                || !(scalar.getValue().equals("true") || scalar.getValue().equals("false")))
            throw fault(file, node, "expected true or false");
        return scalar.getValue().equals("true");
    }
}
