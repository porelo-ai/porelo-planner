package org.fuin.porelo.planner.cleanup;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads Artifactory's deep file list of {@code porelo-files/porelo-planner} ({@code GET
 * /api/storage/porelo-files/porelo-planner?list&deep=1&listFolders=0}): every file's path below the
 * folder and when it last changed.
 */
public final class Listing {

    /** The repository and folder whose builds the clean-up chooses among. */
    public static final String PREFIX = "porelo-files/porelo-planner";

    /** What the listing's own address ends with, so a listing of another folder is refused. */
    private static final String LISTED = "/api/storage/" + PREFIX;

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // Artifactory writes ISO 8601 with "Z", "+00:00" or "+0000" by its version.
    private static final DateTimeFormatter TIME =
            new DateTimeFormatterBuilder()
                    .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .optionalStart()
                    .appendOffset("+HH:MM", "Z")
                    .optionalEnd()
                    .optionalStart()
                    .appendOffset("+HHMM", "Z")
                    .optionalEnd()
                    .toFormatter();

    private Listing() {}

    /**
     * Reads a listing whole, or refuses it.
     *
     * @param text the listing's JSON as Artifactory answered it
     * @return every file under the prefix that stands in a folder, by that first folder
     * @throws Unreadable when the text is no whole listing of the prefix, or one of its files has
     *     no path or no time
     */
    public static List<Build> read(final String text) throws Unreadable {
        final JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (final JacksonException e) {
            throw new Unreadable("the listing is no whole JSON document", e);
        }
        if (root == null || !root.isObject()) {
            throw new Unreadable("the listing is no JSON object");
        }
        final JsonNode uri = root.get("uri");
        if (uri == null || !uri.isTextual() || !stripped(uri.asText()).endsWith(LISTED)) {
            throw new Unreadable("the listing's uri is " + uri + ", not one ending in " + LISTED);
        }
        final JsonNode files = root.get("files");
        if (files == null || !files.isArray()) {
            throw new Unreadable("the listing has no array 'files'");
        }
        final List<Build> builds = new ArrayList<>();
        int index = 0;
        for (final JsonNode file : files) {
            final Build build = build(file, "files[" + index + "]");
            if (build != null) {
                builds.add(build);
            }
            index++;
        }
        return List.copyOf(builds);
    }

    private static @Nullable Build build(final JsonNode file, final String where)
            throws Unreadable {
        if (!file.isObject()) {
            throw new Unreadable(where + " is no JSON object");
        }
        final JsonNode folder = file.get("folder");
        if (folder != null && !folder.isBoolean()) {
            throw new Unreadable(where + ".folder is " + folder + ", not true or false");
        }
        final JsonNode uri = file.get("uri");
        if (uri == null || !uri.isTextual() || !uri.asText().startsWith("/")) {
            throw new Unreadable(where + ".uri is " + uri + ", not a path starting with /");
        }
        final JsonNode modified = file.get("lastModified");
        if (modified == null || !modified.isTextual()) {
            throw new Unreadable(where + ".lastModified is " + modified + ", not a time");
        }
        final Instant time;
        try {
            time = OffsetDateTime.parse(modified.asText(), TIME).toInstant();
        } catch (final DateTimeParseException e) {
            throw new Unreadable(where + ".lastModified " + modified + " is no ISO 8601 time", e);
        }
        if (folder != null && folder.asBoolean()) {
            return null;
        }
        final String path = uri.asText().substring(1);
        final int slash = path.indexOf('/');
        // A file directly under the prefix stands in no build's folder.
        return slash < 0 ? null : new Build(decoded(path.substring(0, slash)), time);
    }

    private static String stripped(final String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    /** Percent-decoded; a malformed escape stays as written, which no version matches. */
    static String decoded(final String segment) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            final char c = segment.charAt(i);
            if (c == '%' && hex(segment, i + 1)) {
                bytes.write(Integer.parseInt(segment.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c > 0x7f) {
                return segment;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean hex(final String text, final int at) {
        return at + 1 < text.length()
                && Character.digit(text.charAt(at), 16) >= 0
                && Character.digit(text.charAt(at + 1), 16) >= 0;
    }
}
