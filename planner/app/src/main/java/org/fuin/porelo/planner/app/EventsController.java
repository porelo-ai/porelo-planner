package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.git.LogText;
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
@RegisterReflectionForBinding({Answers.Board.class, WorkAnswers.PlanWork.class})
public class EventsController {

    private static final Logger LOG = LoggerFactory.getLogger(EventsController.class);

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
        final Changes.Subscription subscription = changes.subscribe(name);
        final SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(any -> subscription.close());
        Thread.ofVirtual().name("events-" + name).start(() -> send(emitter, subscription));
        return emitter;
    }

    /**
     * Fetches the project's repositories now.
     *
     * @param project the project's name
     * @return the revision after the fetch
     */
    @PostMapping("/projects/{project}/refresh")
    public Answers.Refreshed refresh(@PathVariable("project") final String project) {
        return new Answers.Refreshed(changes.refresh(BoardController.name(project)));
    }

    private void send(final SseEmitter emitter, final Changes.Subscription subscription) {
        try {
            // A project given anew in the meantime has closed it; its old board is not sent.
            if (subscription.open()) {
                board(emitter, subscription.firstFrame());
            }
            while (subscription.open()) {
                final Optional<Changes.Frame> next = subscription.nextFrame(keepAlive);
                if (next.isPresent()) {
                    board(emitter, next.get());
                } else if (subscription.open()) {
                    emitter.send(SseEmitter.event().comment(" keep-alive"));
                }
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
                                BoardController.boardOf(frame.board(), frame.work()),
                                MediaType.APPLICATION_JSON));
    }
}
