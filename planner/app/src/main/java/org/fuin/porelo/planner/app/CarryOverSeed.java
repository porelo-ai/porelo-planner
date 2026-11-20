package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The velocity seed a carry-over writes into {@code project.yml}: whole minutes per repository and
 * size, read from a file of {@code repository,size,minutes} rows the person derived from the
 * velocity records.
 */
final class CarryOverSeed {

    /** The first line the file starts with. */
    static final String HEADER = "repository,size,minutes";

    /** The sizes a seed names, in their order. */
    static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /** The most minutes one seed names. */
    static final int MOST_MINUTES = 100_000;

    /** The largest file read. */
    static final int MOST_BYTES = 64 * 1024;

    private static final Pattern MINUTES = Pattern.compile("[1-9][0-9]{0,5}");

    private CarryOverSeed() {}

    /**
     * Reads a seed file.
     *
     * @param file the file, a plain file and no link
     * @param repositories the project's repositories, which each row must name
     * @return the minutes by repository and size, in the project's and the sizes' order
     * @throws IllegalArgumentException naming the line and what is wrong, never repeating a value
     * @throws IOException when the file cannot be read
     */
    static Map<String, Map<String, Integer>> read(final Path file, final List<String> repositories)
            throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("--seed: not a plain file");
        }
        final byte[] bytes;
        try (InputStream in =
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MOST_BYTES + 1);
        }
        if (bytes.length > MOST_BYTES) {
            throw new IllegalArgumentException(
                    "--seed: the file is larger than " + MOST_BYTES + " bytes");
        }
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
        } catch (final CharacterCodingException e) {
            throw new IllegalArgumentException("--seed: the file is not UTF-8");
        }
        final List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException("--seed: the first line is not " + HEADER);
        }
        final Set<String> known = Set.copyOf(repositories);
        final Map<String, Map<String, Integer>> seed = new TreeMap<>();
        for (int at = 1; at < lines.size(); at++) {
            final String line = lines.get(at);
            if (line.isEmpty() && at == lines.size() - 1) {
                break;
            }
            final String where = "--seed: line " + (at + 1) + ": ";
            final String[] parts = line.split(",", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException(where + "not three values");
            }
            if (!known.contains(parts[0])) {
                throw new IllegalArgumentException(where + "no repository of the project");
            }
            if (!SIZES.contains(parts[1])) {
                throw new IllegalArgumentException(where + "the size is none of " + SIZES);
            }
            if (!MINUTES.matcher(parts[2]).matches() || Integer.parseInt(parts[2]) > MOST_MINUTES) {
                throw new IllegalArgumentException(
                        where + "the minutes are no whole number from 1 to " + MOST_MINUTES);
            }
            if (seed.computeIfAbsent(parts[0], any -> new TreeMap<>())
                            .put(parts[1], Integer.parseInt(parts[2]))
                    != null) {
                throw new IllegalArgumentException(where + "its repository and size stand twice");
            }
        }
        final Map<String, Map<String, Integer>> ordered = new LinkedHashMap<>();
        for (final String repository : repositories) {
            final Map<String, Integer> sizes = seed.get(repository);
            if (sizes == null) {
                continue;
            }
            final Map<String, Integer> bySize = new LinkedHashMap<>();
            SIZES.forEach(
                    size -> {
                        final Integer minutes = sizes.get(size);
                        if (minutes != null) {
                            bySize.put(size, minutes);
                        }
                    });
            ordered.put(repository, Collections.unmodifiableMap(bySize));
        }
        return Collections.unmodifiableMap(ordered);
    }

    /**
     * Writes the seed as {@code project.yml}'s {@code velocity.seed}.
     *
     * @param seed the minutes by repository and size
     * @return the lines, ending with a line feed
     */
    static String yaml(final Map<String, Map<String, Integer>> seed) {
        final StringBuilder yaml = new StringBuilder("velocity:\n    seed:\n");
        seed.forEach(
                (repository, sizes) -> {
                    yaml.append("        \"").append(repository).append("\":\n");
                    sizes.forEach(
                            (size, minutes) ->
                                    yaml.append("            \"")
                                            .append(size)
                                            .append("\": ")
                                            .append(minutes)
                                            .append('\n'));
                });
        return yaml.toString();
    }
}
