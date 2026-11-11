package org.fuin.porelo.planner.app;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * The planner's log file, {@code planner.log} in the directory the window keeps its own log in,
 * readable by the account alone, its newest lines kept within a bound. Only the planner's own
 * loggers write to it: their values pass {@link org.fuin.porelo.planner.store.git.LogText}, which a
 * library's lines do not.
 */
public final class LogFile implements AutoCloseable {

    /** The log file's name. */
    static final String NAME = "planner.log";

    /** The logger the file takes its lines from, with every logger below it. */
    static final String LOGGER = "org.fuin.porelo.planner";

    /** One line: when, how grave, which class, what; never a stack trace. */
    private static final String PATTERN =
            "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level %logger{0} - %msg%n%nopex";

    private final @Nullable Path path;

    private final @Nullable RollingFileAppender<ILoggingEvent> appender;

    private LogFile(
            final @Nullable Path path,
            final @Nullable RollingFileAppender<ILoggingEvent> appender) {
        this.path = path;
        this.appender = appender;
    }

    /**
     * No log file, as in the mock mode, which writes nothing of the person's.
     *
     * @return a log file that writes nowhere
     */
    public static LogFile none() {
        return new LogFile(null, null);
    }

    /**
     * Where the log lies: {@code porelo/logs} under {@code XDG_STATE_HOME} on Linux and under
     * {@code %LOCALAPPDATA%} on Windows, the window's own log beside it; made, or checked when the
     * window made it, the account's alone.
     *
     * @param files the platform's private files
     * @param environment the planner's environment
     * @param base a base given instead of the platform's, such as a test's own; empty for none
     * @return the log directory
     * @throws IllegalStateException when it cannot be made or is not the account's alone, naming it
     */
    public static Path directory(
            final PrivateFiles files, final Map<String, String> environment, final String base) {
        Path where = null;
        try {
            where =
                    base.isBlank()
                            ? files.base(PrivateFiles.Kind.LOGS, environment)
                            : Path.of(base);
            return files.directories(
                    where, true, PrivateFiles.Kind.LOGS, files.steps(PrivateFiles.Kind.LOGS));
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "the planner's log directory "
                            + (where == null ? "" : where.resolve("porelo").resolve("logs") + " ")
                            + "cannot be used: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Opens the log file in a checked directory and has the planner's loggers write to it.
     *
     * @param directory the log directory, from {@link #directory}
     * @param files the platform's private files
     * @param maxFileBytes the size at which the file is rolled over
     * @param history how many rolled-over files are kept
     * @return the open log file
     * @throws IllegalStateException when the file cannot be made the account's alone
     */
    public static LogFile open(
            final Path directory,
            final PrivateFiles files,
            final long maxFileBytes,
            final int history) {
        final Path file = directory.resolve(NAME);
        try {
            prepare(file, files);
        } catch (final IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "the planner's log file " + file + " cannot be used: " + e.getMessage(), e);
        }
        final LoggerContext context = context();
        final PrivateAppender appender = new PrivateAppender(files);
        appender.setContext(context);
        appender.setName("planner-file");
        appender.setFile(file.toString());
        appender.setAppend(true);

        final FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
        rolling.setContext(context);
        rolling.setParent(appender);
        rolling.setFileNamePattern(directory.resolve("planner.%i.log").toString());
        rolling.setMinIndex(1);
        rolling.setMaxIndex(history);
        rolling.start();

        final SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>();
        trigger.setContext(context);
        trigger.setMaxFileSize(new FileSize(maxFileBytes));
        // At every line, not once a minute as by default: a burst would grow the file past its
        // bound before the next look.
        trigger.setCheckIncrement(Duration.buildByMilliseconds(0));
        trigger.start();

        final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        appender.setRollingPolicy(rolling);
        appender.setTriggeringPolicy(trigger);
        appender.setEncoder(encoder);
        appender.start();
        if (!appender.isStarted()) {
            throw new IllegalStateException("the planner's log file " + file + " cannot be opened");
        }
        context.getLogger(LOGGER).addAppender(appender);
        return new LogFile(file, appender);
    }

    /**
     * Answers the log file's path.
     *
     * @return its absolute path, or {@code null} when the planner keeps no log file
     */
    public @Nullable Path path() {
        return path;
    }

    /** Stops writing to the file. */
    @Override
    public void close() {
        if (appender != null) {
            context().getLogger(LOGGER).detachAppender(appender);
            appender.stop();
        }
    }

    private static LoggerContext context() {
        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException(
                    "the planner logs through Logback, not " + factory.getClass().getName());
        }
        return context;
    }

    /**
     * Makes the file the account's alone before anything opens it, since an open by name takes the
     * umask's mode, and refuses one that is a link, another's or open to others.
     */
    static void prepare(final Path file, final PrivateFiles files) throws IOException {
        try {
            Files.newByteChannel(
                            file,
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            files.ownerOnlyFile())
                    .close();
        } catch (final FileAlreadyExistsException exists) {
            // An earlier start's file, checked below like a new one.
        }
        files.requireOwnFile(file, true);
    }

    /**
     * Opens the file, at the start and after every roll-over, only once it is the account's own.
     */
    private static final class PrivateAppender extends RollingFileAppender<ILoggingEvent> {

        private final PrivateFiles files;

        PrivateAppender(final PrivateFiles files) {
            this.files = files;
        }

        @Override
        public void openFile(final String name) throws IOException {
            try {
                prepare(Path.of(name), files);
            } catch (final RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            }
            super.openFile(name);
        }
    }
}
