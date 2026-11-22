package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.planner.app.answers.Board;
import org.fuin.porelo.planner.app.answers.PlanWork;
import org.fuin.porelo.planner.app.answers.Refreshed;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ReadProgress;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tells a project's boards to the windows that follow it, as Server-Sent Events, and fetches the
 * repositories when a window asks.
 */
@RestController
// The board goes out through SseEmitter, so no return type tells the native image of it.
@RegisterReflectionForBinding({Board.class, PlanWork.class})
public class EventsController {

    private static final Logger LOG = LoggerFactory.getLogger(EventsController.class);

    /**
     * How long the events request waits for the first board before its stream starts; a refusal
     * within it is answered as problem+json, as before.
     */
    private static final Duration AT_ONCE = Duration.ofSeconds(2);

    /** How often a stream waiting for its first board tells how far the read has come. */
    private static final Duration LOAD_EVERY = Duration.ofSeconds(5);

    private final ChangesRegistry changes;

    private final Duration keepAlive;

    /**
     * Makes the controller.
     *
     * @param changes the projects followed
     * @param keepAlive how long the stream may be silent before it sends a keep-alive comment
     */
    public EventsController(
            final ChangesRegistry changes,
            @Value("${planner.keep-alive:15s}") final Duration keepAlive) {
        this.changes = changes;
        this.keepAlive = keepAlive;
    }

