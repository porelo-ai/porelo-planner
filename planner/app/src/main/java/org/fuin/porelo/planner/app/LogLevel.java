package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.http.HttpStatus;

/**
 * The level of the planner's own loggers, set by the window and kept in the state directory, so it
 * holds from the next start too. Only the planner's loggers take it: a library's at DEBUG could
 * write a call's Authorization header.
 */
public final class LogLevel {

    /** The levels a person may choose, the least first. */
    static final List<String> LEVELS = List.of("ERROR", "WARN", "INFO", "DEBUG");

    /** The level when none was chosen. */
    static final String DEFAULT = "INFO";

    /** The file the level is kept in. */
    static final String FILE = "log-level";

    private static final Logger LOG = LoggerFactory.getLogger(LogLevel.class);

    private final LoggingSystem logging;

    private final @Nullable Path file;

    private final PrivateFiles files;

    private LogLevel(
            final LoggingSystem logging,
            final @Nullable Path file,
            final PrivateFiles files,
            final @Nullable String kept) {
        this.logging = logging;
        this.file = file;
        this.files = files;
        // Without a kept level the configured one stays, INFO unless a start names another.
        if (kept != null) {
            apply(kept);
        }
    }

    /**
     * The level kept in the state directory, set at once; where none is kept, the configured one.
     *
     * @param logging the logging system
     * @param files the platform's private files
     * @param environment the planner's environment
     * @param base a base for the state directory instead of the platform's, such as a test's; empty
     *     for none
     * @return the level
     * @throws IllegalStateException when the state directory or the file is not the account's alone
     */
    public static LogLevel kept(
            final LoggingSystem logging,
            final PrivateFiles files,
            final Map<String, String> environment,
            final String base) {
        final Path directory =
                files.directories(
                        base.isBlank()
                                ? files.base(PrivateFiles.Kind.STATE, environment)
                                : Path.of(base),
                        true,
                        PrivateFiles.Kind.STATE,
                        files.steps(PrivateFiles.Kind.STATE));
        final Path file = directory.resolve(FILE);
        return new LogLevel(logging, file, files, read(file, files));
    }

    /**
     * A level kept nowhere, as in the mock mode, which writes nothing of the person's.
     *
     * @param logging the logging system
     * @param files the platform's private files
     * @return the level, the configured one at first
     */
    public static LogLevel inMemory(final LoggingSystem logging, final PrivateFiles files) {
        return new LogLevel(logging, null, files, null);
    }

    /**
     * Answers the level in force.
     *
     * @return one of {@link #LEVELS}
     */
    public synchronized String level() {
        final LoggerConfiguration configuration = logging.getLoggerConfiguration(LogFile.LOGGER);
        final org.springframework.boot.logging.LogLevel found =
                configuration == null ? null : configuration.getEffectiveLevel();
        final String effective = found == null ? DEFAULT : found.name();
        // TRACE and the like are no level a person chose; the nearest of the four stands for them.
        return switch (effective) {
            case "TRACE" -> "DEBUG";
            case "FATAL", "OFF" -> "ERROR";
            default -> LEVELS.contains(effective) ? effective : DEFAULT;
        };
    }

    /**
     * Sets the level, keeps it, and answers it.
     *
     * @param chosen the level the person chose
     * @return the level now in force
     * @throws Refusal when it is none of {@link #LEVELS}
     */
    public synchronized String set(final @Nullable String chosen) {
        if (chosen == null || !LEVELS.contains(chosen)) {
            throw new Refusal(
                    "InvalidLevel",
                    HttpStatus.BAD_REQUEST,
                    "The level is none of " + String.join(", ", LEVELS) + ".",
                    Map.of());
        }
        if (file != null) {
            write(file, chosen);
        }
        apply(chosen);
        return level();
    }

    private void apply(final String chosen) {
        logging.setLogLevel(
                LogFile.LOGGER,
                org.springframework.boot.logging.LogLevel.valueOf(chosen.toUpperCase(Locale.ROOT)));
    }

    /** Reads a kept level; null for a file that is not there or names none of the levels. */
    private static @Nullable String read(final Path file, final PrivateFiles files) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        files.requireOwnFile(file, true);
        try {
            final String kept = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!LEVELS.contains(kept)) {
                LOG.warn("The kept log level names none of {}; the configured one stays", LEVELS);
                return null;
            }
            return kept;
        } catch (final IOException e) {
            throw new UncheckedIOException("the kept log level cannot be read", e);
        }
    }

    /** Writes the level to a new owner-only file and puts it in place. */
    private void write(final Path target, final String chosen) {
        final Path temporary = target.resolveSibling(FILE + "-" + UUID.randomUUID() + ".tmp");
        try {
            try (SeekableByteChannel channel =
                    Files.newByteChannel(
                            temporary,
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            files.ownerOnlyFile())) {
                channel.write(ByteBuffer.wrap((chosen + "\n").getBytes(StandardCharsets.UTF_8)));
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new UncheckedIOException("the log level cannot be kept", e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException ignored) {
                // Left only after a failed write, whose own failure is the one reported.
            }
        }
    }
}
