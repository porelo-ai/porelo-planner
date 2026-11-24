package org.fuin.porelo.planner.app;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.app.answers.EditSaved;
import org.fuin.porelo.planner.app.answers.HeldCommand;
import org.fuin.porelo.planner.app.answers.HeldSave;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the saves a project's outbox keeps for the person, as {@code openapi/planner.yaml}
 * describes them: each one on the board, resent or dropped by the owner account.
 */
@RestController
public class HeldSavesController {

    private static final Logger LOG = LoggerFactory.getLogger(HeldSavesController.class);

    /** The most groups the board carries. */
    static final int MOST_GROUPS = 100;

    /** The most commands one group carries on the board. */
    static final int MOST_COMMANDS = 200;

    /** The most characters a reason's subject holds. */
    private static final int MOST_SUBJECT = 256;

    /** The most characters a source's repository holds on the board. */
    private static final int MOST_REPOSITORY = 4096;

    /** The most characters a source's branch holds on the board. */
    private static final int MOST_BRANCH = 255;

    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern SAVE = Pattern.compile(UUID);

    private static final Pattern COMMAND = Pattern.compile(UUID + "/[0-9]{1,3}");

    private static final Pattern TARGET =
            Pattern.compile(
                    "(?!.*\\.\\.)[a-z0-9][a-z0-9._-]{0,99}|ST[0-9]{1,5}|[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?"
                            + "|(?![^#]*\\.\\.)[a-z0-9][a-z0-9._-]{0,99}#[1-9][0-9]{0,8}");

    /** The kinds that make a new item in a repository the outbox names as its source does. */
    private static final Set<HeldCommand.KindEnum> CREATES =
            Set.of(
                    HeldCommand.KindEnum.CREATE_ISSUE,
                    HeldCommand.KindEnum.CREATE_IDEA,
                    HeldCommand.KindEnum.CREATE_STORY);

    /** The board's kind of each of the outbox's kinds. */
    private static final Map<String, HeldCommand.KindEnum> KINDS =
            Map.ofEntries(
                    Map.entry("write", HeldCommand.KindEnum.WRITE),
                    Map.entry("fields", HeldCommand.KindEnum.SET_FIELDS),
                    Map.entry("text", HeldCommand.KindEnum.SET_TEXT),
                    Map.entry("blockers", HeldCommand.KindEnum.SET_BLOCKERS),
                    Map.entry("storyIssues", HeldCommand.KindEnum.SET_STORY_ISSUES),
                    Map.entry("labels", HeldCommand.KindEnum.CORRECT_LABELS),
                    Map.entry("comment", HeldCommand.KindEnum.COMMENT),
                    Map.entry("createIssue", HeldCommand.KindEnum.CREATE_ISSUE),
                    Map.entry("createIdea", HeldCommand.KindEnum.CREATE_IDEA),
                    Map.entry("createStory", HeldCommand.KindEnum.CREATE_STORY),
                    Map.entry("addUnlisted", HeldCommand.KindEnum.ADD_UNLISTED));

    private static final Set<CommandOutbox.State> STOPPED =
            EnumSet.of(CommandOutbox.State.REFUSED, CommandOutbox.State.HELD);

    private static final Set<CommandOutbox.State> GOING =
            EnumSet.of(CommandOutbox.State.QUEUED, CommandOutbox.State.IN_FLIGHT);

    private final Projects projects;

    private final ChangesRegistry changes;

    private final HeldSavesOf held;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a resend or a drop
     * @param held gives each project the saves its outbox keeps
     */
    public HeldSavesController(
            final Projects projects, final ChangesRegistry changes, final HeldSavesOf held) {
        this.projects = projects;
        this.changes = changes;
        this.held = held;
    }

