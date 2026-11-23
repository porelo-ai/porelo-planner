package org.fuin.porelo.planner.store.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.files.FileProjectLock;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.files.ProjectLock;
import org.fuin.porelo.planner.store.github.BodySections;
import org.fuin.porelo.planner.store.github.FieldNames;
import org.fuin.porelo.planner.store.github.IssueMapping;
import org.fuin.porelo.planner.store.github.ItemView;
import org.fuin.porelo.planner.store.github.NewItemTexts;
import org.fuin.porelo.planner.store.github.StageComment;
import org.jspecify.annotations.Nullable;

/**
 * A project's stories and issues as Markdown files in one directory on the person's computer,
 * outside every repository: one file {@code <repository>-<number>.md} per item, with the fields,
 * body sections and stage entries a GitHub item has. Nothing is committed and no forge is asked.
 *
 * <p>Every read and every write checks the directory again - absolute, the account's alone, reached
 * through no link - and never makes it. Reads and writes hold a lock file inside it, so two
 * planners never interleave; a write reads the files again under the lock and refuses a save whose
 * revision no longer matches, writes every changed file to a temporary one first and then moves
 * each into place. The revision is the digest of the item files' names, sizes, times and contents,
 * so a change by hand is seen even when it keeps a file's size and time.
 *
 * <p>The board's {@link Board#owner()} is null: no tracker keeps the items, and an id such as
 * {@code engine#4} names no issue on any forge, so it must never reach Porelo's Start as a forge's
 * reference.
 */
public final class LocalDirectoryStore implements PlanningStore {

    /** How long a call waits for another planner's read or save of the directory. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    private static final String KIND = "local";

    private static final String ISSUES = "issues";

    private static final String BLOCKED_BY = "blockedBy";

    private final Path directory;

    private final Path standard;

    private final PrivateFiles files;

    private final TrackedStores.Source again;

    private final StageMetrics metrics;

    private final Clock clock;

    private final Duration lockWait;

    private final ProjectLock lock;

    private final String own;

    private volatile TrackedProject project;

    private volatile LocalReader.@Nullable Snapshot held;

    private volatile boolean retired;

    /**
     * Makes the store; nothing is read until the board is asked for.
     *
     * @param directory the project's items directory, which must exist
     * @param files the account's checks of the directory and its files
     * @param project the project as its files name it at opening
     * @param again gives the project as its files name it now, asked at each read
     * @param metrics gives a stage's numbers
     * @param clock tells when a stage's result is saved, which its entry keeps as its end
     */
    public LocalDirectoryStore(
            final Path directory,
            final PrivateFiles files,
            final TrackedProject project,
            final TrackedStores.Source again,
            final StageMetrics metrics,
            final Clock clock) {
        this(directory, files, project, again, metrics, clock, LOCK_WAIT);
    }

    /**
     * Makes the store with its own wait for the directory's lock.
     *
     * @param directory the project's items directory, which must exist
     * @param files the account's checks of the directory and its files
     * @param project the project as its files name it at opening
     * @param again gives the project as its files name it now, asked at each read
     * @param metrics gives a stage's numbers
     * @param clock tells when a stage's result is saved, which its entry keeps as its end
     * @param lockWait how long a call waits for the lock before it is refused as busy
     */
    public LocalDirectoryStore(
            final Path directory,
            final PrivateFiles files,
            final TrackedProject project,
            final TrackedStores.Source again,
            final StageMetrics metrics,
            final Clock clock,
            final Duration lockWait) {
        this(directory, directory, files, project, again, metrics, clock, lockWait);
    }

    /**
     * Makes the store over a directory beside the project's default one, which the window is told.
     *
     * @param directory the project's items directory, which must exist
     * @param standard the project's default items directory
     * @param files the account's checks of the directory and its files
     * @param project the project as its files name it at opening
     * @param again gives the project as its files name it now, asked at each read
     * @param metrics gives a stage's numbers
     * @param clock tells when a stage's result is saved, which its entry keeps as its end
     * @param lockWait how long a call waits for the lock before it is refused as busy
     */
    LocalDirectoryStore(
            final Path directory,
            final Path standard,
            final PrivateFiles files,
            final TrackedProject project,
            final TrackedStores.Source again,
            final StageMetrics metrics,
            final Clock clock,
            final Duration lockWait) {
        this.directory = directory;
        this.standard = standard;
        this.files = files;
        this.project = checked(project);
        this.again = again;
        this.metrics = metrics;
        this.clock = clock;
        this.lockWait = lockWait;
        this.lock = new FileProjectLock(directory);
        this.own = project.repositories().getFirst().name();
    }