    /**
     * Streams the project's board, first at once and then on every change.
     *
     * @param project the project's name
     * @return the stream
     */
    @GetMapping(path = "/projects/{project}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("project") final String project) {
        final String name = BoardController.name(project);
        // Refused here, before the stream starts, so an unknown project answers problem+json.
        final PlanningStore store = changes.projects().store(name);
        final Instant began = Instant.now();
        final CompletableFuture<Changes.Subscription> first = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("events-first-" + name)
                .start(
                        () -> {
                            try {
                                first.complete(changes.subscribe(name));
                            } catch (final RuntimeException e) {
                                first.completeExceptionally(e);
                            }
                        });
        Changes.@Nullable Subscription ready = null;
        try {
            ready = first.get(AT_ONCE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            // A refusal of a quick first read is answered as before the stream starts.
            if (e.getCause() instanceof RuntimeException refused) {
                throw refused;
            }
            throw new IllegalStateException(e.getCause());
        } catch (final TimeoutException e) {
            // A long first read: the stream starts now and tells the read's progress.
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The events request was stopped", e);
        }
        final SseEmitter emitter = new SseEmitter(0L);
        final Changes.Subscription quick = ready;
        if (quick != null) {
            emitter.onCompletion(quick::close);
            emitter.onTimeout(quick::close);
            emitter.onError(any -> quick.close());
            Thread.ofVirtual()
                    .name("events-" + name)
                    .start(() -> send(emitter, quick, store, name));
            return emitter;
        }
        emitter.onCompletion(() -> first.thenAccept(Changes.Subscription::close));
        emitter.onTimeout(() -> first.thenAccept(Changes.Subscription::close));
        emitter.onError(any -> first.thenAccept(Changes.Subscription::close));
        Thread.ofVirtual()
                .name("events-" + name)
                .start(() -> sendAfterLoad(emitter, first, store, began, name));
        return emitter;
    }

    /** Tells the read's progress until the first board is there, then streams as before. */
    private void sendAfterLoad(
            final SseEmitter emitter,
            final CompletableFuture<Changes.Subscription> first,
            final PlanningStore store,
            final Instant began,
            final String name) {
        Changes.@Nullable Subscription subscription = null;
        try {
            while (subscription == null) {
                load(emitter, store, began);
                try {
                    subscription = first.get(LOAD_EVERY.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final TimeoutException e) {
                    // Still reading; the next load event follows.
                }
            }
        } catch (final ExecutionException e) {
            failed(emitter, java.util.Objects.requireNonNullElse(e.getCause(), e));
            return;
        } catch (final IOException | RuntimeException e) {
            LOG.debug("A stream ended while the board was read: {}", LogText.of(e.getMessage()));
            emitter.complete();
            return;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
            return;
        }
        send(emitter, subscription, store, name);
    }

    private static void load(
            final SseEmitter emitter, final PlanningStore store, final Instant began)
            throws IOException {
        final Optional<ReadProgress> progress = store.reading();
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", "reading");
        data.put("since", progress.map(ReadProgress::since).orElse(began).toString());
        data.put("read", progress.map(ReadProgress::read).orElse(0));
        data.put("total", progress.map(ReadProgress::total).orElse(null));
        data.put("lastRead", progress.map(ReadProgress::lastRead).orElse(null));
        progress.map(ReadProgress::from).ifPresent(from -> data.put("from", from));
        emitter.send(SseEmitter.event().name("load").data(data, MediaType.APPLICATION_JSON));
    }

    /** Tells a first read's refusal in the stream that already began, and ends the stream. */
    private static void failed(final SseEmitter emitter, final Throwable cause) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("load")
                            .data(failure(cause), MediaType.APPLICATION_JSON));
        } catch (final IOException | RuntimeException e) {
            LOG.debug("A stream ended before its refusal: {}", LogText.of(e.getMessage()));
        }
        emitter.complete();
    }

    /** The load event's data for a read that failed: its problem, words and parameters. */
    private static Map<String, Object> failure(final Throwable cause) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", "failed");
        data.put(
                "problem",
                cause instanceof StoreException known
                        ? known.getClass().getSimpleName()
                        : "Unreadable");
        data.put(
                "failure",
                cause instanceof StoreException known
                        ? String.valueOf(known.getMessage())
                        : "The board could not be read.");
        // The parameters its problem+json carries, so the window offers a token as it does there.
        if (cause instanceof StoreException.NoForgeAccess refused) {
            final Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("address", refused.subject());
            if (refused.right() != null) {
                parameters.put("right", refused.right());
            }
            data.put("parameters", parameters);
        } else if (cause instanceof StoreException known) {
            data.put("parameters", Map.of("repository", known.subject()));
        }
        return data;
    }

    /**
     * Fetches the project's repositories now.
     *
     * @param project the project's name
     * @return the revision after the fetch
     */
    @PostMapping("/projects/{project}/refresh")
    public Refreshed refresh(@PathVariable("project") final String project) {
        return new Refreshed(changes.refresh(BoardController.name(project)));
    }

    /**
     * Streams the boards; while the store reads the whole board again, a reload's read or a poll's,
     * it tells the read's progress every few seconds, and that the read ended: with its board, as
     * done, or as failed with its problem, which leaves the stream open.
     */
    private void send(
            final SseEmitter emitter,
            final Changes.Subscription subscription,
            final PlanningStore store,
            final String name) {
        try {
            // A project given anew in the meantime has closed it; its old board is not sent.
            if (subscription.open()) {
                board(emitter, subscription.firstFrame());
            }
            final Duration step = keepAlive.compareTo(LOAD_EVERY) < 0 ? keepAlive : LOAD_EVERY;
            Instant lastSent = Instant.now();
            Instant readSince = Instant.now();
            // A look right after the first board takes about a second: its progress is told at
            // once, so the window sees it end with done rather than never see it.
            boolean reading = subscription.open() && store.reading().isPresent();
            if (reading) {
                load(emitter, store, Instant.now());
                lastSent = Instant.now();
            }
            while (subscription.open()) {
                final Optional<Changes.Frame> next = subscription.nextFrame(step);
                if (!subscription.open()) {
                    break;
                }
                final boolean readsNow = store.reading().isPresent();
                if (readsNow && !reading) {
                    readSince = Instant.now().minus(step);
                }
                final Optional<ChangesRegistry.ReadFailure> failed = changes.lastFailure(name);
                final Instant since = readSince;
                if (next.isEmpty()
                        && !readsNow
                        && reading
                        && failed.isPresent()
                        && failed.get().at().isAfter(since)) {
                    // A read that failed after the first board: its problem, and the stream stays.
                    emitter.send(
                            SseEmitter.event()
                                    .name("load")
                                    .data(
                                            failure(failed.get().cause()),
                                            MediaType.APPLICATION_JSON));
                    lastSent = Instant.now();
                } else if (next.isPresent()) {
                    board(emitter, next.get());
                    lastSent = Instant.now();
                } else if (readsNow) {
                    load(emitter, store, Instant.now());
                    lastSent = Instant.now();
                } else if (reading) {
                    // A read that changed nothing sends no board; the window is told it ended.
                    emitter.send(
                            SseEmitter.event()
                                    .name("load")
                                    .data(Map.of("state", "done"), MediaType.APPLICATION_JSON));
                    lastSent = Instant.now();
                } else if (!Instant.now().isBefore(lastSent.plus(keepAlive))) {
                    emitter.send(SseEmitter.event().comment(" keep-alive"));
                    lastSent = Instant.now();
                }
                reading = readsNow;
            }
        } catch (final IOException | RuntimeException e) {
            // The window went away; its subscription goes with it.
            LOG.debug("A stream ended: {}", LogText.of(e.getMessage()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            subscription.close();
            emitter.complete();
        }
    }

    private static void board(final SseEmitter emitter, final Changes.Frame frame)
            throws IOException {
        emitter.send(
                SseEmitter.event()
                        .name("board")
                        .id(frame.board().revision().value())
                        .data(
                                BoardController.boardOf(
                                        frame.board(),
                                        frame.work(),
                                        frame.derive(),
                                        frame.reasons()),
                                MediaType.APPLICATION_JSON));
    }
}
