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
import org.fuin.porelo.planner.app.answers.HeldAnswer;
import org.fuin.porelo.planner.app.answers.HeldChange;
import org.fuin.porelo.planner.app.answers.HeldCommand;
import org.fuin.porelo.planner.app.answers.HeldSave;
import org.fuin.porelo.planner.app.answers.HeldTry;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
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

    /** The most changes one group carries on the board. */
    static final int MOST_CHANGES = 2000;

    /** The most saves one line folds; a further one starts a line of its own. */
    static final int MOST_SAVES = 1000;

    /** The most tries a held save carries, its newest. */
    static final int MOST_TRIES = 1000;

    /**
     * The most UTF-16 units a reason's subject holds, so every reader counts the bound of a subject
     * and of the sentence naming it alike, however it counts characters.
     */
    private static final int MOST_SUBJECT = 256;

    /** The most characters a source's repository holds on the board. */
    private static final int MOST_REPOSITORY = 4096;

    /** The most characters a source's branch holds on the board. */
    private static final int MOST_BRANCH = 255;

    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern SAVE = Pattern.compile(UUID);

    private static final Pattern COMMAND = Pattern.compile(UUID + "/[0-9]{1,3}");

    private static final Pattern FIELD = Pattern.compile("[a-z][A-Za-z]{0,63}");

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
     * its run; answered as a move. Of a line of folded saves the newest is sent, once, and the
     * others are dropped once it is written.
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
            held(name, saves, id);
            final List<String> line = line(name, saves, id);
            final String newest = line.getLast();
            final CommandOutbox.Group group = held(name, saves, newest);
            if (group.earlier()) {
                final Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("save", newest);
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
                if (!saves.runs().resavable(group.story(), newest)) {
                    throw new Refusal(
                            "SaveOfRun",
                            HttpStatus.CONFLICT,
                            "The save is a halted run's; the run's Stop or Start decides it.",
                            Map.of("save", newest));
                }
                board = saves.runs().resave(group.story(), newest);
            } else {
                board =
                        projects.work(name)
                                .alone(
                                        () ->
                                                CommandGroups.saved(
                                                        lost(
                                                                name,
                                                                saves,
                                                                newest,
                                                                () -> saves.resend(newest)),
                                                        store));
            }
            // Written once: the saves folded into its line would write the same again. One the
            // outbox refuses to drop stays held, a line of its own; the write stands.
            dropped(name, saves, line.subList(0, line.size() - 1), true);
            LOG.info(
                    "Resent the held save {} of project {} as revision {}, dropping {} folded"
                            + " into it",
                    LogText.of(newest),
                    LogText.of(name),
                    LogText.of(board.revision().value()),
                    line.size() - 1);
            return new EditSaved(board.revision().value());
        } finally {
            changes.saved(name);
        }
    }

    /**
     * Removes a held save from the project's outbox, writing nothing to the store, with every save
     * folded into its line.
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
            final List<String> line = line(name, saves, id);
            lost(
                    name,
                    saves,
                    id,
                    () -> {
                        saves.drop(id);
                        return id;
                    });
            dropped(name, saves, line.stream().filter(each -> !each.equals(id)).toList(), false);
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
     * refused or held and none of its commands queued or under way, this source's first. Groups
     * that change one field of one item to one value from one run, earlier save, repository and
     * branch fold into one line, told by its newest. A group the board cannot tell as described is
     * left out and logged, never told otherwise.
     *
     * @param project the project's name, for the log
     * @param saves the project's held saves
     * @param board the board they are shown beside, whose values tell what each would change
     * @param readAt when that board was read, or empty when its store tells no time
     * @return at most {@value #MOST_GROUPS} lines
     */
    static List<HeldSave> answers(
            final String project,
            final HeldSaves saves,
            final Board board,
            final Optional<java.time.Instant> readAt) {
        final List<HeldSave> answers = new ArrayList<>();
        int left = 0;
        for (final Line line : lines(project, saves, board, readAt)) {
            if (answers.size() == MOST_GROUPS) {
                left++;
                continue;
            }
            answers.add(line.told());
        }
        if (left > 0) {
            LOG.warn(
                    "Project {} keeps {} held saves more than the board carries",
                    LogText.of(project),
                    left);
        }
        return List.copyOf(answers);
    }

    /**
     * One line of held saves: a group alone, or groups folded as one, oldest first.
     *
     * @param groups the groups
     * @param saves each group as the board tells it alone
     */
    private record Line(List<CommandOutbox.Group> groups, List<HeldSave> saves) {

        /** The line as the board tells it: its newest save, with every save and try of it. */
        HeldSave told() {
            final HeldSave newest = saves.getLast();
            if (saves.size() == 1) {
                return newest;
            }
            final List<CommandOutbox.Try> kept =
                    groups.stream()
                            .flatMap(group -> group.tries().stream())
                            .sorted(java.util.Comparator.comparing(CommandOutbox.Try::at))
                            .toList();
            final List<HeldTry> tries =
                    kept.subList(Math.max(0, kept.size() - MOST_TRIES), kept.size()).stream()
                            .map(HeldSavesController::tried)
                            .toList();
            return new HeldSave(
                    newest.id(),
                    saves.stream().map(HeldSave::id).toList(),
                    newest.reason(),
                    newest.changes(),
                    tries.isEmpty() ? null : tries.getLast().answer(),
                    tries,
                    newest.run(),
                    newest.earlier(),
                    newest.resendable(),
                    newest.repository(),
                    newest.branch(),
                    newest.commands());
        }
    }

    /** What folds held groups into one line. */
    private record Fold(
            String target,
            String field,
            @Nullable String saved,
            String story,
            boolean earlier,
            String repository,
            String branch) {}

    /**
     * The lines of a project's held saves, in the order of each line's oldest group.
     *
     * @param project the project's name, for the log
     * @param saves the project's held saves
     * @param board the board they are shown beside, whose values tell what each would change; null
     *     where no value now is asked for, since the lines are the groups' alone
     * @param readAt when that board was read, or empty when its store tells no time
     * @return the lines
     */
    private static List<Line> lines(
            final String project,
            final HeldSaves saves,
            final @Nullable Board board,
            final Optional<java.time.Instant> readAt) {
        final List<List<CommandOutbox.Group>> groups = new ArrayList<>();
        final List<List<HeldSave>> told = new ArrayList<>();
        final Map<Fold, Integer> folds = new LinkedHashMap<>();
        for (final CommandOutbox.Group group : saves.groups()) {
            if (!stopped(group)) {
                if (group.commands().stream().anyMatch(t -> STOPPED.contains(t.state()))) {
                    // Refused or held beside a command under way: the file is not as the outbox
                    // writes it, so the group is shown neither as held nor as anything else.
                    unshown(project, group, "a command is still queued or under way");
                }
                continue;
            }
            final Optional<HeldSave> answer = answer(project, saves, group, board, readAt);
            if (answer.isEmpty()) {
                continue;
            }
            final Fold fold = fold(group);
            final Integer at = fold == null ? null : folds.get(fold);
            if (at == null || groups.get(at).size() == MOST_SAVES) {
                if (fold != null) {
                    folds.put(fold, groups.size());
                }
                groups.add(new ArrayList<>(List.of(group)));
                told.add(new ArrayList<>(List.of(answer.get())));
            } else {
                groups.get(at).add(group);
                told.get(at).add(answer.get());
            }
        }
        final List<Line> lines = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            lines.add(new Line(List.copyOf(groups.get(i)), List.copyOf(told.get(i))));
        }
        return lines;
    }

    /**
     * What folds a group with others: its one command not yet written, when that command makes
     * exactly one change; null for any other group, which stays a line of its own, since a resend
     * of another group would not write all it holds.
     */
    private static @Nullable Fold fold(final CommandOutbox.Group group) {
        final List<CommandOutbox.Told> open =
                group.commands().stream()
                        .filter(told -> told.state() != CommandOutbox.State.DONE)
                        .toList();
        if (open.size() != 1) {
            return null;
        }
        final List<HeldChange> changes = HeldChanges.of(open.getFirst());
        if (changes.size() != 1) {
            return null;
        }
        final HeldChange change = changes.getFirst();
        return new Fold(
                change.target(),
                change.field(),
                change.saved(),
                group.story(),
                group.earlier(),
                group.source().repository(),
                group.source().branch());
    }

    /**
     * The ids of the line a held save is told in, oldest first, from the groups alone; the save
     * alone when no line holds it.
     */
    private static List<String> line(final String project, final HeldSaves saves, final String id) {
        return lines(project, saves, null, Optional.empty()).stream()
                .map(line -> line.saves().stream().map(HeldSave::id).toList())
                .filter(ids -> ids.contains(id))
                .findFirst()
                .orElse(List.of(id));
    }

    /**
     * Drops the saves folded into a line beside the one resent or dropped; one another call took
     * meanwhile is left to it. The outbox's refusal of a drop is answered, those dropped before it
     * staying dropped; after a resend, whose write stands, it is only logged.
     *
     * @param project the project's name
     * @param saves the project's held saves
     * @param folded the saves to drop
     * @param written whether the line's newest was written, so a refusal is logged, not answered
     */
    private static void dropped(
            final String project,
            final HeldSaves saves,
            final List<String> folded,
            final boolean written) {
        for (final String other : folded) {
            try {
                saves.drop(other);
            } catch (final IllegalStateException taken) {
                LOG.info(
                        "The save {} folded beside another of project {} was taken meanwhile: {}",
                        LogText.of(other),
                        LogText.of(project),
                        LogText.of(String.valueOf(taken.getMessage())));
            } catch (final StoreException refused) {
                if (!written) {
                    throw refused;
                }
                LOG.warn(
                        "The save {} folded beside one written of project {} stays held: {}",
                        LogText.of(other),
                        LogText.of(project),
                        LogText.of(String.valueOf(refused.getMessage())));
            }
        }
    }

    /** Whether the group is refused or held, with no command waiting or under way. */
    private static boolean stopped(final CommandOutbox.Group group) {
        return group.commands().stream().anyMatch(t -> STOPPED.contains(t.state()))
                && group.commands().stream().noneMatch(t -> GOING.contains(t.state()));
    }

    private static Optional<HeldSave> answer(
            final String project,
            final HeldSaves saves,
            final CommandOutbox.Group group,
            final @Nullable Board board,
            final Optional<java.time.Instant> readAt) {
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
        final List<HeldChange> changes = new ArrayList<>();
        for (final CommandOutbox.Told told : group.commands()) {
            if (told.state() != CommandOutbox.State.DONE) {
                // A save kept from another source writes items of another board: no value
                // of the board shown is theirs.
                changes.addAll(
                        board == null
                                ? HeldChanges.of(told)
                                : HeldChanges.of(
                                        told, board, group.earlier() ? Optional.empty() : readAt));
            }
        }
        if (changes.size() > MOST_CHANGES
                || changes.stream()
                        .anyMatch(
                                c ->
                                        !TARGET.matcher(c.target()).matches()
                                                || !FIELD.matcher(c.field()).matches())) {
            unshown(project, group, "what it would change cannot be told as the board describes");
            return Optional.empty();
        }
        final CommandOutbox.Told stopped =
                group.commands().stream()
                        .filter(t -> STOPPED.contains(t.state()))
                        .findFirst()
                        .orElseThrow();
        final WorkRun.@Nullable SaveFailure why = stopped.why();
        final String reason =
                why == null
                        ? HeldReasons.of("", "")
                        : HeldReasons.of(why.name(), clean(why.subject()));
        final List<CommandOutbox.Try> kept = group.tries();
        final List<HeldTry> tries =
                kept.subList(Math.max(0, kept.size() - MOST_TRIES), kept.size()).stream()
                        .map(HeldSavesController::tried)
                        .toList();
        final @Nullable HeldAnswer answer = tries.isEmpty() ? null : tries.getLast().answer();
        final boolean run = !group.story().isEmpty();
        final boolean resendable =
                !group.earlier() && (!run || saves.runs().resavable(group.story(), group.id()));
        return Optional.of(
                new HeldSave(
                        group.id(),
                        List.of(group.id()),
                        reason,
                        changes,
                        answer,
                        tries,
                        run,
                        group.earlier(),
                        resendable,
                        group.earlier() ? repository : null,
                        group.earlier() && !branch.isEmpty() ? branch : null,
                        commands));
    }

    /** A try as the board tells it: the outbox keeps only sound ones, so each fits the board. */
    private static HeldTry tried(final CommandOutbox.Try kept) {
        final CommandOutbox.Answer answer = kept.answer();
        return new HeldTry(
                kept.at().toString(),
                kept.result() == CommandOutbox.State.REFUSED
                        ? HeldTry.ResultEnum.REFUSED
                        : HeldTry.ResultEnum.HELD,
                HeldReasons.of(kept.why().name(), clean(kept.why().subject())),
                answer.equals(CommandOutbox.Answer.NONE)
                        ? null
                        : new HeldAnswer(
                                answer.status(),
                                answer.text(),
                                answer.requestId().isEmpty() ? null : answer.requestId()));
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
        return subject.length() > MOST_SUBJECT
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
