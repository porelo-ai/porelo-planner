package org.fuin.porelo.planner.store.git;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/** Reads the project's work rules from {@code stories/work.yml}; a fault never throws. */
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
            Set.of("from", "done", "then", "blocked", "per", "modes", "order", "models", "risks");

    private static final Set<String> RISK_KEYS = Set.of("models", "effort");

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
     * Reads the rules.
     *
     * @param text the file's text, or null when the commit has none
     * @return the rules that could be read, and in words why each other one could not
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
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        final List<String> faults = new ArrayList<>();
        work.fields()
                .forEachRemaining(
                        entry -> {
                            try {
                                rules.put(entry.getKey(), rule(entry.getValue()));
                            } catch (final Fault | IllegalArgumentException e) {
                                faults.add(FILE + ": " + entry.getKey() + ": " + e.getMessage());
                            }
                        });
        return new Work(rules, faults);
    }

    private static WorkRule rule(final JsonNode node) throws Fault {
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
                risks(node));
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
