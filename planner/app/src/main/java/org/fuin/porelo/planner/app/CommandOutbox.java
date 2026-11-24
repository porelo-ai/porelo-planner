package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandShown;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.Written;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One project's commands on disk, in the planner's state directory beside the work record: every
 * group a save makes is written here before its first call to the store, and every change of a
 * command's state is written before the call it concerns. The file keeps no rule: per command it
 * keeps the versions the command was judged on, of its target and of every item it read, and the
 * command's changes as data.
 *
 * <p>At open, a command the planner stopped in the middle of is settled against the board read
 * then: the board showing every part it writes means done, its target still at the version judged
 * means queued again, anything else holds it for the owner account. A command whose parts the board
 * cannot show is held, never told done and never sent twice. A file of another repository or branch
 * is never run again: its open commands are kept held under that source.
 */
final class CommandOutbox implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CommandOutbox.class);

    /** The file's name in the project's state directory. */
    static final String FILE = "outbox.json";

    /** The form of the file this planner writes and reads. */
    static final int FORMAT = 1;

    /** The most bytes the file holds. */
    static final int MOST = WorkRecord.MOST_RECORD;

    /** The room a queued group leaves free, so starting, ending and holding a command fit. */
    static final int RESERVE = 1024 * 1024;

    /** The most characters a reason's subject holds. */
    static final int MOST_SUBJECT = 256;

    /** The kinds a reason is named by: the store's refusals and a failure that is none of them. */
    static final Set<String> REASONS =
            Set.of(
                    "NoSuchStory",
                    "NoSuchIssue",
                    "WorkflowMissing",
                    "CredentialRefused",
                    "NoForgeAccess",
                    "Unreachable",
                    "Unreadable",
                    "ItemsDirectoryRefused",
                    "StaleRevision",
                    "Changed",
                    "BasisIncomplete",
                    "NotSavable",
                    "InStory",
                    "Busy",
                    "PartlySaved",
                    "LabelsNotKept",
                    "NotOffered");

    /** Where a command stands. */
    enum State {
        /** Waiting for the commands before it. */
        QUEUED,
        /** Its call to the store began and has not ended. */
        IN_FLIGHT,
        /** Written. */
        DONE,
        /** Refused by the store before it wrote anything. */
        REFUSED,
        /** Held for the owner account: it may stand in part, or it waits behind one that does. */
        HELD
    }

    /**
     * The repository and branch a file's commands are written to.
     *
     * @param repository the project's repository
     * @param branch the branch, empty for none
     */
    record Source(String repository, String branch) {}

    /**
     * Why a command is refused or held, in the store's refusal kinds and their bounded words.
     *
     * @param name the kind, empty for none
     * @param subject what it is about
     * @param right the right a forge named as missing, empty for none
     * @param places the places of a conflict, by their fixed names; empty for any other reason
     */
    record Why(String name, String subject, String right, List<String> places) {

        static final Why NONE = new Why("", "", "");

        /** Copies the places. */
        Why {
            places = List.copyOf(places);
        }

        /** A reason without places. */
        Why(final String name, final String subject, final String right) {
            this(name, subject, right, List.of());
        }

        /** Keeps a store's refusal as it can be told again; any other failure as NotOffered. */
        static Why of(final Throwable cause) {
            final Why why =
                    cause instanceof StoreException refusal
                            ? new Why(
                                    refusal.getClass().getSimpleName(),
                                    refusal.subject(),
                                    refusal instanceof StoreException.NoForgeAccess access
                                                    && access.right() != null
                                            ? access.right()
                                            : "")
                            : new Why("NotOffered", "", "");
            return why.sound() ? why : new Why(why.name(), "", "");
        }

        /** Whether it is one the file may keep: a known kind with bounded, shown words. */
        boolean sound() {
            if (!REASONS.contains(name)
                    || !places.isEmpty() && !name.equals("Changed")
                    || places.size() > Written.Conflict.MOST
                    || places.stream().anyMatch(p -> !Written.Conflict.PLACE.matcher(p).matches())
                    || subject.codePointCount(0, subject.length()) > MOST_SUBJECT
                    || subject.codePoints().anyMatch(Writes.Reason::unshown)) {
                return false;
            }
            if (name.equals("NoForgeAccess")) {
                try {
                    return new StoreException.NoForgeAccess(subject, right.isEmpty() ? null : right)
                            .subject()
                            .equals(subject);
                } catch (final IllegalArgumentException refused) {
                    return false;
                }
            }
            return right.isEmpty();
        }

        /** The refusal as a run keeps it. */
        WorkRun.SaveFailure failure() {
            return new WorkRun.SaveFailure(
                    name, subject, List.of(), right.isEmpty() ? null : right);
        }
    }

    /**
     * A new issue as data, its unset values left out.
     *
     * @param repository the repository
     * @param title the title
     * @param mustBeTrue what must be true
     * @param worker who works it
     * @param labels its topic labels
     * @param set the values given: size, task, priority, story, state
     */
    record IssueData(
            String repository,
            String title,
            String mustBeTrue,
            String worker,
            List<String> labels,
            Map<String, String> set) {}

    /**
     * A label call as data, its unset values left out.
     *
     * @param action the action
     * @param label the label
     * @param set the values given: color, description
     */
    record CallData(String action, String label, Map<String, String> set) {}

    /**
     * A command's changes as data; the command's kind says which members it fills, the others stay
     * empty.
     *
     * @param issueStates a write's issue states
     * @param storyStates a write's story states
     * @param workers a write's workers
     * @param priorities a write's story priorities
     * @param issueReasons a write's issue reasons
     * @param storyReasons a write's story reasons
     * @param values the named values: fields, a text's kind and title, a story's own words
     * @param parts the parts a text writes
     * @param removed the parts a text removes
     * @param labels the labels a text sets, when {@code labelsSet}
     * @param labelsSet whether a text sets labels
     * @param ids the blockers, the story's issues, or the issues a story is made from
     * @param calls the label calls
     * @param text a comment's text
     * @param issues the issues created
     */
    record Changes(
            Map<String, String> issueStates,
            Map<String, String> storyStates,
            Map<String, String> workers,
            Map<String, String> priorities,
            Map<String, Writes.Reason> issueReasons,
            Map<String, Writes.Reason> storyReasons,
            Map<String, String> values,
            Map<String, String> parts,
            List<String> removed,
            List<String> labels,
            boolean labelsSet,
            List<String> ids,
            List<CallData> calls,
            String text,
            List<IssueData> issues) {

        static final Changes NONE =
                new Changes(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        Map.of(), List.of(), List.of(), false, List.of(), List.of(), "", List.of());
    }

    /**
     * One command in the file.
     *
     * @param group its group's id
     * @param id its own id
     * @param kind its kind
     * @param target the issue, story or repository it writes
     * @param judged the versions it was judged on, by item id
     * @param changes its changes
     * @param state where it stands
     * @param why why it is refused or held; {@link Why#NONE} otherwise
     * @param resumed whether it was begun before and may stand in part, so a later refusal of it
     *     holds it instead of telling it unwritten
     * @param story the story whose run saves its group, empty for a window's save; a run's group is
     *     repaired by its run, never sent again by the owner account
     */
    record Entry(
            String group,
            String id,
            String kind,
            String target,
            Map<String, String> judged,
            Changes changes,
            State state,
            Why why,
            boolean resumed,
            String story) {

        Entry to(final State next, final Why because) {
            return new Entry(
                    group, id, kind, target, judged, changes, next, because, resumed, story);
        }

        Entry resumed(final boolean again) {
            return new Entry(group, id, kind, target, judged, changes, state, why, again, story);
        }
    }

    /**
     * Commands kept from another repository or branch, never run again.
     *
     * @param source where they were to be written
     * @param entries the commands, whole groups in order
     */
    record Earlier(Source source, List<Entry> entries) {}

    /**
     * The file.
     *
     * @param format its form
     * @param project the project
     * @param source where its commands are written
     * @param entries the commands, whole groups in order
     * @param earlier commands kept from other sources
     */
    record Document(
            int format,
            String project,
            Source source,
            List<Entry> entries,
            List<Earlier> earlier) {}

    /**
     * A command as the outbox tells it.
     *
     * @param id its id
     * @param kind its kind
     * @param target what it writes
     * @param state where it stands
     * @param why why it is refused or held, or null
     * @param places the places of a conflict, for the owner account; empty otherwise
     */
    record Told(
            String id,
            String kind,
            String target,
            State state,
            WorkRun.@Nullable SaveFailure why,
            List<String> places) {}

    /**
     * A group not written whole, every command of it told.
     *
     * @param source where it is written
     * @param id the group's id
     * @param commands its commands in order, those done included
     * @param earlier whether it is kept from another source and never run
     * @param story the story whose run saves it, empty for a window's save
     */
    record Group(Source source, String id, List<Told> commands, boolean earlier, String story) {}

    private final String project;
    private final Source source;
    private final Path file;
    private final DurableFile durable;
    private final LocalPaths paths;
    private List<Entry> entries = List.of();
    private List<Earlier> earlier = List.of();
    private final Map<String, Command> live = new HashMap<>();
    private boolean opened;
    private boolean closed;

    /**
     * The repository and branch this outbox's own groups are written to.
     *
     * @return the source
     */
    Source source() {
        return source;
    }

    /**
     * Keeps one project's commands in its state directory.
     *
     * @param environment the planner's environment, for the words of a local path
     * @param directory the project's state directory, where the work record lies
     * @param project the project
     * @param repository its repository
     * @param branch its branch, or null for none
     * @param files the platform's private files
     * @param limits the planner's factors
     */
    CommandOutbox(
            final Map<String, String> environment,
            final Path directory,
            final String project,
            final String repository,
            final @Nullable String branch,
            final PrivateFiles files,
            final LimitFactors limits) {
        this(
                environment,
                directory,
                project,
                repository,
                branch,
                files,
                new DurableFile(files, limits));
    }

    /** Keeps the commands with the given durable file. */
    CommandOutbox(
            final Map<String, String> environment,
            final Path directory,
            final String project,
            final String repository,
            final @Nullable String branch,
            final PrivateFiles files,
            final DurableFile durable) {
        this.project = project;
        this.source = new Source(repository, branch == null ? "" : branch);
        this.file = directory.resolve(FILE);
        this.durable = durable;
        this.paths = LocalPaths.of(environment, files.windows());
    }

    /**
     * Reads the file and settles every command the planner stopped in the middle of against the
     * board read now; writes the result before it answers.
     *
     * @param board reads the board from the store, called only when a command was begun
     * @return each command settled, by id, with the state it took
     * @throws WorkRefusal for a file torn, forged, of another project, of a newer form, too large
     *     or not the account's own, or one that cannot be written
     */
    synchronized Map<String, State> open(final Supplier<Board> board) {
        open();
        if (opened) {
            throw new IllegalStateException("The outbox is open already.");
        }
        final byte[] bytes;
        try {
            durable.removeLeftovers(file);
            bytes = durable.read(file, MOST);
        } catch (final DurableFile.TooLarge large) {
            throw unavailable("The outbox is larger than 16 MB.");
        } catch (final IOException failed) {
            throw unavailable("The outbox cannot be read", failed);
        }
        final Document read = bytes == null ? null : document(bytes);
        List<Entry> now = read == null ? List.of() : read.entries();
        List<Earlier> kept = read == null ? List.of() : read.earlier();
        if (read != null && !read.source().equals(source)) {
            kept = keptAside(kept, read.source(), now);
            now = List.of();
        }
        final Map<String, State> settled = new LinkedHashMap<>();
        now = settledAtOpen(now, board, settled);
        final Document next = new Document(FORMAT, project, source, pruned(now), prunedKept(kept));
        if (read == null
                ? !next.entries().isEmpty() || !next.earlier().isEmpty()
                : !next.equals(read)) {
            write(next, MOST, () -> {});
        }
        entries = next.entries();
        earlier = next.earlier();
        opened = true;
        return settled;
    }

    /**
     * Queues a group, every command waiting, and writes it before any call of it.
     *
     * @param group the group
     * @throws IllegalArgumentException for a group or command id the outbox holds already
     * @throws WorkRefusal when the file has no room for it, kept free of a reserve, or cannot be
     *     written: then nothing of the group is queued; or when the file was written but its
     *     directory not synced: then the group is queued and may run after a restart
     */
    synchronized void enqueue(final CommandGroup group) {
        running();
        final Set<String> held = ids();
        final Set<String> groups = new HashSet<>();
        entries.forEach(entry -> groups.add(entry.group()));
        earlier.forEach(each -> each.entries().forEach(entry -> groups.add(entry.group())));
        if (groups.contains(group.id())) {
            throw new IllegalArgumentException("The outbox holds the group " + group.id() + ".");
        }
        final List<Entry> next = new ArrayList<>(entries);
        for (final Command command : group.commands()) {
            if (held.contains(command.id())) {
                throw new IllegalArgumentException(
                        "The outbox holds the command " + command.id() + ".");
            }
            next.add(entry(group.id(), command, group.story()));
        }
        final Document document = new Document(FORMAT, project, source, next, earlier);
        if (bytes(worstHeld(document)).length > MOST - RESERVE) {
            throw unavailable("The outbox has no room for this save.");
        }
        write(
                document,
                MOST,
                () -> {
                    entries = List.copyOf(next);
                    group.commands().forEach(command -> live.put(command.id(), command));
                });
    }

    /**
     * Marks a command begun, written before the store is called for it.
     *
     * @param id the command's id
     * @throws IllegalStateException for a command not waiting, or one whose group holds a command
     *     before it not written
     */
    synchronized void begin(final String id) {
        running();
        final int at = at(id);
        final Entry entry = entries.get(at);
        if (entry.state() != State.QUEUED) {
            throw new IllegalStateException("The command " + id + " is not waiting.");
        }
        for (int before = at - 1;
                before >= 0 && entries.get(before).group().equals(entry.group());
                before--) {
            if (entries.get(before).state() != State.DONE) {
                throw new IllegalStateException(
                        "The command " + id + " waits on one before it in its group.");
            }
        }
        replace(at, List.of(entry.to(State.IN_FLIGHT, Why.NONE)), false);
    }

    /**
     * Marks a command written.
     *
     * @param id the command's id
     */
    synchronized void done(final String id) {
        running();
        final int at = inFlight(id);
        replace(at, List.of(entries.get(at).to(State.DONE, Why.NONE)), true);
    }

    /**
     * Ends a command the store's call failed for. Refused before writing, it is refused; one that
     * may have written is done when the board shows every part of it, and held otherwise. The
     * commands after it in its group are held.
     *
     * @param id the command's id
     * @param cause what the call threw
     * @param mayHaveWritten whether the command may stand in part
     * @param board the board read after the failure, or null when none was read
     * @return the state the command took
     */
    synchronized State refused(
            final String id,
            final Throwable cause,
            final boolean mayHaveWritten,
            final @Nullable Board board) {
        running();
        final int at = inFlight(id);
        final Entry entry = entries.get(at);
        final Why why = Why.of(cause);
        // A command begun before this run may stand in part, whatever refuses it now.
        final boolean may = mayHaveWritten || entry.resumed();
        if (may && board != null && shows(command(entry), board)) {
            replace(at, List.of(entry.to(State.DONE, Why.NONE)), true);
            return State.DONE;
        }
        return ended(at, may ? State.HELD : State.REFUSED, why);
    }

    /**
     * Ends a command whose item moved at places it writes or read: nothing of it was written, so it
     * is refused for the owner account with the places, and the commands after it are held. A
     * command begun before this run may stand in part, so it is held instead.
     *
     * @param id the command's id
     * @param places the places, by their fixed names
     */
    synchronized void conflict(final String id, final List<String> places) {
        running();
        final int at = inFlight(id);
        final Entry entry = entries.get(at);
        ended(
                at,
                entry.resumed() ? State.HELD : State.REFUSED,
                new Why("Changed", subject(entry.target()), "", places));
    }

    /**
     * Holds a group for the owner account: each command of it waiting or begun is held with the
     * reason given, one begun as may stand in part; those done stay done.
     *
     * @param group the group's id
     * @param why why it is held
     */
    synchronized void hold(final String group, final Throwable why) {
        running();
        final List<Entry> of = group(group);
        final Why because = Why.of(why);
        final List<Entry> held = new ArrayList<>();
        for (final Entry entry : of) {
            held.add(
                    entry.state() == State.QUEUED || entry.state() == State.IN_FLIGHT
                            ? entry.to(State.HELD, because)
                                    .resumed(entry.resumed() || entry.state() == State.IN_FLIGHT)
                            : entry);
        }
        replace(at(of.getFirst().id()), held, false);
    }

    /**
     * The story whose run saves a group of this source.
     *
     * @param group the group's id
     * @return the story's id, empty for a window's save
     */
    synchronized String story(final String group) {
        return group(group).getFirst().story();
    }

    /**
     * Puts a begun command back to wait, when its call ended before writing anything, such as when
     * the store was busy: it is taken again, never refused.
     *
     * @param id the command's id
     */
    synchronized void requeue(final String id) {
        running();
        final int at = inFlight(id);
        replace(at, List.of(entries.get(at).to(State.QUEUED, Why.NONE)), false);
    }

    /**
     * Removes a group none of whose commands was begun, such as one refused at its judgment, which
     * the caller is answered for and which is never run.
     *
     * @param group the group's id
     */
    synchronized void unqueue(final String group) {
        running();
        final List<Entry> of = group(group);
        if (of.stream().anyMatch(e -> e.state() != State.QUEUED || e.resumed())) {
            throw new IllegalStateException("The group " + group + " was begun.");
        }
        removed(group, earlier);
    }

    /**
     * Tells whether a command was begun before this run, so it may stand already.
     *
     * @param id the command's id
     * @return true when it was begun before
     */
    synchronized boolean resumed(final String id) {
        return entries.get(at(id)).resumed();
    }

    /**
     * Tells whether none of a group's commands was begun, in this run or before.
     *
     * @param group the group's id
     * @return true when every command waits and none was begun before
     */
    synchronized boolean fresh(final String group) {
        return group(group).stream().allMatch(e -> e.state() == State.QUEUED && !e.resumed());
    }

    /**
     * Tells whether this source holds a run's group the store refused before it wrote anything:
     * none of its commands done, its first refused and the rest held unbegun behind it.
     *
     * @param group the group's id
     * @param story the story whose run saved it
     * @return true for such a group of that story
     */
    synchronized boolean refusedUnwritten(final String group, final String story) {
        final List<Entry> of = entries.stream().filter(e -> e.group().equals(group)).toList();
        if (of.isEmpty() || !of.getFirst().story().equals(story)) {
            return false;
        }
        if (of.getFirst().state() != State.REFUSED || of.getFirst().resumed()) {
            return false;
        }
        return of.stream().skip(1).allMatch(e -> e.state() == State.HELD && !e.resumed());
    }

    /**
     * Drops a group the owner account gives up: only one with a command refused or held, and none
     * waiting or begun.
     *
     * @param group the group's id
     */
    synchronized void drop(final String group) {
        running();
        final List<Entry> of = all(group);
        if (of.stream().noneMatch(e -> e.state() == State.REFUSED || e.state() == State.HELD)
                || of.stream()
                        .anyMatch(e -> e.state() == State.QUEUED || e.state() == State.IN_FLIGHT)) {
            throw new IllegalStateException("The group " + group + " is not held.");
        }
        final List<Earlier> kept = new ArrayList<>();
        for (final Earlier each : earlier) {
            final List<Entry> left =
                    each.entries().stream().filter(e -> !e.group().equals(group)).toList();
            if (!left.isEmpty()) {
                kept.add(new Earlier(each.source(), left));
            }
        }
        removed(group, kept);
        LOG.info(
                "The owner account dropped the group {} of project {}",
                LogText.of(group),
                LogText.of(project));
    }

    /**
     * Sends a held group again: its refused and held commands wait again, each with the versions it
     * was judged on; one held may stand in part from before.
     *
     * @param group the group's id
     * @throws IllegalStateException for a group not held, or one kept from another source, which is
     *     named
     */
    synchronized void resend(final String group) {
        running();
        for (final Earlier each : earlier) {
            if (each.entries().stream().anyMatch(e -> e.group().equals(group))) {
                throw new IllegalStateException(
                        "The group "
                                + group
                                + " was queued for "
                                + each.source().repository()
                                + (each.source().branch().isEmpty()
                                        ? ""
                                        : " on " + each.source().branch())
                                + ", not this source.");
            }
        }
        final List<Entry> of = group(group);
        if (!of.getFirst().story().isEmpty()) {
            throw new IllegalStateException(
                    "The group "
                            + group
                            + " is saved by the run of "
                            + of.getFirst().story()
                            + ", which repairs it after its halt.");
        }
        if (of.stream().noneMatch(e -> e.state() == State.REFUSED || e.state() == State.HELD)) {
            throw new IllegalStateException("The group " + group + " is not held.");
        }
        final int at = at(of.getFirst().id());
        final List<Entry> again = new ArrayList<>();
        for (final Entry entry : of) {
            again.add(
                    entry.state() == State.DONE
                            ? entry
                            : entry.to(State.QUEUED, Why.NONE)
                                    .resumed(entry.resumed() || entry.state() == State.HELD));
        }
        replace(at, again, false);
    }

    /**
     * The groups of this source whose next command waits, in order.
     *
     * @return their ids
     */
    synchronized List<String> waiting() {
        final List<String> ids = new ArrayList<>();
        String group = null;
        for (final Entry entry : entries) {
            if (entry.group().equals(group) || entry.state() == State.DONE) {
                continue;
            }
            group = entry.group();
            if (entry.state() == State.QUEUED) {
                ids.add(group);
            }
        }
        return ids;
    }

    /**
     * A group of this source with every command told.
     *
     * @param group the group's id
     * @return its commands in order
     */
    synchronized List<Told> told(final String group) {
        return groups(source, group(group), false).getFirst().commands();
    }

    private List<Entry> group(final String group) {
        final List<Entry> of = entries.stream().filter(e -> e.group().equals(group)).toList();
        if (of.isEmpty()) {
            throw new IllegalStateException("The outbox holds no group " + group + " to run.");
        }
        return of;
    }

    private List<Entry> all(final String group) {
        final List<Entry> of = new ArrayList<>();
        entries.stream().filter(e -> e.group().equals(group)).forEach(of::add);
        earlier.forEach(
                each ->
                        each.entries().stream()
                                .filter(e -> e.group().equals(group))
                                .forEach(of::add));
        if (of.isEmpty()) {
            throw new IllegalStateException("The outbox holds no group " + group + ".");
        }
        return of;
    }

    /** Writes the file without the group, then forgets its commands in memory. */
    private void removed(final String group, final List<Earlier> kept) {
        final List<Entry> left = entries.stream().filter(e -> !e.group().equals(group)).toList();
        final List<Entry> gone = entries.stream().filter(e -> e.group().equals(group)).toList();
        write(
                new Document(FORMAT, project, source, left, kept),
                MOST,
                () -> {
                    gone.forEach(e -> live.remove(e.id()));
                    entries = left;
                    earlier = kept;
                });
    }

    /** Ends the command at the index in the state given, and holds the rest of its group. */
    private State ended(final int at, final State state, final Why why) {
        final Entry entry = entries.get(at);
        final List<Entry> ended = new ArrayList<>();
        ended.add(entry.to(state, why));
        for (int after = at + 1;
                after < entries.size() && entries.get(after).group().equals(entry.group());
                after++) {
            ended.add(entries.get(after).to(State.HELD, why));
        }
        replace(at, ended, false);
        return state;
    }

    /**
     * The command to send: the one queued in this run with its own rules, or one read from the
     * file, checked only by the versions it was judged on, each read again from the store.
     *
     * @param id the command's id
     * @return the command
     */
    synchronized Command command(final String id) {
        final Command kept = live.get(id);
        return kept != null ? kept : command(entries.get(at(id)));
    }

    /**
     * Every group not written whole, this source's first in order, then those kept from others.
     *
     * @return the groups, each with all of its commands
     */
    synchronized List<Group> pending() {
        final List<Group> groups = new ArrayList<>(groups(source, entries, false));
        earlier.forEach(each -> groups.addAll(groups(each.source(), each.entries(), true)));
        return List.copyOf(groups);
    }

    /** Refuses every later change, so a runner left from before writes nothing beside a new one. */
    @Override
    public synchronized void close() {
        closed = true;
    }

    // --- reading and checking the file

    private Document document(final byte[] bytes) {
        final JsonNode tree;
        try {
            tree = WorkRecord.JSON.readTree(bytes);
        } catch (final JacksonException invalid) {
            throw unavailable("The outbox is not readable JSON.");
        }
        if (tree == null || !tree.isObject() || holdsNull(tree)) {
            throw unavailable("The outbox does not have its form.");
        }
        final JsonNode format = tree.get("format");
        if (format == null || !format.isInt() || format.intValue() < 1) {
            throw unavailable("The outbox does not have its form.");
        }
        if (format.intValue() > FORMAT) {
            throw unavailable("The outbox was written by a newer planner.");
        }
        final JsonNode named = tree.get("project");
        if (named == null || !named.isString() || !named.stringValue().equals(project)) {
            throw unavailable("The outbox is another project's.");
        }
        final Document document;
        try {
            document = WorkRecord.JSON.treeToValue(tree, Document.class);
        } catch (final JacksonException | IllegalArgumentException invalid) {
            throw unavailable("The outbox does not have its form.");
        }
        try {
            checked(document);
        } catch (final RuntimeException invalid) {
            throw unavailable("The outbox has a command that fails its check.");
        }
        return document;
    }

    private static boolean holdsNull(final JsonNode node) {
        if (node.isNull()) {
            return true;
        }
        for (final JsonNode each : node.values()) {
            if (holdsNull(each)) {
                return true;
            }
        }
        return false;
    }

    /** Checks every command and every group's order of states; throws for the first fault. */
    private static void checked(final Document document) {
        final Set<String> ids = new HashSet<>();
        final Set<String> groups = new HashSet<>();
        sound(document.source());
        order(document.entries(), ids, groups, true, document.source().repository());
        final Set<Source> sources = new HashSet<>();
        for (final Earlier each : document.earlier()) {
            sound(each.source());
            if (each.source().equals(document.source())
                    || !sources.add(each.source())
                    || each.entries().isEmpty()) {
                throw new IllegalArgumentException("a source kept twice");
            }
            order(each.entries(), ids, groups, false, each.source().repository());
        }
    }

    private static void sound(final Source source) {
        if (source.repository().isBlank() || !source.branch().equals(source.branch().strip())) {
            throw new IllegalArgumentException("no source");
        }
    }

    /**
     * Checks the commands' groups: each whole and once, each command once, a command's changes of
     * its kind, and the states in an order the outbox makes: the done first, then at most one
     * begun, refused or held, then waiting ones, or held ones behind a refused or held one.
     */
    private static void order(
            final List<Entry> list,
            final Set<String> ids,
            final Set<String> groups,
            final boolean runs,
            final String repository) {
        String group = null;
        State stop = null;
        for (final Entry entry : list) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("a command twice");
            }
            if (!entry.group().equals(group)) {
                if (!groups.add(entry.group())) {
                    throw new IllegalArgumentException("a group in two places");
                }
                group = entry.group();
                stop = null;
            }
            final Command command = command(entry);
            if (!entry.story().isEmpty()) {
                Ids.story(entry.story());
            }
            if (!entry(entry.group(), command, repository, entry.story())
                    .to(entry.state(), entry.why())
                    .resumed(entry.resumed())
                    .equals(entry)) {
                throw new IllegalArgumentException("changes not of their kind");
            }
            final boolean reasoned = entry.state() == State.REFUSED || entry.state() == State.HELD;
            if (reasoned ? !entry.why().sound() : !entry.why().equals(Why.NONE)) {
                throw new IllegalArgumentException("a reason out of place");
            }
            if (!runs && (entry.state() == State.QUEUED || entry.state() == State.IN_FLIGHT)) {
                throw new IllegalArgumentException("a kept command to run");
            }
            final State state = entry.state();
            if (stop == null) {
                if (state != State.DONE) {
                    stop = state;
                }
            } else if (stop == State.QUEUED || stop == State.IN_FLIGHT) {
                if (state != State.QUEUED) {
                    throw new IllegalArgumentException("a state out of order");
                }
            } else if (state != State.HELD) {
                throw new IllegalArgumentException("a state out of order");
            }
        }
    }

    // --- settling

    /** Settles each begun command against the board, and holds the rest of a held one's group. */
    private static List<Entry> settledAtOpen(
            final List<Entry> list,
            final Supplier<Board> boards,
            final Map<String, State> settled) {
        final List<Entry> next = new ArrayList<>();
        Entry heldBy = null;
        Board board = null;
        for (final Entry entry : list) {
            if (heldBy != null && entry.group().equals(heldBy.group())) {
                next.add(entry.to(State.HELD, heldBy.why()));
                settled.put(entry.id(), State.HELD);
                continue;
            }
            heldBy = null;
            if (entry.state() != State.IN_FLIGHT) {
                next.add(entry);
                continue;
            }
            if (board == null) {
                // Read once, and only when a command was begun: a plain open reads nothing.
                board = boards.get();
            }
            final Entry after = settled(entry, board);
            next.add(after);
            settled.put(entry.id(), after.state());
            if (after.state() == State.HELD) {
                heldBy = after;
            }
        }
        return next;
    }

    /**
     * A begun command: done when the board shows all it writes, waiting again when its target
     * stands at the version judged and the board could show it, held otherwise.
     */
    private static Entry settled(final Entry entry, final Board board) {
        final Command command = command(entry);
        if (shows(command, board)) {
            return entry.to(State.DONE, Why.NONE);
        }
        if (!showable(command)) {
            return entry.to(State.HELD, new Why("PartlySaved", subject(entry.target()), ""));
        }
        final String judged = entry.judged().get(entry.target());
        final Version now = Basis.of(board).of(entry.target());
        if (judged != null && now != null && now.value().equals(judged)) {
            return entry.to(State.QUEUED, Why.NONE).resumed(true);
        }
        return entry.to(State.HELD, new Why("Changed", subject(entry.target()), ""));
    }

    private static String subject(final String target) {
        return new Why("Changed", target, "").sound() ? target : "";
    }

    /** Keeps a source's open groups aside, every command of them held, never run again. */
    private static List<Earlier> keptAside(
            final List<Earlier> kept, final Source old, final List<Entry> list) {
        final List<Entry> held = new ArrayList<>();
        for (final Entry entry : list) {
            held.add(
                    entry.state() == State.QUEUED || entry.state() == State.IN_FLIGHT
                            ? entry.to(State.HELD, new Why("Changed", subject(entry.target()), ""))
                            : entry);
        }
        final List<Earlier> next = new ArrayList<>();
        boolean merged = false;
        for (final Earlier each : kept) {
            if (each.source().equals(old)) {
                final List<Entry> both = new ArrayList<>(each.entries());
                both.addAll(held);
                next.add(new Earlier(old, both));
                merged = true;
            } else {
                next.add(each);
            }
        }
        if (!merged) {
            next.add(new Earlier(old, held));
        }
        return next;
    }

    /** Drops every group written whole, so the file holds only what is not settled. */
    private static List<Entry> pruned(final List<Entry> list) {
        final Set<String> open = new HashSet<>();
        list.stream().filter(e -> e.state() != State.DONE).forEach(e -> open.add(e.group()));
        return list.stream().filter(e -> open.contains(e.group())).toList();
    }

    private static List<Earlier> prunedKept(final List<Earlier> kept) {
        final List<Earlier> next = new ArrayList<>();
        for (final Earlier each : kept) {
            final List<Entry> left = pruned(each.entries());
            if (!left.isEmpty()) {
                next.add(new Earlier(each.source(), left));
            }
        }
        return next;
    }

    /** The document as it would stand with every open command held for the longest reason. */
    private static Document worstHeld(final Document document) {
        final Why longest =
                new Why("ItemsDirectoryRefused", "😀".repeat(MOST_SUBJECT), "x".repeat(64));
        final List<Entry> held =
                document.entries().stream()
                        .map(e -> e.state() == State.DONE ? e : e.to(State.HELD, longest))
                        .toList();
        return new Document(
                document.format(), document.project(), document.source(), held, document.earlier());
    }

    // --- what the board shows

    private static boolean showable(final Command command) {
        return CommandShown.showable(command);
    }

    private static boolean shows(final Command command, final Board board) {
        return CommandShown.shows(command, board);
    }

    // --- commands as data and back

    /** The entry a queued command is kept as. */
    private Entry entry(final String group, final Command command, final String story) {
        return entry(group, command, source.repository(), story);
    }

    private static Entry entry(
            final String group,
            final Command command,
            final String repository,
            final String story) {
        final Map<String, String> judged = new LinkedHashMap<>();
        command.basis().items().forEach((id, version) -> judged.put(id, version.value()));
        final Changes none = Changes.NONE;
        final String kind;
        final String target;
        Changes changes = none;
        switch (command) {
            case Command.Write write -> {
                kind = "write";
                target = write.target();
                final Writes writes = write.writes();
                final Map<String, String> workers = new LinkedHashMap<>();
                writes.workers().forEach((id, worker) -> workers.put(id, worker.name()));
                changes =
                        new Changes(
                                writes.issues(),
                                writes.stories(),
                                workers,
                                writes.priorities(),
                                writes.issueReasons(),
                                writes.storyReasons(),
                                Map.of(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                false,
                                List.of(),
                                List.of(),
                                "",
                                List.of());
            }
            case Command.SetFields set -> {
                kind = "fields";
                target = set.issue();
                final Map<String, String> values = new LinkedHashMap<>();
                final Fields fields = set.fields();
                for (final Fields.Field field :
                        EnumSet.copyOf(
                                fields.named().isEmpty()
                                        ? EnumSet.noneOf(Fields.Field.class)
                                        : fields.named())) {
                    final Object value =
                            switch (field) {
                                case SIZE -> fields.size();
                                case ESTIMATE -> fields.estimateMinutes();
                                case PRIORITY -> fields.priority();
                                case RANK -> fields.rank();
                            };
                    values.put(field.name(), value == null ? "" : value.toString());
                }
                changes = values(values);
            }
            case Command.SetText set -> {
                kind = "text";
                target = set.item();
                final TextEdit edit = set.edit();
                final Map<String, String> values = new LinkedHashMap<>();
                values.put("kind", edit.kind().name());
                if (edit.title() != null) {
                    values.put("title", edit.title());
                }
                final Map<String, String> parts = new LinkedHashMap<>();
                new EnumMap<>(edit.written()).forEach((part, text) -> parts.put(part.name(), text));
                final List<String> removed =
                        edit.removed().stream().sorted().map(Enum::name).toList();
                changes =
                        new Changes(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                values,
                                parts,
                                removed,
                                edit.labels() == null ? List.of() : edit.labels(),
                                edit.labels() != null,
                                List.of(),
                                List.of(),
                                "",
                                List.of());
            }
            case Command.SetBlockers set -> {
                kind = "blockers";
                target = set.issue();
                changes = ids(set.blockedBy());
            }
            case Command.SetStoryIssues set -> {
                kind = "storyIssues";
                target = set.story();
                changes = ids(set.issues());
            }
            case Command.CorrectLabels labels -> {
                kind = "labels";
                target = labels.repository();
                final List<CallData> calls = new ArrayList<>();
                for (final RepositoryWarning.Call call : labels.calls()) {
                    final Map<String, String> set = new LinkedHashMap<>();
                    if (call.color() != null) {
                        set.put("color", call.color());
                    }
                    if (call.description() != null) {
                        set.put("description", call.description());
                    }
                    calls.add(new CallData(call.action().name(), call.label(), set));
                }
                changes =
                        new Changes(
                                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                                Map.of(), Map.of(), List.of(), List.of(), false, List.of(), calls,
                                "", List.of());
            }
            case Command.Comment comment -> {
                kind = "comment";
                target = comment.issue();
                changes =
                        new Changes(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                false,
                                List.of(),
                                List.of(),
                                comment.text(),
                                List.of());
            }
            case Command.CreateIssue create -> {
                kind = "createIssue";
                target = create.issue().repository();
                changes = issues(Map.of(), List.of(), List.of(create.issue()));
            }
            case Command.CreateIdea create -> {
                kind = "createIdea";
                final NewIdea idea = create.idea();
                target = repository;
                changes =
                        issues(
                                story(idea.title(), idea.gain(), idea.test(), idea.priority()),
                                List.of(),
                                idea.issues());
            }
            case Command.CreateStory create -> {
                kind = "createStory";
                final StoryFromIssues made = create.story();
                target = repository;
                changes =
                        issues(
                                story(made.title(), made.gain(), made.test(), made.priority()),
                                made.existing(),
                                List.of());
            }
            case Command.AddUnlisted add -> {
                kind = "addUnlisted";
                target = add.issue();
            }
        }
        return new Entry(
                group,
                command.id(),
                kind,
                target,
                judged,
                changes,
                State.QUEUED,
                Why.NONE,
                false,
                story);
    }

    private static Map<String, String> story(
            final String title, final String gain, final String test, final String priority) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("title", title);
        values.put("gain", gain);
        values.put("test", test);
        values.put("priority", priority);
        return values;
    }

    private static Changes values(final Map<String, String> values) {
        return new Changes(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), values, Map.of(),
                List.of(), List.of(), false, List.of(), List.of(), "", List.of());
    }

    private static Changes ids(final List<String> ids) {
        return new Changes(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), false, ids, List.of(), "", List.of());
    }

    private static Changes issues(
            final Map<String, String> values, final List<String> ids, final List<NewIssue> issues) {
        final List<IssueData> data = new ArrayList<>();
        for (final NewIssue issue : issues) {
            final Map<String, String> set = new LinkedHashMap<>();
            if (issue.size() != null) {
                set.put("size", issue.size());
            }
            if (issue.task() != null) {
                set.put("task", issue.task());
            }
            if (issue.priority() != null) {
                set.put("priority", issue.priority());
            }
            if (issue.story() != null) {
                set.put("story", issue.story());
            }
            if (issue.state() != null) {
                set.put("state", issue.state());
            }
            data.add(
                    new IssueData(
                            issue.repository(),
                            issue.title(),
                            issue.mustBeTrue(),
                            issue.worker().name(),
                            issue.labels(),
                            set));
        }
        return new Changes(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), values, Map.of(),
                List.of(), List.of(), false, ids, List.of(), "", data);
    }

    /**
     * The command an entry keeps, its rule only the versions it was judged on: every item of them
     * is read again from the store and must stand at its version, not only the target.
     */
    static Command command(final Entry entry) {
        final Map<String, Version> versions = new LinkedHashMap<>();
        entry.judged().forEach((id, version) -> versions.put(id, new Version(version)));
        final Basis basis = new Basis(versions);
        final Recheck recheck = fresh(versions);
        final Changes changes = entry.changes();
        return switch (entry.kind()) {
            case "write" -> {
                final Map<String, Worker> workers = new LinkedHashMap<>();
                changes.workers().forEach((id, worker) -> workers.put(id, Worker.valueOf(worker)));
                yield new Command.Write(
                        entry.id(),
                        entry.target(),
                        new Writes(
                                changes.issueStates(),
                                changes.storyStates(),
                                workers,
                                changes.priorities(),
                                changes.issueReasons(),
                                changes.storyReasons()),
                        basis,
                        recheck);
            }
            case "fields" -> {
                final Map<String, String> values = changes.values();
                final Set<Fields.Field> named = EnumSet.noneOf(Fields.Field.class);
                values.keySet().forEach(name -> named.add(Fields.Field.valueOf(name)));
                yield new Command.SetFields(
                        entry.id(),
                        entry.target(),
                        new Fields(
                                named,
                                values.get("SIZE"),
                                number(values.get("ESTIMATE")),
                                values.get("PRIORITY"),
                                number(values.get("RANK"))),
                        basis,
                        recheck);
            }
            case "text" -> {
                final Map<TextEdit.Part, String> written = new EnumMap<>(TextEdit.Part.class);
                changes.parts()
                        .forEach((part, text) -> written.put(TextEdit.Part.valueOf(part), text));
                final Set<TextEdit.Part> removed = EnumSet.noneOf(TextEdit.Part.class);
                changes.removed().forEach(part -> removed.add(TextEdit.Part.valueOf(part)));
                yield new Command.SetText(
                        entry.id(),
                        entry.target(),
                        new TextEdit(
                                TextEdit.Kind.valueOf(need(changes.values(), "kind")),
                                changes.values().get("title"),
                                written,
                                removed,
                                changes.labelsSet() ? changes.labels() : null),
                        basis,
                        recheck);
            }
            case "blockers" ->
                    new Command.SetBlockers(
                            entry.id(), entry.target(), changes.ids(), basis, recheck);
            case "storyIssues" ->
                    new Command.SetStoryIssues(
                            entry.id(), entry.target(), changes.ids(), basis, recheck);
            case "labels" -> {
                final List<RepositoryWarning.Call> calls = new ArrayList<>();
                for (final CallData call : changes.calls()) {
                    calls.add(
                            new RepositoryWarning.Call(
                                    RepositoryWarning.Call.Action.valueOf(call.action()),
                                    call.label(),
                                    call.set().get("color"),
                                    call.set().get("description")));
                }
                yield new Command.CorrectLabels(entry.id(), entry.target(), calls);
            }
            case "comment" -> new Command.Comment(entry.id(), entry.target(), changes.text());
            case "createIssue" -> {
                if (changes.issues().size() != 1) {
                    throw new IllegalArgumentException("one issue");
                }
                yield new Command.CreateIssue(
                        entry.id(), issue(changes.issues().getFirst()), basis, recheck);
            }
            case "createIdea" -> {
                final Map<String, String> values = changes.values();
                yield new Command.CreateIdea(
                        entry.id(),
                        new NewIdea(
                                need(values, "title"),
                                need(values, "gain"),
                                need(values, "test"),
                                need(values, "priority"),
                                changes.issues().stream().map(CommandOutbox::issue).toList()),
                        basis,
                        recheck);
            }
            case "createStory" -> {
                final Map<String, String> values = changes.values();
                yield new Command.CreateStory(
                        entry.id(),
                        new StoryFromIssues(
                                need(values, "title"),
                                need(values, "gain"),
                                need(values, "test"),
                                need(values, "priority"),
                                changes.ids()),
                        basis,
                        recheck);
            }
            case "addUnlisted" ->
                    new Command.AddUnlisted(entry.id(), entry.target(), basis, recheck);
            default -> throw new IllegalArgumentException("an unknown kind");
        };
    }

    private static String need(final Map<String, String> values, final String name) {
        final String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("a value missing");
        }
        return value;
    }

    private static @Nullable Integer number(final @Nullable String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    private static NewIssue issue(final IssueData data) {
        final Map<String, String> set = data.set();
        if (!Set.of("size", "task", "priority", "story", "state").containsAll(set.keySet())) {
            throw new IllegalArgumentException("an unknown value");
        }
        return new NewIssue(
                data.repository(),
                data.title(),
                data.mustBeTrue(),
                set.get("size"),
                Worker.valueOf(data.worker()),
                set.get("task"),
                set.get("priority"),
                set.get("story"),
                set.get("state"),
                data.labels());
    }

    /**
     * The rule a command read from the file is checked by: every item it was judged on is read
     * again from the store and must stand at the version judged; one moved refuses it as changed.
     */
    static Recheck fresh(final Map<String, Version> judged) {
        if (judged.isEmpty()) {
            return Recheck.none();
        }
        // A need makes the store read the items named afresh; the parent is the least it reads.
        return new Recheck(
                Set.of(Recheck.Need.PARENT),
                judged.keySet(),
                dependencies -> {
                    final Basis now = Basis.of(dependencies.board());
                    final List<String> moved =
                            judged.entrySet().stream()
                                    .filter(e -> !e.getValue().equals(now.of(e.getKey())))
                                    .map(Map.Entry::getKey)
                                    .sorted()
                                    .toList();
                    if (!moved.isEmpty()) {
                        throw new StoreException.Changed(moved);
                    }
                });
    }

    // --- writing

    private void replace(final int at, final List<Entry> with, final boolean prune) {
        final List<Entry> next = new ArrayList<>(entries);
        for (int i = 0; i < with.size(); i++) {
            next.set(at + i, with.get(i));
        }
        final List<Entry> kept = prune ? pruned(next) : List.copyOf(next);
        write(
                new Document(FORMAT, project, source, kept, earlier),
                MOST,
                () -> {
                    final Set<String> left = new HashSet<>();
                    kept.forEach(entry -> left.add(entry.id()));
                    entries.stream()
                            .filter(entry -> !left.contains(entry.id()))
                            .forEach(entry -> live.remove(entry.id()));
                    entries = kept;
                });
    }

    /**
     * Writes the file, and changes memory as told once the new file is in place: a failure before
     * the move leaves both as they were; a failure to sync after it leaves memory following the
     * file, and the caller is told the change may stand.
     */
    private void write(final Document document, final int most, final Runnable moved) {
        final byte[] bytes = bytes(document);
        if (bytes.length > most) {
            throw unavailable("The outbox has no room for this change.");
        }
        try {
            durable.write(file, bytes, moved);
        } catch (final DurableFile.NotSynced unsynced) {
            throw unavailable(
                    "The outbox was changed, but its directory could not be synced, so the change"
                            + " may stand",
                    unsynced);
        } catch (final IOException failed) {
            throw unavailable("The outbox cannot be written", failed);
        }
    }

    private static byte[] bytes(final Document document) {
        return WorkRecord.JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
    }

    // --- helpers

    private void open() {
        if (closed) {
            throw new IllegalStateException("The outbox is closed.");
        }
    }

    private void running() {
        open();
        if (!opened) {
            throw new IllegalStateException("The outbox is not open.");
        }
    }

    private Set<String> ids() {
        final Set<String> ids = new HashSet<>();
        entries.forEach(entry -> ids.add(entry.id()));
        earlier.forEach(each -> each.entries().forEach(entry -> ids.add(entry.id())));
        return ids;
    }

    private int at(final String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("The outbox holds no command " + id + " to run.");
    }

    private int inFlight(final String id) {
        final int at = at(id);
        if (entries.get(at).state() != State.IN_FLIGHT) {
            throw new IllegalStateException("The command " + id + " was not begun.");
        }
        return at;
    }

    private static List<Group> groups(
            final Source source, final List<Entry> list, final boolean earlier) {
        final Map<String, List<Told>> byGroup = new LinkedHashMap<>();
        final Map<String, String> stories = new HashMap<>();
        for (final Entry entry : list) {
            stories.put(entry.group(), entry.story());
            byGroup.computeIfAbsent(entry.group(), id -> new ArrayList<>())
                    .add(
                            new Told(
                                    entry.id(),
                                    entry.kind(),
                                    entry.target(),
                                    entry.state(),
                                    entry.why().equals(Why.NONE) ? null : entry.why().failure(),
                                    entry.why().places()));
        }
        final List<Group> groups = new ArrayList<>();
        byGroup.forEach(
                (id, told) ->
                        groups.add(
                                new Group(
                                        source,
                                        id,
                                        List.copyOf(told),
                                        earlier,
                                        stories.getOrDefault(id, ""))));
        return groups;
    }

    private WorkRefusal unavailable(final String why) {
        final String reason = paths.relative(why);
        LOG.warn(
                "The outbox of project {} is unavailable: {}",
                LogText.of(project),
                LogText.of(reason));
        return WorkRefusal.recordUnavailable(project, reason);
    }

    private WorkRefusal unavailable(final String step, final Exception failed) {
        final String words = failed.getMessage();
        return unavailable(
                step
                        + ": "
                        + (words == null || words.isBlank()
                                ? failed.getClass().getSimpleName()
                                : words));
    }
}