    /**
     * Sends a resendable held save again: a window's through the project's runner, a run's through
     * its run; answered as a move.
     *
     * @param project the project's name
     * @param save the held save's id
     * @return the board's revision once the group is written
     */
    @PostMapping("/projects/{project}/saves/{save}/resend")
    public EditSaved resendSave(
            @PathVariable("project") final String project,
            @PathVariable("save") final String save) {
        final String name = BoardController.name(project);
        final String id = saveId(name, save);
        final PlanningStore store = projects.store(name);
        final HeldSaves saves = held.of(name);
        try {
            final CommandOutbox.Group group = held(name, saves, id);
            if (group.earlier()) {
                final Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("save", id);
                parameters.put("repository", group.source().repository());
                if (!group.source().branch().isEmpty()) {
                    parameters.put("branch", group.source().branch());
                }
                throw new Refusal(
                        "SaveFromElsewhere",
                        HttpStatus.CONFLICT,
                        "The save was kept from another repository or branch.",
                        parameters);
            }
            final Board board;
            if (!group.story().isEmpty()) {
                if (!saves.runs().resavable(group.story(), id)) {
                    throw new Refusal(
                            "SaveOfRun",
                            HttpStatus.CONFLICT,
                            "The save is a halted run's; the run's Stop or Start decides it.",
                            Map.of("save", id));
                }
                board = saves.runs().resave(group.story(), id);
            } else {
                board =
                        projects.work(name)
                                .alone(
                                        () ->
                                                CommandGroups.saved(
                                                        lost(
                                                                name,
                                                                saves,
                                                                id,
                                                                () -> saves.resend(id)),
                                                        store));
            }
            LOG.info(
                    "Resent the held save {} of project {} as revision {}",
                    LogText.of(id),
                    LogText.of(name),
                    LogText.of(board.revision().value()));
            return new EditSaved(board.revision().value());
        } finally {
            changes.saved(name);
        }
    }

    /**
     * Removes a held save from the project's outbox, writing nothing to the store.
     *
     * @param project the project's name
     * @param save the held save's id
     * @return no content
     */
    @DeleteMapping("/projects/{project}/saves/{save}")
    public ResponseEntity<Void> dropSave(
            @PathVariable("project") final String project,
            @PathVariable("save") final String save) {
        final String name = BoardController.name(project);
        final String id = saveId(name, save);
        projects.store(name);
        final HeldSaves saves = held.of(name);
        try {
            final CommandOutbox.Group group = held(name, saves, id);
            lost(
                    name,
                    saves,
                    id,
                    () -> {
                        saves.drop(id);
                        return id;
                    });
            LOG.info(
                    "Dropped the held save {} of project {} with the targets {}",
                    LogText.of(id),
                    LogText.of(name),
                    LogText.of(
                            group.commands().stream()
                                    .map(CommandOutbox.Told::target)
                                    .collect(Collectors.joining(", "))));
            return ResponseEntity.noContent().build();
        } finally {
            changes.saved(name);
        }
    }

    /**
     * The saves a project's outbox keeps for the person, as the board carries them: each group
     * refused or held and none of its commands queued or under way, this source's first. A group
     * the board cannot tell as described is left out and logged, never told otherwise.
     *
     * @param project the project's name, for the log
     * @param saves the project's held saves
     * @return at most {@value #MOST_GROUPS} groups
     */
    static List<HeldSave> answers(final String project, final HeldSaves saves) {
        final List<HeldSave> answers = new ArrayList<>();
        int left = 0;
        for (final CommandOutbox.Group group : saves.groups()) {
            if (!stopped(group)) {
                if (group.commands().stream().anyMatch(t -> STOPPED.contains(t.state()))) {
                    // Refused or held beside a command under way: the file is not as the outbox
                    // writes it, so the group is shown neither as held nor as anything else.
                    unshown(project, group, "a command is still queued or under way");
                }
                continue;
            }
            final Optional<HeldSave> answer = answer(project, saves, group);
            if (answer.isEmpty()) {
                continue;
            }
            if (answers.size() == MOST_GROUPS) {
                left++;
                continue;
            }
            answers.add(answer.get());
        }
        if (left > 0) {
            LOG.warn(
                    "Project {} keeps {} held saves more than the board carries",
                    LogText.of(project),
                    left);
        }
        return List.copyOf(answers);
    }

    /** Whether the group is refused or held, with no command waiting or under way. */
    private static boolean stopped(final CommandOutbox.Group group) {
        return group.commands().stream().anyMatch(t -> STOPPED.contains(t.state()))
                && group.commands().stream().noneMatch(t -> GOING.contains(t.state()));
    }

    private static Optional<HeldSave> answer(
            final String project, final HeldSaves saves, final CommandOutbox.Group group) {
        if (!SAVE.matcher(group.id()).matches()) {
            unshown(project, group, "its id is no save's id");
            return Optional.empty();
        }
        if (group.commands().size() > MOST_COMMANDS) {
            unshown(project, group, "it holds more commands than the board carries");
            return Optional.empty();
        }
        final String repository = group.source().repository();
        final String branch = group.source().branch();
        if (group.earlier()
                && (repository.length() > MOST_REPOSITORY
                        || branch.length() > MOST_BRANCH
                        || repository.codePoints().anyMatch(Writes.Reason::unshown)
                        || branch.codePoints().anyMatch(Writes.Reason::unshown))) {
            unshown(project, group, "its source cannot be told as the board describes");
            return Optional.empty();
        }
        final List<HeldCommand> commands = new ArrayList<>();
        for (final CommandOutbox.Told told : group.commands()) {
            final Optional<HeldCommand> command = command(told);
            if (command.isEmpty()) {
                unshown(project, group, "a command of it cannot be told as the board describes");
                return Optional.empty();
            }
            commands.add(command.get());
        }
        final boolean run = !group.story().isEmpty();
        final boolean resendable =
                !group.earlier() && (!run || saves.runs().resavable(group.story(), group.id()));
        return Optional.of(
                new HeldSave(
                        group.id(),
                        run,
                        group.earlier(),
                        resendable,
                        group.earlier() ? repository : null,
                        group.earlier() && !branch.isEmpty() ? branch : null,
                        commands));
    }

