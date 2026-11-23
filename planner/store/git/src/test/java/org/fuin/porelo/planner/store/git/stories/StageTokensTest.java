package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Counting a session's tokens from its file, without Git. */
class StageTokensTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    private static final Instant END = Instant.parse("2026-01-01T11:00:00Z");

    @TempDir Path directory;

    @Test
    void aSessionLargerThanSixtyFourMebibytesIsCountedLineByLine() throws IOException {
        // Harm: a long agent session refused at its stage's end, so the stage is never recorded
        // with the tokens it took.
        final Path session = directory.resolve("long.jsonl");
        final String padding = "x".repeat(1024 * 1024);
        try (BufferedWriter out = Files.newBufferedWriter(session, StandardCharsets.UTF_8)) {
            out.write(assistant("msg_first", 10, 4));
            for (int line = 0; line < 70; line++) {
                out.write(
                        "{\"type\":\"user\",\"timestamp\":\"2026-01-01T10:02:00Z\","
                                + "\"message\":{\"content\":\""
                                + padding
                                + "\"}}\n");
            }
            out.write(assistant("msg_second", 7, 3));
        }
        assertThat(Files.size(session))
                .as("the session's size, above the bound a session once had")
                .isGreaterThan(64L * 1024 * 1024);

        StageCommand.readable(session);
        final Map<String, @Nullable Object> record = new HashMap<>();
        StageTokens.read(session, "agent", START, END, null, false, record);

        assertThat(record)
                .as("the tokens of both messages around the long middle")
                .containsEntry("claude_input_tokens", 17L)
                .containsEntry("claude_output_tokens", 7L)
                .containsEntry("claude_messages", 2L);
    }

    @Test
    void aPrettyPrintedResultIsReadAsOneObject() throws IOException {
        // Harm: agy's result, written over several lines, refused as invalid JSON line by line.
        final Path session =
                Files.writeString(
                        directory.resolve("agy.json"),
                        "\n{\n  \"response\": \"done\",\n  \"usage\": {\n    \"input\": 12,\n"
                                + "    \"cacheRead\": 5,\n    \"output\": 8\n  }\n}\n\n");
        final Map<String, @Nullable Object> record = new HashMap<>();
        StageTokens.read(session, "agent", START, END, null, false, record);

        assertThat(record)
                .as("agy's tokens")
                .containsEntry("agy_input_tokens", 12L)
                .containsEntry("agy_cache_read_tokens", 5L)
                .containsEntry("agy_output_tokens", 8L);
    }

    @Test
    void oneLineFollowedByBlankLinesIsThatOneEntry() throws IOException {
        // Harm: a result written with trailing blank lines refused, though it is one JSON value.
        final Path session =
                Files.writeString(
                        directory.resolve("agy.json"),
                        "{\"usage\":{\"input\":1,\"cacheRead\":2,\"output\":3}}\n\n  \n");
        final Map<String, @Nullable Object> record = new HashMap<>();
        StageTokens.read(session, "agent", START, END, null, false, record);

        assertThat(record).as("agy's tokens").containsEntry("agy_output_tokens", 3L);
    }

    @Test
    void aBlankLineBetweenEntriesOrBadJsonIsRefused() throws IOException {
        // Harm: a session cut or spliced by hand counted as if it were whole.
        final Map<String, String> sessions =
                Map.of(
                        assistant("msg_a", 1, 1) + "\n" + assistant("msg_b", 1, 1),
                        "session contains an empty JSON line",
                        assistant("msg_a", 1, 1) + assistant("msg_b", 1, 1) + "\n",
                        "session contains an empty JSON line",
                        assistant("msg_a", 1, 1) + "{\"type\":",
                        "session contains invalid JSON",
                        "{\"type\":",
                        "session contains invalid JSON");
        for (final Map.Entry<String, String> text : sessions.entrySet()) {
            final Path session = Files.writeString(directory.resolve("bad.jsonl"), text.getKey());

            assertThatThrownBy(
                            () ->
                                    StageTokens.read(
                                            session,
                                            "agent",
                                            START,
                                            END,
                                            null,
                                            false,
                                            new HashMap<>()))
                    .as("the session %s", text.getKey())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(text.getValue());
        }
    }

    /** One line of a Claude session: an assistant's message with its usage, within the stage. */
    @Test
    void aSessionThatGrowsWhileItIsReadIsRefused() throws IOException {
        // Harm: a session another process keeps writing is read for as long as it grows, so the
        // stage's end never comes, or its tokens are counted past the read.
        final Path session =
                Files.writeString(directory.resolve("growing.jsonl"), assistant("msg_a", 10, 4));
        try (Reader text = SessionFile.open(session)) {
            Files.writeString(session, assistant("msg_b", 7, 3), StandardOpenOption.APPEND);
            assertThatThrownBy(() -> text.transferTo(Writer.nullWriter()))
                    .as("a session that took bytes after it was opened")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(SessionFile.GREW);
        }
    }

    @Test
    void aLinkIsRefusedByTheOpeningItself() throws IOException {
        // Harm: a session swapped for a link after its name was checked is read in place of the
        // agent's own log.
        final Path target =
                Files.writeString(directory.resolve("other.jsonl"), assistant("msg_a", 10, 4));
        final Path link = Files.createSymbolicLink(directory.resolve("session.jsonl"), target);
        assertThatThrownBy(() -> SessionFile.opened(link))
                .as("a link opened without its name checked")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SessionFile.NOT_REGULAR);
    }

    @Test
    void aDirectoryIsRefusedOnceItIsOpened() throws IOException {
        // Harm: a session swapped for something that is no regular file after its name was
        // checked is read as if it were the agent's log.
        final Path folder = Files.createDirectory(directory.resolve("folder.jsonl"));
        assertThatThrownBy(() -> SessionFile.opened(folder).close())
                .as("a directory opened without its name checked")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SessionFile.NOT_REGULAR);
    }

    private static String assistant(final String id, final long input, final long output) {
        return "{\"type\":\"assistant\",\"timestamp\":\"2026-01-01T10:01:00Z\",\"message\":"
                + "{\"usage\":{\"input_tokens\":"
                + input
                + ",\"output_tokens\":"
                + output
                + ",\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0},\"id\":\""
                + id
                + "\"}}\n";
    }
}
