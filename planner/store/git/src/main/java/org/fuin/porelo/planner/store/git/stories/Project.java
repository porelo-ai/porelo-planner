package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The repositories a project names in its {@code project.yml}: its own, under {@code project}, and
 * each one under {@code repositories} with the issue prefixes it owns and its central address.
 *
 * @param own the name of the project's own repository
 * @param repositories every repository by name, the own one first, each with its issue prefixes in
 *     the order {@code project.yml} gives them
 * @param centrals every repository's central address by name, in the order of the repositories, as
 *     {@code project.yml} writes it under {@code central} (or a not yet renamed {@code upstream})
 */
public record Project(
        String own, Map<String, Set<String>> repositories, Map<String, String> centrals) {

    /** Where the index finds the issues of a repository, to learn the own repository's prefixes. */
    interface Indexes {
        Map<String, IssueIndex> read(String repository) throws IOException;
    }

    private static final String FILE = "project.yml";
    private static final ObjectMapper YAML =
            new ObjectMapper(
                    new YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    private static final Pattern UPSTREAM =
            Pattern.compile(
                    "^(?:git@github\\.com:|https://github\\.com/)porelo-ai/([A-Za-z0-9][A-Za-z0-9_.-]*?)(?:\\.git)?$");

    /**
     * Refuses a project that does not hold its own repository or whose centrals name other
     * repositories, and copies the repositories, their prefixes and their centrals to keep it
     * immutable, the centrals in the order of the repositories.
     *
     * @throws NullPointerException when the own repository, the repositories, the centrals, a
     *     repository's prefixes or a central address are null
     * @throws IllegalArgumentException when the repositories do not hold the own one, or the
     *     centrals' names are not the repositories'
     */
    public Project {
        Objects.requireNonNull(own, "own");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(centrals, "centrals");
        if (!repositories.containsKey(own))
            throw new IllegalArgumentException(
                    "own repository " + own + " is not among the repositories");
        if (!centrals.keySet().equals(repositories.keySet()))
            throw new IllegalArgumentException(
                    "centrals "
                            + centrals.keySet()
                            + " are not those of the repositories "
                            + repositories.keySet());
        final Map<String, Set<String>> copied = new LinkedHashMap<>();
        final Map<String, String> addresses = new LinkedHashMap<>();
        for (final var entry : repositories.entrySet()) {
            copied.put(entry.getKey(), ordered(entry.getValue()));
            addresses.put(
                    entry.getKey(),
                    Objects.requireNonNull(centrals.get(entry.getKey()), "central"));
        }
        repositories = Collections.unmodifiableMap(copied);
        centrals = Collections.unmodifiableMap(addresses);
    }

    /**
     * A repository's central address, as {@code project.yml} writes it.
     *
     * @param name the name of a repository of the project
     * @return its central address
     * @throws IllegalArgumentException when the project names no repository {@code name}
     */
    public String central(String name) {
        final String central = centrals.get(name);
        if (central == null)
            throw new IllegalArgumentException(name + " is not among the repositories");
        return central;
    }

    /** The prefixes in the order given, since a new issue takes a repository's first one. */
    private static Set<String> ordered(final Set<String> prefixes) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.copyOf(prefixes)));
    }

    /** A repository's name, derived from its central address, and the address as written. */
    private record Repository(String name, String central) {}

    static String own(Path file) throws IOException {
        return repository(required(yaml(file), "project", FILE), "project").name();
    }

    /**
     * Reads a project from the text of its {@code project.yml}, as the stories index does.
     *
     * @param text the text of {@code project.yml}
     * @param ownPrefixes the issue prefixes of the project's own repository, which {@code
     *     project.yml} does not name
     * @return the project
     * @throws IllegalArgumentException when the text is no project, in the words the stories index
     *     refuses it with
     * @throws NullPointerException when {@code ownPrefixes} is null, with that message
     */
    public static Project read(String text, Set<String> ownPrefixes) {
        Objects.requireNonNull(ownPrefixes, "ownPrefixes");
        final JsonNode project = yaml(text, FILE);
        return of(project, own(project), ownPrefixes);
    }

    static Project read(Path file, Indexes indexes) throws IOException {
        Path root = file.toAbsolutePath().normalize().getParent();
        while (root != null && !Files.isRegularFile(root.resolve(FILE))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new NoSuchFileException(file.toString(), null, "no project.yml above the plan");
        }
        final JsonNode project = yaml(root.resolve(FILE));
        final Repository own = own(project);
        final Set<String> ownPrefixes = new HashSet<>();
        indexes.read(own.name())
                .keySet()
                .forEach(number -> ownPrefixes.add(number.replaceAll("\\d+$", "")));
        return of(project, own, ownPrefixes);
    }

    private static Repository own(JsonNode project) {
        return repository(required(project, "project", FILE), "project");
    }

    private static Project of(JsonNode project, Repository own, Set<String> ownPrefixes) {
        final Map<String, Set<String>> repositories = new LinkedHashMap<>();
        final Map<String, String> centrals = new LinkedHashMap<>();
        repositories.put(own.name(), ordered(ownPrefixes));
        centrals.put(own.name(), own.central());
        final JsonNode declared = required(project, "repositories", FILE);
        if (!declared.isObject()) {
            throw new IllegalArgumentException("project.yml repositories: expected a mapping");
        }
        final var entries = declared.properties().iterator();
        while (entries.hasNext()) {
            final var entry = entries.next();
            final String where = "project.yml repositories." + entry.getKey();
            final Repository repository = repository(entry.getValue(), where);
            final String name = repository.name();
            final Set<String> prefixes =
                    new LinkedHashSet<>(strings(entry.getValue(), "issues", where));
            if (prefixes.isEmpty()
                    || prefixes.stream().anyMatch(prefix -> !prefix.matches("[A-Z]{1,3}"))) {
                throw new IllegalArgumentException(where + ": issues must name uppercase prefixes");
            }
            if (repositories.putIfAbsent(name, ordered(prefixes)) != null) {
                throw new IllegalArgumentException(where + ": duplicate repository " + name);
            }
            centrals.put(name, repository.central());
        }
        return new Project(own.name(), repositories, centrals);
    }

    // Parsed from the bytes, so the parser skips a byte order mark and refuses bad UTF-8 as YAML.
    private static JsonNode yaml(Path file) throws IOException {
        try (var input = Files.newInputStream(file);
                var parser = YAML.createParser(input)) {
            return yaml(parser, file.toString());
        }
    }

    private static JsonNode yaml(String text, String file) {
        try (var parser = YAML.createParser(text)) {
            return yaml(parser, file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static JsonNode yaml(JsonParser parser, String file) throws IOException {
        try {
            final JsonNode data = YAML.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(file + ": expected exactly one YAML document");
            }
            if (data == null || !data.isObject()) {
                throw new IllegalArgumentException(file + ": expected a YAML mapping");
            }
            return data;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    file + ": invalid YAML: " + ex.getOriginalMessage(), ex);
        }
    }

    private static JsonNode required(JsonNode data, String key, String where)
            throws IllegalArgumentException {
        final JsonNode value = data.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(where + ": missing required field " + key);
        }
        return value;
    }

    private static String string(JsonNode data, String key, String where)
            throws IllegalArgumentException {
        final JsonNode value = required(data, key, where);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(where + ": " + key + " must be a nonblank string");
        }
        return value.textValue();
    }

    private static JsonNode array(JsonNode data, String key, String where)
            throws IllegalArgumentException {
        final JsonNode value = required(data, key, where);
        if (!value.isArray()) {
            throw new IllegalArgumentException(where + ": " + key + " must be a list");
        }
        return value;
    }

    private static List<String> strings(JsonNode data, String key, String where)
            throws IllegalArgumentException {
        if (!data.has(key)) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (final JsonNode value : array(data, key, where)) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        where + ": " + key + " must contain nonblank strings");
            }
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    private static Repository repository(JsonNode data, String where)
            throws IllegalArgumentException {
        // A project.yml not yet renamed still names the central remote "upstream".
        final String key = data.has("central") ? "central" : "upstream";
        final String central = string(data, key, where);
        final Matcher match = UPSTREAM.matcher(central);
        if (!match.matches()) {
            throw new IllegalArgumentException(
                    where + ": unsupported repository " + key + " " + central);
        }
        return new Repository(match.group(1), central);
    }
}