    private static Optional<HeldCommand> command(final CommandOutbox.Told told) {
        final HeldCommand.KindEnum kind = KINDS.get(told.kind());
        if (kind == null) {
            return Optional.empty();
        }
        final String target = CREATES.contains(kind) ? named(told.target()) : told.target();
        if (!COMMAND.matcher(told.id()).matches() || !TARGET.matcher(target).matches()) {
            return Optional.empty();
        }
        if (told.state() == CommandOutbox.State.IN_FLIGHT) {
            // A command under way is never told as one at rest.
            return Optional.empty();
        }
        final HeldCommand.StateEnum state = HeldCommand.StateEnum.fromValue(told.state().name());
        final WorkRun.@Nullable SaveFailure why = told.why();
        final HeldCommand.ReasonEnum reason;
        try {
            reason =
                    why == null
                            ? HeldCommand.ReasonEnum.NONE
                            : HeldCommand.ReasonEnum.fromValue(why.name());
        } catch (final IllegalArgumentException unknown) {
            return Optional.empty();
        }
        final String subject = why == null ? "" : why.subject();
        return Optional.of(new HeldCommand(told.id(), kind, target, state, reason, clean(subject)));
    }

    /**
     * A repository by its name, the last part of a clone URL without {@code .git}: the outbox keeps
     * a new item's repository as its source names it, the board by the name alone.
     */
    private static String named(final String repository) {
        final String last =
                repository.substring(
                        Math.max(repository.lastIndexOf('/'), repository.lastIndexOf(':')) + 1);
        return last.endsWith(".git") ? last.substring(0, last.length() - ".git".length()) : last;
    }

    /** The subject when its words are bounded and shown; empty otherwise. */
    private static String clean(final String subject) {
        return subject.codePointCount(0, subject.length()) > MOST_SUBJECT
                        || subject.codePoints().anyMatch(Writes.Reason::unshown)
                ? ""
                : subject;
    }

    private static void unshown(
            final String project, final CommandOutbox.Group group, final String why) {
        LOG.warn(
                "A held save of project {} is left off the board: {} ({})",
                LogText.of(project),
                why,
                SAVE.matcher(group.id()).matches() ? group.id() : "no save's id");
    }

    /** The group of the id, when it is refused or held. */
    private static CommandOutbox.Group held(
            final String project, final HeldSaves saves, final String id) {
        final CommandOutbox.Group group =
                saves.groups().stream()
                        .filter(each -> each.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> noSuchSave(project, id));
        if (!stopped(group)) {
            throw notHeld(id);
        }
        return group;
    }

    /**
     * Runs a call the outbox may refuse for a group another call took meanwhile: refused as gone
     * when the outbox holds it no longer, as not held otherwise.
     */
    private static <T> T lost(
            final String project,
            final HeldSaves saves,
            final String id,
            final java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (final IllegalStateException taken) {
            final boolean holds = saves.groups().stream().anyMatch(each -> each.id().equals(id));
            LOG.info(
                    "The save {} of project {} was taken meanwhile: {}",
                    LogText.of(id),
                    LogText.of(project),
                    LogText.of(String.valueOf(taken.getMessage())));
            throw holds ? notHeld(id) : noSuchSave(project, id);
        }
    }

    private static Refusal noSuchSave(final String project, final String id) {
        return new Refusal(
                "NoSuchSave",
                HttpStatus.NOT_FOUND,
                "The project holds no such save.",
                Map.of("project", project, "save", id));
    }

    private static Refusal notHeld(final String id) {
        return new Refusal(
                "SaveNotHeld",
                HttpStatus.CONFLICT,
                "The save is not held; it runs or was sent again.",
                Map.of("save", id));
    }

    private static String saveId(final String project, final String save) {
        if (!SAVE.matcher(save).matches()) {
            throw Refusal.invalidName("The save id is invalid.", Map.of("project", project));
        }
        return save;
    }
}