    /** The board held, its directory checked again, so a directory moved away is never shown. */
    @Override
    public Board board() {
        final LocalReader.Snapshot current = held;
        if (current == null) {
            return refresh();
        }
        ItemsDirectories.check(files, directory);
        return current.board();
    }

    @Override
    public Tracker tracker() {
        return Tracker.local(directory.toString(), standard.toString());
    }

    @Override
    public Board refresh() {
        return locked(() -> readNow().board());
    }

    /** Reads the directory again; an unchanged digest answers the board held, read no further. */
    @Override
    public Board poll() {
        return refresh();
    }

    /** A look at the directory is cheap, so it is polled as often as a probed store. */
    @Override
    public Duration pollDelay(final Duration fetched, final Duration probed) {
        return probed;
    }

    @Override
    public Board save(final Revision revision, final Writes writes) {
        return write(
                        revision,
                        plan -> {
                            if (writes.reasons()) {
                                throw new StoreException.NotSavable(
                                        "", "this store keeps no reason with a move");
                            }
                            final Workflow workflow = plan.now.board().workflow();
                            writes.issues()
                                    .forEach(
                                            (id, state) ->
                                                    plan.edit(issue(plan.now, id))
                                                            .choice(
                                                                    FieldNames.STATUS,
                                                                    known(workflow, id, state)));
                            writes.workers()
                                    .forEach(
                                            (id, worker) ->
                                                    plan.edit(issue(plan.now, id))
                                                            .choice(
                                                                    FieldNames.WORKER,
                                                                    worker(worker)));
                            // A closed state closes a story; an open one reopens it, after
                            // which it follows its issues again.
                            writes.stories()
                                    .forEach(
                                            (id, state) ->
                                                    plan.edit(story(plan.now, id))
                                                            .choice(
                                                                    FieldNames.STATUS,
                                                                    workflow.closed()
                                                                                    .contains(
                                                                                            known(
                                                                                                    workflow,
                                                                                                    id,
                                                                                                    state))
                                                                            ? state
                                                                            : null));
                            writes.priorities()
                                    .forEach(
                                            (id, priority) ->
                                                    plan.edit(story(plan.now, id))
                                                            .choice(FieldNames.PRIORITY, priority));
                            return "";
                        })
                .board();
    }

    /** Sets the topic labels of an issue or a story and keeps every other label it carries. */
    @Override
    public boolean setsLabels() {
        return true;
    }

    /** A new issue is written in its requested state at once. */
    @Override
    public boolean createsInState() {
        return true;
    }

