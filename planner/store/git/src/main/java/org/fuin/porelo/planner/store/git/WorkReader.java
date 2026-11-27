package org.fuin.porelo.planner.store.git;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * Reads the project's work rules from {@code stories/work.yml}: the roles of its tasks under {@code
 * roles} and the rule per working state under {@code work}, each rule naming the role it offers. A
 * fault never throws, and a role Porelo would refuse at its {@code Start} never loads.
 */
public final class WorkReader {

    /** The file the rules stand in, in the project's own repository. */
    public static final String FILE = "stories/work.yml";

    /** Deepest nesting a sound file needs is three levels; anything far deeper is refused. */
    private static final int NESTING = 16;

    private static final ObjectMapper YAML =
            new ObjectMapper(
                    YAMLFactory.builder()
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(NESTING)
                                            .build())
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build());

    private static final Set<String> KEYS =
            Set.of(
                    "from", "done", "then", "blocked", "per", "modes", "order", "models", "risks",
                    "result", "role");

    private static final Set<String> RISK_KEYS = Set.of("models", "effort");

    private static final Set<String> ROLE_KEYS =
            Set.of("end", "workspace", "writeAs", "rights", "readLanded", "projectServices");

    /** The name the person at the machine goes by, so no task's role may take it. */
    private static final String OPERATOR = "operator";

    /** Longest part of a parser's message a fault quotes. */
    private static final int MESSAGE = 200;

    private WorkReader() {}

    /** A field that cannot be read; it drops its rule. */
    private static final class Fault extends Exception {

        private static final long serialVersionUID = 1L;

        Fault(final String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Reads the roles and the rules.
     *
     * @param text the file's text, or null when the commit has none
     * @return the roles and rules that could be read, and in words why each other one could not
     */
    public static Work read(final @Nullable String text) {
        if (text == null) {
            return new Work(Map.of(), List.of(FILE + " is missing, so no work can be started"));
        }
        final JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (final JacksonException e) {
            return new Work(Map.of(), List.of(FILE + ": not YAML: " + firstLine(e)));
        }
        final JsonNode work = root == null ? null : root.get("work");
        if (work == null || !work.isObject()) {
            return new Work(Map.of(), List.of(FILE + ": no mapping 'work'"));
        }
        final List<String> faults = new ArrayList<>();
        final Map<String, Role> roles = new LinkedHashMap<>();
        final Set<String> refused = new HashSet<>();
        roles(root.get("roles"), roles, refused, faults);
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        work.fields()
                .forEachRemaining(
                        entry -> {
                            try {
                                rules.put(entry.getKey(), rule(entry.getValue(), roles, refused));
                            } catch (final Fault | IllegalArgumentException e) {
                                faults.add(FILE + ": " + entry.getKey() + ": " + e.getMessage());
                            }
                        });
        return new Work(rules, faults, Map.of(), roles);
    }

    /**
     * Reads every role into {@code roles}, and the name of each one that cannot be read into {@code
     * refused} with its fault, so a rule offering it says why it is dropped.
     */
    private static void roles(
            final @Nullable JsonNode node,
            final Map<String, Role> roles,
            final Set<String> refused,
            final List<String> faults) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            faults.add(FILE + ": roles is no mapping of name to role");
            return;
        }
        node.fields()
                .forEachRemaining(
                        entry -> {
                            try {
                                roles.put(entry.getKey(), role(entry.getKey(), entry.getValue()));
                            } catch (final Fault | IllegalArgumentException e) {
                                refused.add(entry.getKey());
                                faults.add(
                                        FILE
                                                + ": role "
                                                + shown(entry.getKey())
                                                + ": "
                                                + e.getMessage());
                            }
                        });
    }

    /** A role's name as a fault shows it: the name itself only where it is a sound one. */
    private static String shown(final String name) {
        return WorkRule.sound(name) && name.length() <= Role.LONGEST ? name : "(an unsound name)";
    }

    private static Role role(final String name, final JsonNode node) throws Fault {
        if (OPERATOR.equalsIgnoreCase(name)) {
            throw new Fault("the name " + OPERATOR + " is taken by the person at the machine");
        }
        if (!node.isObject()) {
            throw new Fault("is no mapping");
        }
        known(node, ROLE_KEYS);
        final Role.End end = end(text(node, "end"));
        final Role.Workspace workspace = workspace(text(node, "workspace"));
        final Role role =
                new Role(
                        name,
                        end,
                        workspace,
                        flag(node, "readLanded", false),
                        // Another task's work is read without the services, which would lead out.
                        flag(node, "projectServices", workspace != Role.Workspace.FOREIGN),
                        writeAs(optional(node, "writeAs")),
                        rights(node));
        final List<Role.Broken> broken = role.broken();
        if (!broken.isEmpty()) {
            throw new Fault(
                    "breaks "
                            + broken.stream()
                                    .map(rule -> rule.word() + " (" + words(rule) + ")")
                                    .collect(Collectors.joining(", ")));
        }
        return role;
    }

    /** A rule broken in plain words, beside its word, for the person who wrote the role. */
    private static String words(final Role.Broken broken) {
        return switch (broken) {
            case FOREIGN_WORK_LEADS_OUT ->
                    "another task's work with the project's services or push";
            case FOREIGN_WORK_NAMES_NO_RIGHTS -> "another task's work with no right at all";
            case REJECT_WITH_PUSH -> "reject together with push";
            case REJECT_OVER_OWN_WORK -> "reject without another task's work";
            case PUSH_WITHOUT_OWN_WORK -> "push outside own work";
            case END_NEEDS_PUSH -> "an end that pushes work without push";
            case PUSH_NEEDS_END -> "push with an end that pushes no work";
            case POINTS_NEED_ANOTHERS_WORK ->
                    "an end that gives points without another task's work";
            case REJECT_NEEDS_POINTS -> "reject with an end that gives no points";
            case FINDINGS_NEED_THREADS -> "findings at another task's work without openThreads";
            case FOREIGN_WORK_NEEDS_READING ->
                    "another task's work with an end that neither reports findings nor gives"
                            + " points";
            case FINDINGS_NEED_LANDED_WORK -> "findings in its answer without readLanded";
            case READ_LANDED_NEEDS_WORKSPACE -> "readLanded without a workspace";
            case POINTS_NEED_THREADS -> "points without openThreads and without reject";
            case END_NEEDS_REPORT ->
                    "an end that reports findings or hands back a result without report";
        };
    }

    private static Role.End end(final String end) throws Fault {
        return switch (end) {
            case "push" -> Role.End.PUSHES_WORK;
            case "findings" -> Role.End.REPORTS_FINDINGS;
            case "points" -> Role.End.GIVES_POINTS;
            case "result" -> Role.End.HANDS_BACK_RESULT;
            default ->
                    throw new Fault(
                            "end '" + end + "' is none of push, findings, points and result");
        };
    }

    private static Role.Workspace workspace(final String word) throws Fault {
        return Role.Workspace.of(word)
                .orElseThrow(
                        () ->
                                new Fault(
                                        "workspace '"
                                                + word
                                                + "' is none of "
                                                + oneOf(
                                                        Arrays.stream(Role.Workspace.values())
                                                                .map(Role.Workspace::word)
                                                                .toList())));
    }

    private static Role.WriteAs writeAs(final @Nullable String word) throws Fault {
        if (word == null) {
            return Role.WriteAs.AUTHOR;
        }
        return Role.WriteAs.of(word)
                .orElseThrow(
                        () ->
                                new Fault(
                                        "writeAs '"
                                                + word
                                                + "' is neither "
                                                + Role.WriteAs.AUTHOR.word()
                                                + " nor "
                                                + Role.WriteAs.REVIEW.word()));
    }

    private static List<Role.Right> rights(final JsonNode node) throws Fault {
        final List<Role.Right> rights = new ArrayList<>();
        for (final String word : strings(node, "rights", "rights")) {
            rights.add(
                    Role.Right.of(word)
                            .orElseThrow(
                                    () ->
                                            new Fault(
                                                    "right '"
                                                            + word
                                                            + "' is none of "
                                                            + oneOf(
                                                                    Arrays.stream(
                                                                                    Role.Right
                                                                                            .values())
                                                                            .map(Role.Right::word)
                                                                            .toList()))));
        }
        return rights;
    }

    /** Porelo's words in its order, the last joined by "and", for a fault that lists them. */
    private static String oneOf(final List<String> words) {
        return String.join(", ", words.subList(0, words.size() - 1))
                + " and "
                + words.get(words.size() - 1);
    }

    private static boolean flag(final JsonNode node, final String key, final boolean absent)
            throws Fault {
        final JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return absent;
        }
        if (!value.isBoolean()) {
            throw new Fault(key + " is neither true nor false");
        }
        return value.booleanValue();
    }

    private static WorkRule rule(
            final JsonNode node, final Map<String, Role> roles, final Set<String> refused)
            throws Fault {
        if (!node.isObject()) {
            throw new Fault("is no mapping");
        }
        known(node, KEYS);
        return new WorkRule(
                strings(node, "from", "states"),
                text(node, "done"),
                optional(node, "then"),
                optional(node, "blocked"),
                per(text(node, "per")),
                strings(node, "modes", "modes"),
                text(node, "order"),
                models(node),
                risks(node),
                result(optional(node, "result")),
                offered(text(node, "role"), roles, refused));
    }

    /** The role a rule offers, which the roles must define and which must have loaded. */
    private static Role offered(
            final String name, final Map<String, Role> roles, final Set<String> refused)
            throws Fault {
        final Role role = roles.get(name);
        if (role != null) {
            return role;
        }
        throw new Fault(
                refused.contains(name)
                        ? "role '" + name + "' did not load"
                        : "role '" + name + "' is not defined under roles");
    }

    private static void known(final JsonNode node, final Set<String> keys) throws Fault {
        for (final var names = node.fieldNames(); names.hasNext(); ) {
            final String name = names.next();
            if (!keys.contains(name)) {
                throw new Fault("unknown key '" + name + "'");
            }
        }
    }

    /** A rule's risks, each read whole or failing with the risk's name. */
    private static Map<Risk, WorkRule.ForRisk> risks(final JsonNode node) throws Fault {
        final JsonNode value = node.get("risks");
        if (value == null) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new Fault("risks is no mapping of risk to models and effort");
        }
        final Map<Risk, WorkRule.ForRisk> result = new LinkedHashMap<>();
        for (final var entries = value.fields(); entries.hasNext(); ) {
            final Map.Entry<String, JsonNode> entry = entries.next();
            final Risk risk;
            try {
                risk = Risk.of(entry.getKey());
            } catch (final IllegalArgumentException e) {
                throw new Fault("risks: " + e.getMessage());
            }
            result.put(risk, forRisk(entry.getValue(), "risks: " + entry.getKey() + ": "));
        }
        return result;
    }

    private static WorkRule.ForRisk forRisk(final JsonNode node, final String at) throws Fault {
        try {
            if (!node.isObject()) {
                throw new Fault("is no mapping of models and effort");
            }
            known(node, RISK_KEYS);
            return new WorkRule.ForRisk(
                    node.has("models") ? models(node) : Map.of(), optional(node, "effort"));
        } catch (final Fault e) {
            throw new Fault(at + e.getMessage());
        } catch (final IllegalArgumentException e) {
            throw new Fault(at + e.getMessage());
        }
    }

    private static WorkRule.@Nullable Result result(final @Nullable String result) throws Fault {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case "ANALYSIS" -> WorkRule.Result.ANALYSIS;
            case "ESTIMATE" -> WorkRule.Result.ESTIMATE;
            case "READINESS" -> WorkRule.Result.READINESS;
            default ->
                    throw new Fault(
                            "result '" + result + "' is none of ANALYSIS, ESTIMATE and READINESS");
        };
    }

    private static WorkRule.Per per(final String per) throws Fault {
        return switch (per) {
            case "story" -> WorkRule.Per.STORY;
            case "repository" -> WorkRule.Per.REPOSITORY;
            default -> throw new Fault("per '" + per + "' is neither story nor repository");
        };
    }

    private static String text(final JsonNode node, final String key) throws Fault {
        final String value = optional(node, key);
        if (value == null) {
            throw new Fault(key + " is missing");
        }
        return value;
    }

    private static @Nullable String optional(final JsonNode node, final String key) throws Fault {
        final JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new Fault(key + " is no text");
        }
        return value.textValue();
    }

    private static List<String> strings(final JsonNode node, final String key, final String what)
            throws Fault {
        final JsonNode value = node.get(key);
        if (value == null) {
            throw new Fault(key + " is missing");
        }
        if (!value.isArray()) {
            throw new Fault(key + " is no list of " + what);
        }
        final List<String> result = new ArrayList<>();
        for (final JsonNode item : value) {
            if (!item.isTextual()) {
                throw new Fault(key + " is no list of " + what);
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static Map<String, String> models(final JsonNode node) throws Fault {
        final JsonNode value = node.get("models");
        if (value == null) {
            throw new Fault("models is missing");
        }
        if (!value.isObject()) {
            throw new Fault("models is no mapping of agent to model");
        }
        final Map<String, String> result = new LinkedHashMap<>();
        for (final var entries = value.fields(); entries.hasNext(); ) {
            final Map.Entry<String, JsonNode> entry = entries.next();
            if (!entry.getValue().isTextual()) {
                throw new Fault("models is no mapping of agent to model");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        }
        return result;
    }

    private static String firstLine(final JacksonException e) {
        final String message = String.valueOf(e.getOriginalMessage());
        final int end = message.indexOf('\n');
        final String line = end < 0 ? message : message.substring(0, end);
        return line.length() > MESSAGE ? line.substring(0, MESSAGE) : line;
    }
}
