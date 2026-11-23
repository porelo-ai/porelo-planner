package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.app.answers.Tracker;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.planner.store.local.ItemsDirectories;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Serves where a local project's stories and issues are kept: the directory the person chooses is
 * kept in the planner's own state on this computer, and the project's store is opened anew on it.
 */
@RestController
public class TrackerController {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerController.class);

    /** The largest body taken, in bytes. */
    static final int MOST_BODY = 8192;

    private static final Set<String> MEMBERS = Set.of("directory", "seen");

    /** Why a body is refused, in the fixed words the description gives. */
    static final String BODY_REFUSED =
            "The body is not one JSON object of at most "
                    + MOST_BODY
                    + " bytes with exactly the members directory and seen.";

    private final Projects projects;

    private final ItemsDirectories directories;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     * @param directories the directories the person chose, by project
     */
    public TrackerController(final Projects projects, final ItemsDirectories directories) {
        this.projects = projects;
        this.directories = directories;
    }

    /**
     * The body as the window sends it, read by hand so that a member missing, added or given twice
     * is refused.
     *
     * @param directory the directory, or null for the default
     * @param seen the versions the person saw; the call has no target
     */
    public record ItemsDirectoryRequest(@Nullable String directory, Basis seen) {}

    /**
     * Keeps the directory a local project's stories and issues are kept in, or the default again,
     * and opens the project's store on it; nothing is kept on a refusal, and the directory in use
     * chosen again changes nothing.
     *
     * @param project the project's name
     * @param request the request, whose body is the directory and the versions seen
     * @return where the project's stories and issues are kept now
     */
    @PutMapping("/projects/{project}/tracker/items")
    public Tracker setItemsDirectory(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final PlanningStore store = projects.store(name);
        if (store.tracker().kind() != org.fuin.porelo.planner.store.Tracker.Kind.LOCAL) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner keeps no directory for a project not kept locally.",
                    Map.of("project", name));
        }
        final ItemsDirectoryRequest body = body(name, request);
        final @Nullable Path chosen;
        try {
            chosen = body.directory() == null ? null : directories.checked(body.directory());
        } catch (final StoreException.ItemsDirectoryRefused refused) {
            throw refused(name, refused);
        }
        final ItemsDirectories.Kept now = directories.kept(name);
        final boolean same =
                chosen == null
                        ? now instanceof ItemsDirectories.Default
                        : now.equals(new ItemsDirectories.Chosen(chosen));
        if (!same) {
            projects.reopen(name, () -> keep(name, chosen));
            LOG.info(
                    "Project {} keeps its items in {}",
                    LogText.of(name),
                    chosen == null ? "its default directory" : "a directory the person chose");
        }
        final PlanningStore opened = projects.store(name);
        final org.fuin.porelo.planner.store.Tracker tracker = opened.tracker();
        if (tracker.items() == null) {
            // The store's directories are refused, the default too: its read says why.
            opened.board();
        }
        return BoardController.tracker(tracker);
    }

    /** Keeps the choice, or refuses the call with the project as it was. */
    private void keep(final String name, final @Nullable Path chosen) {
        try {
            directories.keep(name, chosen);
        } catch (final UncheckedIOException | IllegalStateException e) {
            // The words name paths, which may name the person's account.
            throw Refusal.work(
                    WorkRefusal.recordUnavailable(
                            name,
                            "The choice of directory cannot be kept in the planner's state"
                                    + " directory."));
        }
    }

    /** The body, or refused naming no directory. */
    private static ItemsDirectoryRequest body(final String name, final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = WorkRequests.read(request, MOST_BODY);
        } catch (final WorkRefusal invalid) {
            throw malformed(name);
        }
        if (!MEMBERS.equals(Set.copyOf(body.propertyNames()))) {
            throw malformed(name);
        }
        final Basis seen = WorkRequests.seen(body.get("seen"));
        final JsonNode directory = body.get("directory");
        if (seen == null || !(directory.isNull() || directory.isString())) {
            throw malformed(name);
        }
        return new ItemsDirectoryRequest(directory.isNull() ? null : directory.asString(), seen);
    }

    private static Refusal malformed(final String name) {
        return new Refusal(
                "ItemsDirectoryRefused",
                HttpStatus.BAD_REQUEST,
                BODY_REFUSED,
                Map.of("project", name));
    }

    private static Refusal refused(
            final String name, final StoreException.ItemsDirectoryRefused refused) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("project", name);
        parameters.putAll(Problems.itemsDirectory(refused));
        return new Refusal(
                "ItemsDirectoryRefused",
                HttpStatus.BAD_REQUEST,
                String.valueOf(refused.getMessage()),
                parameters);
    }
}