    @Override
    public NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        final Written<String> written =
                write(
                        revision,
                        plan -> {
                            final String storyId = issue.story();
                            final LocalItem story =
                                    storyId == null ? null : plan.edit(story(plan.now, storyId));
                            return made(
                                    plan,
                                    issue,
                                    Objects.requireNonNullElse(issue.priority(), "Medium"),
                                    story);
                        });
        return new NewIssue.Added(written.answer(), written.board());
    }

    @Override
    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        final Written<List<String>> written =
                write(
                        revision,
                        plan -> {
                            final Set<String> named = new LinkedHashSet<>();
                            for (final NewIssue issue : idea.issues()) {
                                if (!named.add(issue.repository())) {
                                    throw new StoreException.NotSavable(
                                            "", "the idea names " + issue.repository() + " twice");
                                }
                            }
                            final LocalItem story = plan.make(idea, "");
                            final List<String> ids = new ArrayList<>();
                            ids.add(story.id());
                            for (final NewIssue issue : idea.issues()) {
                                ids.add(made(plan, issue, idea.priority(), story));
                            }
                            return ids;
                        });
        final List<String> ids = written.answer();
        return new NewIdea.Added(ids.getFirst(), ids.subList(1, ids.size()), written.board());
    }

    @Override
    public boolean makesStoriesFromIssues() {
        return true;
    }

    /**
     * Makes the story, or takes the one an earlier try of the same request made, as its body's
     * marker tells, and hangs each issue under it that it does not hold yet, in the request's
     * order; an issue another story or issue holds is refused before anything is written.
     */
    @Override
    public NewIdea.Added createStoryFromIssues(
            final Revision revision, final StoryFromIssues story) {
        final Written<String> written =
                write(
                        revision,
                        plan -> {
                            final Workflow workflow = plan.now.board().workflow();
                            final String marker = story.marker();
                            LocalItem taken = null;
                            for (final LocalItem item : plan.now.items().values()) {
                                if (item.story()
                                        && item.repository().equals(own)
                                        && item.body().contains(marker)
                                        && plan.now.board().story(item.id()).isPresent()) {
                                    taken = item;
                                }
                            }
                            final String takenId = taken == null ? null : taken.id();
                            for (int index = 0; index < story.existing().size(); index++) {
                                final String id = story.existing().get(index);
                                final LocalItem item = issue(plan.now, id);
                                final Issue issue = plan.now.board().issue(id).orElseThrow();
                                if (workflow.closed().contains(issue.state())) {
                                    throw new StoreException.NotSavable(
                                            id,
                                            "the issue "
                                                    + id
                                                    + " is closed; a new story takes open issues"
                                                    + " only");
                                }
                                if (!workflow.states().contains(issue.state())) {
                                    throw new StoreException.NotSavable(
                                            id,
                                            "the issue "
                                                    + id
                                                    + " stands outside the workflow; a new story"
                                                    + " takes issues of the workflow only");
                                }
                                final String holder = holder(plan.now, item);
                                if (holder != null && !holder.equals(takenId)) {
                                    throw new StoreException.InStory(
                                            id, holder, "existing[" + index + "]");
                                }
                            }
                            final LocalItem made =
                                    taken == null
                                            ? plan.make(
                                                    new NewIdea(
                                                            story.title(),
                                                            story.gain(),
                                                            story.test(),
                                                            story.priority(),
                                                            List.of()),
                                                    marker)
                                            : plan.edit(taken);
                            final List<String> list = new ArrayList<>(made.links(ISSUES));
                            for (final String id : story.existing()) {
                                if (!list.contains(id)) {
                                    list.add(id);
                                }
                                plan.edit(issue(plan.now, id)).parent(made.id());
                            }
                            made.links(ISSUES, list);
                            return made.id();
                        });
        return new NewIdea.Added(written.answer(), story.existing(), written.board());
    }

    @Override
    public Board setFields(final Revision revision, final String issue, final Fields fields) {
        return write(
                        revision,
                        plan -> {
                            final LocalItem item = plan.edit(issue(plan.now, issue));
                            for (final Fields.Field field : fields.named()) {
                                switch (field) {
                                    case SIZE -> item.choice(FieldNames.SIZE, fields.size());
                                    case ESTIMATE ->
                                            item.number(
                                                    FieldNames.ESTIMATE,
                                                    number(fields.estimateMinutes()));
                                    case PRIORITY ->
                                            item.choice(FieldNames.PRIORITY, fields.priority());
                                    case RANK ->
                                            item.number(FieldNames.RANK, number(fields.rank()));
                                }
                            }
                            return "";
                        })
                .board();
    }

    /**
     * Sets the issues the story's head lists; an issue the story holds only under {@code Also
     * needs} in its body keeps its place after them and is refused taken out, since this write does
     * not change the body.
     */
    @Override
    public Board setStoryIssues(
            final Revision revision, final String story, final List<String> issues) {
        return write(
                        revision,
                        plan -> {
                            final LocalItem parent = story(plan.now, story);
                            final Story found = plan.now.board().story(story).orElseThrow();
                            final List<String> listed = parent.links(ISSUES);
                            final List<String> also =
                                    found.issues().stream()
                                            .filter(id -> !listed.contains(id))
                                            .toList();
                            for (final String id : also) {
                                if (!issues.contains(id)) {
                                    throw new StoreException.NotSavable(
                                            story,
                                            "the issue "
                                                    + id
                                                    + " stands under Also needs in the story's"
                                                    + " body, which this write does not change");
                                }
                            }
                            if (!issues.subList(issues.size() - also.size(), issues.size())
                                    .equals(also)) {
                                throw new StoreException.NotSavable(
                                        story,
                                        "the issues under Also needs in the story's body stand"
                                                + " after its other issues, in their order, which"
                                                + " this write does not change");
                            }
                            final List<String> wanted =
                                    issues.subList(0, issues.size() - also.size());
                            for (final String id : wanted) {
                                final LocalItem child = issue(plan.now, id);
                                final String holder = holder(plan.now, child);
                                if (holder != null && !holder.equals(story)) {
                                    throw new StoreException.NotSavable(
                                            story, "the issue " + id + " stands under " + holder);
                                }
                            }
                            final List<String> list = new ArrayList<>(wanted);
                            for (final String id : listed) {
                                if (!found.issues().contains(id) && !list.contains(id)) {
                                    // An entry the board does not place stays, warned as it is.
                                    list.add(id);
                                } else if (!wanted.contains(id)) {
                                    final LocalItem out = plan.now.items().get(id);
                                    if (out != null && story.equals(out.parent())) {
                                        plan.edit(out).parent(null);
                                    }
                                }
                            }
                            if (!list.equals(listed)) {
                                plan.edit(parent).links(ISSUES, list);
                            }
                            for (final String id : wanted) {
                                final LocalItem child = issue(plan.now, id);
                                if (!story.equals(child.parent())) {
                                    plan.edit(child).parent(story);
                                }
                            }
                            return "";
                        })
                .board();
    }

    @Override
    public Board setBlockedBy(
            final Revision revision, final String issue, final List<String> blockedBy) {
        return write(
                        revision,
                        plan -> {
                            final LocalItem item = issue(plan.now, issue);
                            final Issue found = plan.now.board().issue(issue).orElseThrow();
                            blockedBy.forEach(id -> issue(plan.now, id));
                            final List<String> list = new ArrayList<>(blockedBy);
                            for (final String id : item.links(BLOCKED_BY)) {
                                // A blocker the board does not place stays, warned as it is.
                                if (!found.blockedBy().contains(id) && !list.contains(id)) {
                                    list.add(id);
                                }
                            }
                            if (!list.equals(item.links(BLOCKED_BY))) {
                                plan.edit(item).links(BLOCKED_BY, list);
                            }
                            return "";
                        })
                .board();
    }

    @Override
    public Board setText(final Revision revision, final String id, final TextEdit edit) {
        return write(
                        revision,
                        plan -> {
                            final LocalItem item =
                                    edit.kind() == TextEdit.Kind.STORY
                                            ? story(plan.now, id)
                                            : issue(plan.now, id);
                            final String body = BodySections.edited(id, item.body(), edit);
                            if (body.contains(LocalItem.STAGES_LINE)) {
                                throw new TextEdit.Refused(
                                        id, "the text holds the line the stages start with");
                            }
                            final String title = edit.title();
                            if (title != null && !title.equals(item.title())) {
                                NewItemTexts.line(title, "the title");
                                plan.edit(item).title(title);
                            }
                            if (!body.equals(item.body())) {
                                plan.edit(item).body(body);
                            }
                            final List<String> labels = edit.labels();
                            if (labels != null
                                    && !TopicLabels.topicOf(item.labels()).equals(labels)) {
                                plan.edit(item).labels(TopicLabels.after(item.labels(), labels));
                            }
                            return "";
                        })
                .board();
    }

    /**
     * Saves a stage's result into its issues as they stand now, and, for a result of one issue, its
     * stage: added to the issue's stages and figures once, a stage of the same key kept already
     * counting as saved.
     */
    @Override
    public Board saveResult(final StageResult result) {
        return write(
                        null,
                        plan -> {
                            final Workflow workflow = plan.now.board().workflow();
                            for (final StageResult.ForIssue given : result.issues()) {
                                final String id = given.id();
                                final LocalItem item = issue(plan.now, id);
                                final Issue issue = plan.now.board().issue(id).orElseThrow();
                                if (!issue.state().equals(given.from())) {
                                    throw new StoreException.NotSavable(
                                            id,
                                            "it stands in "
                                                    + issue.state()
                                                    + ", not in the state the stage completes");
                                }
                                if (!workflow.states().contains(given.to())
                                        || workflow.closed().contains(given.to())) {
                                    throw new StoreException.NotSavable(
                                            id,
                                            "a result moves it to an open state of the workflow"
                                                    + " only");
                                }
                                final LocalItem changed = plan.edit(item);
                                changed.choice(FieldNames.STATUS, given.to());
                                changed.choice(FieldNames.SIZE, given.size());
                                if (given.estimateMinutes() != null) {
                                    changed.number(
                                            FieldNames.ESTIMATE, number(given.estimateMinutes()));
                                }
                                try {
                                    changed.body(
                                            BodySections.read(item.body())
                                                    .withResult(result.kind(), given)
                                                    .text());
                                } catch (final BodySections.Ambiguous e) {
                                    // Never written over a guess; the board names the body.
                                } catch (final IllegalArgumentException e) {
                                    throw new StoreException.NotSavable(
                                            id,
                                            "its body cannot hold the result: " + e.getMessage());
                                }
                            }
                            if (result.issues().size() == 1) {
                                final String id = result.issues().getFirst().id();
                                final Optional<StageEntry> stage = metrics.stage(id, result);
                                if (stage.isPresent()) {
                                    staged(plan.edit(issue(plan.now, id)), id, stage.get());
                                }
                            }
                            return "";
                        })
                .board();
    }

    /**
     * Adds a stage and its figures, unless a stage of its key is kept already; a figure the board
     * reads as none, or a sum past its bound, refuses the save.
     */
    private void staged(final LocalItem item, final String id, final StageEntry entry) {
        if (item.hasStage(entry.key())) {
            return;
        }
        if (!item.stagesWhole()) {
            throw new StoreException.NotSavable(
                    id, "its stages cannot be read, so no stage is saved over them");
        }
        if (StageComment.summed(entry)) {
            add(item, id, FieldNames.MINUTES, entry.minutes());
            add(item, id, FieldNames.VM_MINUTES, entry.vmMinutes());
            add(item, id, FieldNames.TOKENS, StageComment.newTokens(entry));
            add(item, id, FieldNames.STAGES, 1);
        }
        final LocalStage.Entry stage = new LocalStage.Entry(entry, clock.instant());
        try {
            LocalStage.write(stage);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable(
                    id, "its stage cannot be written: " + e.getMessage());
        }
        item.append(stage);
    }

    private static void add(
            final LocalItem item, final String id, final String field, final double figure) {
        final String given = item.fields().getOrDefault(field, "").strip();
        final Double base =
                given.isEmpty() ? Double.valueOf(0) : IssueMapping.bounded(field, given);
        // A value the board reads as none is never added to nor written over.
        if (base == null) {
            throw new StoreException.NotSavable(
                    id, "its field " + field + " holds no number in range");
        }
        if (!IssueMapping.inBounds(field, base + figure)) {
            throw new StoreException.NotSavable(
                    id, "its field " + field + " would pass its bound with this stage");
        }
        item.number(field, base + figure);
    }

    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        return project.storyStates().storyState(issueStates);
    }

    @Override
    public TextFormat textFormat() {
        return TextFormat.MARKDOWN;
    }

    /** Makes a new issue in its requested state, or the first, after the last of its priority. */
    private String made(
            final Plan plan,
            final NewIssue issue,
            final String priority,
            final @Nullable LocalItem story) {
        final Workflow workflow = plan.now.board().workflow();
        if (!plan.now.board().names().contains(issue.repository())) {
            throw new StoreException.NotSavable(
                    "", issue.repository() + " is no repository of the project");
        }
        final String body = NewItemTexts.issueBody(issue);
        final String asked = issue.state();
        final String state = asked == null ? workflow.states().getFirst() : asked;
        if (!workflow.states().contains(state) || workflow.closed().contains(state)) {
            throw new StoreException.NotSavable(
                    "", "a new issue starts in an open state of the workflow");
        }
        final LocalItem item = plan.make(issue.repository(), false, issue.title().strip(), body);
        item.labels(issue.labels());
        item.choice(FieldNames.STATUS, state);
        item.choice(FieldNames.SIZE, issue.size());
        item.choice(FieldNames.WORKER, worker(issue.worker()));
        item.choice(FieldNames.PRIORITY, NewItemTexts.priority(priority));
        item.number(FieldNames.RANK, (double) plan.rank(false, priority));
        if (story != null) {
            item.parent(story.id());
            final List<String> list = new ArrayList<>(story.links(ISSUES));
            list.add(item.id());
            story.links(ISSUES, list);
        }
        return item.id();
    }

    /** The story or issue an issue stands under, by its head or a story's list, or null. */
    private static @Nullable String holder(final LocalReader.Snapshot now, final LocalItem issue) {
        final String parent = issue.parent();
        if (parent != null) {
            return parent;
        }
        for (final LocalItem item : now.items().values()) {
            if (item.story() && item.links(ISSUES).contains(issue.id())) {
                return item.id();
            }
        }
        return null;
    }

    private static String known(final Workflow workflow, final String id, final String state) {
        if (!workflow.states().contains(state)) {
            throw new StoreException.NotSavable(id, "its state is none of the workflow");
        }
        return state;
    }

    private static String worker(final Worker worker) {
        return worker == Worker.MANUAL ? "Manual" : "Agent";
    }

    private static @Nullable Double number(final @Nullable Integer value) {
        return value == null ? null : value.doubleValue();
    }

    /** The item of an issue on the board, or the refusal that it has none. */
    private static LocalItem issue(final LocalReader.Snapshot now, final String id) {
        final LocalItem item = now.items().get(id);
        if (item == null || now.board().issue(id).isEmpty()) {
            throw new StoreException.NoSuchIssue(id);
        }
        return item;
    }

    /** The item of a story on the board, or the refusal that it has none. */
    private static LocalItem story(final LocalReader.Snapshot now, final String id) {
        final LocalItem item = now.items().get(id);
        if (item == null || now.board().story(id).isEmpty()) {
            throw new StoreException.NoSuchStory(id);
        }
        return item;
    }

    /** Refuses a project whose tracker is of another kind. */
    private static TrackedProject checked(final TrackedProject project) {
        if (!KIND.equals(project.kind())) {
            throw new StoreException.Unreadable(
                    project.repositories().getFirst().name(),
                    "project.yml names a tracker of another kind than a local directory");
        }
        return project;
    }

    /**
     * Reads the directory under the lock held, checked when the lock was taken; the board held is
     * answered when the digest did not change.
     */
    private LocalReader.Snapshot readNow() {
        final TrackedProject now = checked(again.current());
        project = now;
        final LocalReader.Scan scan;
        try {
            scan = LocalReader.scan(directory, files, now);
        } catch (final UncheckedIOException e) {
            throw new StoreException.Unreadable(
                    own, "the project's items directory cannot be read");
        }
        final LocalReader.Snapshot current = held;
        if (current != null && current.board().revision().equals(scan.revision())) {
            return current;
        }
        final LocalReader.Snapshot read = LocalReader.read(scan, now);
        held = read;
        return read;
    }

    /**
     * Runs a call under the directory's lock, the directory checked once before the lock is taken.
     */
    private <T> T locked(final Supplier<T> call) {
        ItemsDirectories.check(files, directory);
        final Optional<ProjectLock.Held> taken;
        try {
            taken = lock.acquire(lockWait);
        } catch (final UncheckedIOException e) {
            throw new StoreException.Unreadable(own, "the items directory's lock cannot be taken");
        }
        try (ProjectLock.Held hold = taken.orElseThrow(() -> new StoreException.Busy(own))) {
            if (retired) {
                // The project's store was opened anew, on another directory perhaps: a call
                // based on this store's board is answered as one that lost to another save.
                throw new StoreException.StaleRevision("");
            }
            return call.get();
        }
    }

    /**
     * Retires the store: a call that takes the lock after this is refused, and one that holds it
     * now ends before this returns.
     */
    @Override
    public void retire() {
        retired = true;
        try {
            ItemsDirectories.check(files, directory);
            lock.acquire(lockWait).ifPresent(ProjectLock.Held::close);
        } catch (final StoreException.ItemsDirectoryRefused | UncheckedIOException e) {
            // The directory is gone or refused: no call gets past its check to hold the lock.
        }
    }

    /**
     * What a write answers: what it made, and the board after it.
     *
     * @param answer what it made
     * @param board the board after it
     * @param <T> what it made
     */
    private record Written<T>(T answer, Board board) {}

    /**
     * Writes under the lock: reads the directory again, refuses a revision that no longer matches
     * when one is given, plans the change on what it read, and writes every file it changes.
     */
    private <T> Written<T> write(
            final @Nullable Revision revision, final Function<Plan, T> planned) {
        return locked(
                () -> {
                    final LocalReader.Snapshot now = readNow();
                    if (revision != null && !now.board().revision().equals(revision)) {
                        throw new StoreException.StaleRevision(now.board().revision().value());
                    }
                    final Plan plan = new Plan(now);
                    final T answer = planned.apply(plan);
                    final boolean wrote;
                    try {
                        wrote = plan.commit();
                    } catch (final StoreException.StaleRevision e) {
                        // A name taken since the read: the window is told the revision on disk.
                        throw new StoreException.StaleRevision(
                                readNow().board().revision().value(),
                                Objects.requireNonNull(e.changed()));
                    } catch (final StoreException.PartlySaved e) {
                        // The board held and its revision show what is on disk now.
                        readNow();
                        throw e;
                    }
                    if (wrote) {
                        return new Written<>(answer, readNow().board());
                    }
                    return new Written<>(answer, now.board());
                });
    }

    /** The items one write changes or makes, written together once every part is planned. */
    private final class Plan {

        private final LocalReader.Snapshot now;

        /** The items changed or made, by their ids, in the order first named. */
        private final Map<String, LocalItem> changed = new LinkedHashMap<>();

        private final Set<String> made = new LinkedHashSet<>();

        private final Map<String, Integer> numbers = new HashMap<>();

        private final Map<String, Integer> ranks = new HashMap<>();

        Plan(final LocalReader.Snapshot now) {
            this.now = now;
        }

        /** The item's copy this write changes, the same one each time it is named. */
        LocalItem edit(final LocalItem item) {
            return changed.computeIfAbsent(item.id(), id -> item.copy());
        }

        /** A new item, numbered after the highest number of its repository's files. */
        LocalItem make(
                final String repository,
                final boolean story,
                final String title,
                final String body) {
            final int number =
                    numbers.compute(
                            repository,
                            (name, last) ->
                                    (last != null
                                                    ? last
                                                    : now.numbers()
                                                            .getOrDefault(
                                                                    name.toLowerCase(Locale.ROOT),
                                                                    0))
                                            + 1);
            final LocalItem item = LocalItem.fresh(repository, number, story, title, body);
            changed.put(item.id(), item);
            made.add(item.id());
            return item;
        }

        /** A new story in the project's own repository, its body as a GitHub story's. */
        LocalItem make(final NewIdea idea, final String marker) {
            final String body = NewItemTexts.storyBody(idea, marker);
            final LocalItem story = make(own, true, idea.title().strip(), body);
            story.choice(FieldNames.PRIORITY, idea.priority());
            story.number(FieldNames.RANK, (double) rank(true, idea.priority()));
            return story;
        }

        /** The rank after the last item of a kind and priority, and after those this write made. */
        int rank(final boolean story, final String priority) {
            final List<ItemView> views =
                    now.items().values().stream()
                            .map(item -> item.view(now.board().workflow(), List.of()))
                            .toList();
            return ranks.compute(
                    story + "\n" + priority,
                    (key, last) ->
                            Math.min(
                                    (last != null
                                                    ? last
                                                    : NewItemTexts.highestRank(
                                                            views, story, priority))
                                            + 1,
                                    IssueMapping.MOST_RANK));
        }

        /**
         * Writes every changed item: each new file is claimed by its name first, then every
         * temporary file is written, then each is moved into place.
         *
         * @return whether anything was written
         */
        boolean commit() {
            final Map<String, byte[]> texts = new LinkedHashMap<>();
            for (final LocalItem item : changed.values()) {
                final String text;
                try {
                    text = item.text();
                } catch (final IllegalArgumentException e) {
                    throw new StoreException.NotSavable(item.id(), String.valueOf(e.getMessage()));
                }
                final LocalItem before = now.items().get(item.id());
                if (before != null && !made.contains(item.id()) && text.equals(before.text())) {
                    continue;
                }
                final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > LocalReader.MOST_BYTES) {
                    throw new StoreException.NotSavable(
                            item.id(), "its file would be larger than 1 MiB");
                }
                texts.put(item.id(), bytes);
            }
            if (texts.isEmpty()) {
                return false;
            }
            final List<Path> claimed = new ArrayList<>();
            final Map<String, Path> temporary = new LinkedHashMap<>();
            try {
                for (final String id : made) {
                    final Path target = target(id);
                    try {
                        create(target, new byte[0]);
                    } catch (final FileAlreadyExistsException e) {
                        // A file of its name was placed since the directory was read; the write
                        // reads the directory again for the revision it answers.
                        throw new StoreException.StaleRevision(now.board().revision().value(), id);
                    }
                    claimed.add(target);
                }
                for (final Map.Entry<String, byte[]> text : texts.entrySet()) {
                    final Path file =
                            directory.resolve(
                                    "."
                                            + Objects.requireNonNull(changed.get(text.getKey()))
                                                    .fileName()
                                            + "."
                                            + UUID.randomUUID()
                                            + ".tmp");
                    create(file, text.getValue());
                    temporary.put(text.getKey(), file);
                }
            } catch (final IOException e) {
                clean(temporary.values(), claimed);
                throw new StoreException.NotSavable(
                        texts.keySet().iterator().next(), "its file cannot be written");
            } catch (final RuntimeException e) {
                clean(temporary.values(), claimed);
                throw e;
            }
            final List<String> written = new ArrayList<>();
            for (final Map.Entry<String, Path> file : temporary.entrySet()) {
                final String id = file.getKey();
                try {
                    // An atomic move replaces the file in place: rename(2) on POSIX, MoveFileEx
                    // with MOVEFILE_REPLACE_EXISTING on Windows. With ATOMIC_MOVE, Files.move
                    // ignores every other option, so REPLACE_EXISTING would add nothing.
                    Files.move(file.getValue(), target(id), StandardCopyOption.ATOMIC_MOVE);
                } catch (final IOException e) {
                    final List<Path> left = new ArrayList<>(temporary.values());
                    left.removeIf(path -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS));
                    final List<Path> empty = new ArrayList<>();
                    for (final Path path : claimed) {
                        if (written.stream().noneMatch(done -> target(done).equals(path))) {
                            empty.add(path);
                        }
                    }
                    clean(left, empty);
                    if (written.isEmpty()) {
                        throw new StoreException.NotSavable(id, "its file cannot be written");
                    }
                    throw new StoreException.PartlySaved(
                            Ids.repositoryOf(id),
                            written.stream().map(Ids::repositoryOf).distinct().toList(),
                            "The files of "
                                    + String.join(", ", written)
                                    + " were written, but not the file of "
                                    + id);
                }
                written.add(id);
            }
            return true;
        }

        private Path target(final String id) {
            return directory.resolve(Objects.requireNonNull(changed.get(id)).fileName());
        }

        /** Makes a new file of the account's alone, never over one that exists. */
        private void create(final Path file, final byte[] content) throws IOException {
            try (SeekableByteChannel channel =
                    Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            files.ownerOnlyFile())) {
                final ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }

        /** Removes the temporary files and the empty files claimed for new items. */
        private static void clean(
                final java.util.Collection<Path> temporary, final List<Path> claimed) {
            for (final Path path : temporary) {
                deleteQuietly(path);
            }
            for (final Path path : claimed) {
                try {
                    final BasicFileAttributes attributes =
                            Files.readAttributes(
                                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    // Only the empty file the save made; a link put in its place is not it.
                    if (attributes.isRegularFile() && attributes.size() == 0) {
                        Files.delete(path);
                    }
                } catch (final IOException e) {
                    // An empty file left is a fault the next read names; the refusal stands.
                }
            }
        }

        private static void deleteQuietly(final Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (final IOException e) {
                // A temporary file left is ignored by every read; the refusal stands.
            }
        }
    }
}
