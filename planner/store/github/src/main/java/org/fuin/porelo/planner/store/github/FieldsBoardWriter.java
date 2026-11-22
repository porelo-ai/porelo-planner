package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Writes moves, workers, priorities, a stage's result and the edits by hand to the GitHub project's
 * fields, the issues' titles and bodies and their comments, and adds new issues and ideas, with the
 * person's own token. Right before it writes, it reads the items it writes and refuses the save
 * when a person changed one of them since the board was read; GitHub takes no condition on a write,
 * so a change in the moment between that read and the write is not caught.
 */
public final class FieldsBoardWriter implements BoardWriter {

    /** The most pages of an issue's comments read to find whether its stage was written. */
    static final int MOST_COMMENT_PAGES = 20;

    /** The number fields a stage's figures are added to. */
    private static final List<String> SUMMED =
            List.of(
                    FieldNames.MINUTES,
                    FieldNames.VM_MINUTES,
                    FieldNames.TOKENS,
                    FieldNames.STAGES);

    private final ProjectWrites github;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    private final InstantSource clock;

    private final WritePace.Sleeper sleeper;

    /**
     * Makes the writer.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     */
    public FieldsBoardWriter(
            final ProjectWrites github, final TokenSource tokens, final AccessRefusals refusals) {
        this(github, tokens, refusals, InstantSource.system());
    }

    /**
     * Makes the writer with its own clock.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     * @param clock tells the time a new issue's call is sent at
     */
    public FieldsBoardWriter(
            final ProjectWrites github,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final InstantSource clock) {
        this(github, tokens, refusals, clock, Thread::sleep);
    }

    /**
     * Makes the writer with its own clock and its own waits between writes.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     * @param clock tells the time a new issue's call is sent at
     * @param sleeper waits between writes and before a write is made again
     */
    FieldsBoardWriter(
            final ProjectWrites github,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final InstantSource clock,
            final WritePace.Sleeper sleeper) {
        this.github = github;
        this.tokens = tokens;
        this.refusals = refusals;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public void save(final Snapshot current, final Revision revision, final Writes writes) {
        fresh(current, revision);
        if (writes.none()) {
            return;
        }
        final Plan plan = new Plan(current);
        writes.issues()
                .forEach(
                        (id, state) -> {
                            final ItemView item = issueItem(current, id);
                            plan.state(id, item, state);
                        });
        writes.workers()
                .forEach(
                        (id, worker) ->
                                plan.option(
                                        id,
                                        issueItem(current, id),
                                        FieldNames.WORKER,
                                        worker == Worker.MANUAL ? "Manual" : "Agent"));
        writes.stories()
                .forEach(
                        (id, state) -> {
                            final ItemView item = storyItem(current, id);
                            plan.state(id, item, state);
                        });
        for (final String id : writes.unstated()) {
            plan.clear(id, storyItem(current, id), FieldNames.STATUS);
        }
        writes.priorities()
                .forEach(
                        (id, priority) ->
                                plan.option(
                                        id, storyItem(current, id), FieldNames.PRIORITY, priority));
        writes.issueReasons()
                .forEach((id, reason) -> plan.closing(id, issueItem(current, id), reason));
        writes.storyReasons()
                .forEach((id, reason) -> plan.closing(id, storyItem(current, id), reason));
        final String token = tokens.token(current.config().forge());
        plan.write(token);
    }

    /**
     * Makes the issue in its repository, adds it to the project, makes it its story's last
     * sub-issue and sets its fields; a call GitHub took without answering is never sent again
     * blindly, and an issue an earlier try of the same creation left half made is taken, not made
     * twice.
     */
    @Override
    public String createIssue(
            final Snapshot current, final Revision revision, final NewIssue issue) {
        fresh(current, revision);
        final String storyId = issue.story();
        final @Nullable ItemView story = storyId == null ? null : storyItem(current, storyId);
        final NewItems adding = new NewItems(github, refusals, clock, sleeper, current);
        // Without a priority of its own an issue takes the Medium a new issue has in Git.
        final NewItems.Planned planned =
                adding.issue(issue, Objects.requireNonNullElse(issue.priority(), "Medium"));
        final String token = tokens.token(current.config().forge());
        if (story == null) {
            adding.write(token, null, List.of(planned));
        } else {
            // GitHub hangs a new sub-issue last, so the story's list ends with it.
            adding.writeUnder(token, story, planned);
        }
        return planned.reference();
    }

    /**
     * Makes the story labeled as one, adds it to the project with its Priority and Rank, then makes
     * each issue in the idea's order, adds it to the project, makes it the story's next sub-issue
     * and sets its fields before the next one is made. A creation cut off midway is finished by
     * sending it again: what the earlier try made is taken, never made twice, and the answer names
     * every issue.
     */
    @Override
    public IdeaIds createIdea(final Snapshot current, final Revision revision, final NewIdea idea) {
        fresh(current, revision);
        final Set<String> named = new HashSet<>();
        for (final NewIssue issue : idea.issues()) {
            if (!named.add(issue.repository())) {
                throw new StoreException.NotSavable(
                        "", "the idea names " + issue.repository() + " twice");
            }
        }
        final NewItems adding = new NewItems(github, refusals, clock, sleeper, current);
        final NewItems.Planned story = adding.story(idea);
        final List<NewItems.Planned> issues = new ArrayList<>();
        idea.issues().forEach(issue -> issues.add(adding.issue(issue, idea.priority())));
        adding.write(tokens.token(current.config().forge()), story, issues);
        return new IdeaIds(
                story.reference(), issues.stream().map(NewItems.Planned::reference).toList());
    }

    /**
     * Reads the issue by its node id right before the write and refuses it, writing nothing, when
     * GitHub holds it on the project already, or it is gone, closed, or moved to another
     * repository; and when the board read after the add would show it, or an item it blocks, only
     * as a warned issue no work starts on: a story outside the project's own repository, an issue
     * under an issue that is no item of the project, that the board does not place, that is a
     * sub-issue itself or that is of another repository, an issue blocked by a story or by an item
     * the board does not place, or a story that blocks an item of the project. Then it is added and
     * given its values.
     */
    @Override
    public void addUnlistedIssue(
            final Snapshot current, final Revision revision, final String issue) {
        fresh(current, revision);
        final String repository = Ids.repositoryOf(issue);
        final int number = Integer.parseInt(issue.substring(issue.lastIndexOf('#') + 1));
        // Checked again under the save's lock: a look since the caller's check moves the offer.
        final Snapshot.Unlisted offered =
                current.board().unlisted().stream().anyMatch(u -> u.id().equals(issue))
                        ? unlisted(current, repository, number)
                        : null;
        if (offered == null) {
            throw new StoreException.StaleRevision(current.board().revision().value(), issue);
        }
        final String token = tokens.token(current.config().forge());
        final GitHubGraphQl.@Nullable IssueOnBoard now;
        try {
            final GitHubGraphQl.IssueBoard board =
                    github.issueBoard(
                            token, List.of(offered.nodeId()), current.config().projectId());
            now = board.issues().isEmpty() ? null : board.issues().get(0);
        } catch (final RuntimeException e) {
            throw refusals.refusal(repository, e, token);
        }
        if (now == null) {
            throw new StoreException.NoSuchIssue(issue);
        }
        final GitHubGraphQl.BoardIssue read = now.issue();
        if (read == null) {
            throw new StoreException.NoSuchIssue(issue);
        }
        if (now.itemId() != null
                || !"OPEN".equals(read.state())
                || read.number() != number
                || !(current.config().project().owner() + "/" + repository)
                        .equalsIgnoreCase(read.repository())) {
            // A person added, closed or moved it since the board was read.
            throw new StoreException.StaleRevision(current.board().revision().value(), issue);
        }
        final boolean story = IssueMapping.STORY_TYPE.equals(read.issueType());
        if (story && !repository.equals(current.config().own())) {
            throw new StoreException.NotSavable(
                    issue,
                    "It is of the issue type Story outside the project's own repository,"
                            + warned(Warning.STORY_OUTSIDE_OWN_REPOSITORY));
        }
        checkParent(current, issue, repository, story, read.parentId());
        checkBlockers(current, issue, read.blockedBy());
        if (story) {
            checkBlocksNoIssue(current, issue, offered.nodeId());
        }
        final NewItems adding = new NewItems(github, refusals, clock, sleeper, current);
        adding.list(
                token,
                adding.existing(repository, number, offered.nodeId(), read.databaseId(), story));
    }

    /** The issue a repository's list gave that is no item of the project, or null. */
    private static Snapshot.@Nullable Unlisted unlisted(
            final Snapshot current, final String repository, final int number) {
        for (final Snapshot.Unlisted listed : current.unlisted()) {
            if (listed.repository().equals(repository)
                    && listed.number() == number
                    && itemOf(current, listed.nodeId()) == null) {
                return listed;
            }
        }
        return null;
    }

    /** The project's item of an issue by its node id, or null. */
    private static @Nullable ItemView itemOf(final Snapshot current, final String issueId) {
        for (final ItemView item : current.items().values()) {
            if (item.issueId().equals(issueId)) {
                return item;
            }
        }
        return null;
    }

    /** An item's tracker reference. */
    private static String reference(final ItemView item) {
        return item.repository() + "#" + item.number();
    }

    /**
     * The end of a refusal for an add the board would show only as a warned issue: what it would
     * then show, naming the warning.
     */
    private static String warned(final String warning) {
        return " so the board would show the issue only as a warned issue no work starts on ("
                + warning
                + ").";
    }

    /**
     * Refuses an issue the board would show only as a warned issue for what blocks it: a story, or
     * an item the board does not place, as one archived, listed apart outside the workflow or left
     * out. A blocker that is no item leaves the issue waiting on it, so it is taken.
     */
    private static void checkBlockers(
            final Snapshot current, final String issue, final GitHubGraphQl.IssueRefs blockers) {
        if (Boolean.TRUE.equals(blockers.pageInfo().hasNextPage())) {
            throw new StoreException.NotSavable(
                    issue, "It is blocked by more issues than one read names.");
        }
        for (final GitHubGraphQl.IssueRef blocker : blockers.nodes()) {
            final ItemView item = itemOf(current, blocker.id());
            if (item == null) {
                continue;
            }
            if (IssueMapping.isStory(item)) {
                throw new StoreException.NotSavable(
                        issue, "It is blocked by a story," + warned(Warning.BLOCKED_BY_STORY));
            }
            if (current.board().issue(reference(item)).isEmpty()) {
                throw new StoreException.NotSavable(
                        issue,
                        "Its blocker "
                                + reference(item)
                                + " is not on the board, as one archived, listed apart outside the"
                                + " workflow or left out,"
                                + warned(Warning.DEPENDS_ON_UNPLACED)
                                + " Repair the blocker first.");
            }
        }
    }

    /**
     * Refuses a story that blocks an item of the project: the board shows an issue blocked by a
     * story only as a warned issue, so the add would turn that issue into one.
     */
    private static void checkBlocksNoIssue(
            final Snapshot current, final String issue, final String nodeId) {
        for (final ItemView item : current.items().values()) {
            if (item.blockedByIds().contains(nodeId)) {
                throw new StoreException.NotSavable(
                        issue,
                        "It blocks "
                                + reference(item)
                                + ", and once a story blocks it, the board would show that issue"
                                + " only as a warned issue no work starts on ("
                                + Warning.BLOCKED_BY_STORY
                                + ").");
            }
        }
    }

    /**
     * Refuses an issue the board would show only as a warned issue for its parent: one under an
     * issue that is no item of the project, under a story or issue the board does not place, under
     * a sub-issue, or an issue under an issue of another repository.
     */
    private static void checkParent(
            final Snapshot current,
            final String issue,
            final String repository,
            final boolean story,
            final @Nullable String parentId) {
        if (parentId == null) {
            return;
        }
        final ItemView parent = itemOf(current, parentId);
        if (parent == null) {
            String named = "its parent";
            for (final Snapshot.Unlisted listed : current.unlisted()) {
                if (listed.nodeId().equals(parentId)) {
                    named = "its parent " + listed.repository() + "#" + listed.number();
                }
            }
            throw new StoreException.NotSavable(
                    issue,
                    "Add "
                            + named
                            + " to the project first; it is no item of it,"
                            + warned(Warning.PARENT_OUTSIDE_PROJECT));
        }
        final String named = reference(parent);
        final boolean placed =
                IssueMapping.isStory(parent)
                        ? current.board().story(named).isPresent()
                        : current.board().issue(named).isPresent();
        if (!placed) {
            throw new StoreException.NotSavable(
                    issue,
                    "Its parent "
                            + named
                            + " is not on the board, as one archived, listed apart outside the"
                            + " workflow or left out,"
                            + warned(Warning.DEPENDS_ON_UNPLACED)
                            + " Repair the parent first.");
        }
        if (!IssueMapping.isStory(parent)
                && current.board().issue(named).orElseThrow().parent() != null) {
            throw new StoreException.NotSavable(
                    issue,
                    "Its parent "
                            + named
                            + " is a sub-issue itself,"
                            + warned(Warning.SUB_ISSUE_OF_SUB_ISSUE));
        }
        if (!story && !IssueMapping.isStory(parent) && !parent.repository().equals(repository)) {
            throw new StoreException.NotSavable(
                    issue,
                    "It stands under an issue of another repository,"
                            + warned(Warning.PARENT_IN_OTHER_REPOSITORY));
        }
    }

    /**
     * Makes the story labeled as one, as an idea's story is made, then hangs each issue under it in
     * the story's order; the issues keep their own fields. Before the story is made, every issue is
     * refused that the board lacks or that hangs under another parent on GitHub, and every one is
     * read again and checked unchanged. A creation cut off after the story was made is finished by
     * sending it again: that story is taken, never made twice, and an issue it holds is not hung
     * again.
     */
    @Override
    public IdeaIds createStoryFromIssues(
            final Snapshot current, final Revision revision, final StoryFromIssues story) {
        fresh(current, revision);
        final Plan plan = new Plan(current);
        final List<NewItems.Over> existing = new ArrayList<>();
        for (final String id : story.existing()) {
            final ItemView item = issueItem(current, id);
            plan.read(id, item);
            existing.add(new NewItems.Over(id, item));
        }
        final NewItems adding = new NewItems(github, refusals, clock, sleeper, current);
        final NewItems.Planned planned =
                adding.story(
                        new NewIdea(
                                story.title(),
                                story.gain(),
                                story.test(),
                                story.priority(),
                                List.of()));
        final String token = tokens.token(current.config().forge());
        adding.writeOver(token, planned, existing, () -> plan.readUnchanged(token));
        return new IdeaIds(planned.reference(), story.existing());
    }

    @Override
    public void saveResult(
            final Snapshot current, final StageResult result, final @Nullable StageEntry stage) {
        final Workflow workflow = current.config().workflow();
        final Plan plan = new Plan(current);
        for (final StageResult.ForIssue given : result.issues()) {
            final String id = given.id();
            final ItemView item = issueItem(current, id);
            final Issue issue =
                    current.board().issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
            if (!issue.state().equals(given.from())) {
                throw new StoreException.NotSavable(
                        id,
                        "it stands in " + issue.state() + ", not in the state the stage completes");
            }
            if (!workflow.states().contains(given.to()) || workflow.closed().contains(given.to())) {
                throw new StoreException.NotSavable(
                        id, "a result moves it to an open state of the workflow only");
            }
            plan.state(id, item, given.to());
            plan.option(id, item, FieldNames.SIZE, given.size());
            final Integer minutes = given.estimateMinutes();
            if (minutes != null) {
                plan.number(id, item, FieldNames.ESTIMATE, minutes);
            }
            @Nullable String body;
            try {
                body = BodySections.read(item.body()).withResult(result.kind(), given).text();
            } catch (final BodySections.Ambiguous e) {
                // Never written over a guess; the board names the body for a person to repair.
                body = null;
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(
                        id, "its body cannot hold the result: " + e.getMessage());
            }
            if (body != null && !body.equals(item.body())) {
                plan.body(id, item, body);
            }
        }
        final String token = tokens.token(current.config().forge());
        if (stage != null && result.issues().size() == 1) {
            final String id = result.issues().get(0).id();
            plan.stage(id, issueItem(current, id), stage);
        }
        plan.write(token);
    }

    @Override
    public boolean setFields(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final Fields fields) {
        fresh(current, revision);
        final ItemView item = issueItem(current, issue);
        final Plan plan = new Plan(current);
        for (final Fields.Field field : fields.named()) {
            switch (field) {
                case SIZE -> plan.optionOrClear(issue, item, FieldNames.SIZE, fields.size());
                case ESTIMATE ->
                        plan.numberOrClear(
                                issue, item, FieldNames.ESTIMATE, fields.estimateMinutes());
                case PRIORITY ->
                        plan.optionOrClear(issue, item, FieldNames.PRIORITY, fields.priority());
                case RANK -> plan.numberOrClear(issue, item, FieldNames.RANK, fields.rank());
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /**
     * Sets a story's sub-issues to the list: takes out those it no longer names, makes the new ones
     * its sub-issues, then orders them as the list does. An issue the story holds only under {@code
     * Also needs} in its body keeps its place after the sub-issues and is refused taken out, since
     * this write does not change the body.
     */
    @Override
    public boolean setStoryIssues(
            final Snapshot current,
            final Revision revision,
            final String story,
            final List<String> issues) {
        fresh(current, revision);
        final ItemView parent = storyItem(current, story);
        final Story found =
                current.board()
                        .story(story)
                        .orElseThrow(() -> new StoreException.NoSuchStory(story));
        final Map<String, String> idOf = new LinkedHashMap<>();
        current.items().forEach((id, view) -> idOf.put(view.issueId(), id));
        final List<String> subs = new ArrayList<>();
        for (final String node : parent.subIssueIds()) {
            final String id = idOf.get(node);
            if (id != null && found.issues().contains(id)) {
                subs.add(id);
            }
        }
        final Plan plan = new Plan(current);
        final List<String> order = new ArrayList<>();
        for (final String id : subs) {
            if (issues.contains(id)) {
                order.add(id);
            } else {
                plan.link(Link.Kind.REMOVE_SUB, story, parent, issueItem(current, id));
            }
        }
        for (final String id : found.issues()) {
            if (!subs.contains(id) && !issues.contains(id)) {
                throw new StoreException.NotSavable(
                        story,
                        "the issue "
                                + id
                                + " stands under Also needs in the story's body, which this"
                                + " write does not change");
            }
        }
        final List<String> also = found.issues().stream().filter(id -> !subs.contains(id)).toList();
        if (!issues.subList(issues.size() - also.size(), issues.size()).equals(also)) {
            // GitHub orders sub-issues only; the board puts the body's issues after them.
            throw new StoreException.NotSavable(
                    story,
                    "the issues under Also needs in the story's body stand after its sub-issues,"
                            + " in their order, which this write does not change");
        }
        for (final String id : issues) {
            if (found.issues().contains(id)) {
                continue;
            }
            final ItemView child = issueItem(current, id);
            if (child.parentIssueId() != null) {
                throw new StoreException.NotSavable(
                        story, "the issue " + id + " has a parent on GitHub already");
            }
            plan.link(Link.Kind.ADD_SUB, story, parent, child);
            order.add(id);
        }
        final List<String> wanted = issues.stream().filter(id -> order.contains(id)).toList();
        for (int at = 0; at < wanted.size(); at++) {
            final String id = wanted.get(at);
            if (order.get(at).equals(id)) {
                continue;
            }
            order.remove(id);
            final ItemView sibling = issueItem(current, order.get(at));
            order.add(at, id);
            final ItemView child = issueItem(current, id);
            if (at == 0) {
                plan.order(story, parent, child, null, sibling);
            } else {
                plan.order(story, parent, child, issueItem(current, wanted.get(at - 1)), null);
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    @Override
    public boolean setBlockedBy(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final List<String> blockedBy) {
        fresh(current, revision);
        final ItemView item = issueItem(current, issue);
        final Issue found =
                current.board()
                        .issue(issue)
                        .orElseThrow(() -> new StoreException.NoSuchIssue(issue));
        final Plan plan = new Plan(current);
        for (final String id : found.blockedBy()) {
            if (!blockedBy.contains(id)) {
                plan.link(Link.Kind.REMOVE_BLOCKER, issue, item, issueItem(current, id));
            }
        }
        for (final String id : blockedBy) {
            if (!found.blockedBy().contains(id)) {
                plan.link(Link.Kind.ADD_BLOCKER, issue, item, issueItem(current, id));
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /**
     * Sets an item's title and named parts: the new body is built from the body the board read,
     * which the write's check confirms GitHub still holds, so every byte outside the parts named
     * stays; title and body go in one update.
     */
    @Override
    public boolean setText(
            final Snapshot current, final Revision revision, final String id, final TextEdit edit) {
        fresh(current, revision);
        final ItemView item =
                edit.kind() == TextEdit.Kind.STORY
                        ? storyItem(current, id)
                        : issueItem(current, id);
        final String body = body(id, item.body(), edit);
        final String title = edit.title();
        final boolean retitled = title != null && !title.equals(item.title());
        final boolean rewritten = !body.equals(item.body());
        if (!retitled && !rewritten) {
            return false;
        }
        final Plan plan = new Plan(current);
        plan.text(id, item, retitled ? title : null, rewritten ? body : null);
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /** The body with the edit's parts removed and written, or the refusal in fixed words. */
    private static String body(final String id, final String held, final TextEdit edit) {
        try {
            BodySections body = BodySections.read(held);
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                if (edit.removed().contains(part)) {
                    body = body.without(BodySections.Part.of(part));
                }
            }
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                final String text = edit.written().get(part);
                if (text != null) {
                    body = body.withEdited(BodySections.Part.of(part), text);
                }
            }
            return body.text();
        } catch (final IllegalArgumentException e) {
            // BodySections' words, an ambiguous body's repair too, are fixed and never repeat it.
            throw new TextEdit.Refused(id, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void comment(final Snapshot current, final String issue, final String text) {
        final ItemView item = issueItem(current, issue);
        final String token = tokens.token(current.config().forge());
        try {
            github.createIssueComment(
                    token,
                    current.config().project().owner(),
                    item.repository(),
                    item.number(),
                    text);
        } catch (final RuntimeException e) {
            throw new Plan(current).refusedCall(e, issue, item.repository(), Set.of(), token);
        }
    }

    /** Refuses a write planned on another board than the store holds, before any call. */
    private static void fresh(final Snapshot current, final Revision revision) {
        if (!current.board().revision().equals(revision)) {
            throw new StoreException.StaleRevision(current.board().revision().value());
        }
    }

    /** The item of an issue on the board, or the refusal that it has none. */
    private static ItemView issueItem(final Snapshot current, final String id) {
        final ItemView item = current.items().get(id);
        if (item == null || current.board().issue(id).isEmpty()) {
            throw new StoreException.NoSuchIssue(id);
        }
        return item;
    }

    /** The item of a story on the board, or the refusal that it has none. */
    private static ItemView storyItem(final Snapshot current, final String id) {
        final ItemView item = current.items().get(id);
        if (item == null || current.board().story(id).isEmpty()) {
            throw new StoreException.NoSuchStory(id);
        }
        return item;
    }

    /** What one write sets, by item, and the calls it makes once every part is checked. */
    private final class Plan {

        private final Snapshot current;

        /** The items written, by their Porelo id, in the order first named. */
        private final Map<String, ItemView> items = new LinkedHashMap<>();

        /** The field values, one per item and field, the last named winning. */
        private final Map<String, Value> values = new LinkedHashMap<>();

        private final Map<String, ProjectWrites.Opening> openings = new LinkedHashMap<>();

        private final Map<String, String> bodies = new LinkedHashMap<>();

        private final Map<String, String> titles = new LinkedHashMap<>();

        private @Nullable Stage stage;

        /**
         * Why a stage's figure cannot be added to a field, by the field's name; raised only where
         * the save would write that field.
         */
        private final Map<String, StoreException.NotSavable> stageRefused = new LinkedHashMap<>();

        /** The reasons to keep as comments, by item, after every other change but the stage. */
        private final Map<String, Writes.Reason> closings = new LinkedHashMap<>();

        /** The links to change, in their order, after every field value. */
        private final List<Link> links = new ArrayList<>();

        /** What GitHub held of each item right before the write, by the issue's node id. */
        private final Map<String, GitHubGraphQl.BoardIssue> reads = new LinkedHashMap<>();

        /** The pace of this save's writes, made when it writes. */
        private WritePace pace = new WritePace(InstantSource.system(), Thread::sleep, "");

        Plan(final Snapshot current) {
            this.current = current;
        }

        void state(final String id, final ItemView item, final String state) {
            final Workflow workflow = current.config().workflow();
            if (!workflow.states().contains(state)) {
                throw new StoreException.NotSavable(id, "its state is none of the workflow");
            }
            option(id, item, FieldNames.STATUS, state);
            final boolean closed = workflow.closed().contains(state);
            // The first closed state is done; any later one, such as Rejected, is not planned.
            final boolean notPlanned = closed && workflow.closed().indexOf(state) > 0;
            // An issue GitHub has closed under an open state stays closed on a move to another open
            // one, so no close is undone unseen; a move out of any other state reopens it. Without
            // a Status, a story stands in the state its issues give it and an issue in the
            // workflow's first state, as the board reads them.
            final String status = item.fields().getOrDefault(FieldNames.STATUS, "");
            final String before =
                    status.isEmpty()
                            ? current.board()
                                    .story(id)
                                    .map(org.fuin.porelo.planner.store.Story::state)
                                    .orElse(workflow.states().get(0))
                            : status;
            final boolean openBefore =
                    workflow.states().contains(before) && !workflow.closed().contains(before);
            final boolean reopens = !closed && !item.open() && !openBefore;
            if ((closed && (item.open() || otherReason(item.stateReason(), notPlanned)))
                    || reopens) {
                openings.put(id, new ProjectWrites.Opening(!closed, notPlanned));
            } else {
                openings.remove(id);
            }
        }

        /**
         * Whether GitHub closed an issue for another reason than its closed state gives; a
         * duplicate counts as not planned, so a person's choice of it stays.
         */
        private static boolean otherReason(
                final @Nullable String reason, final boolean notPlanned) {
            return notPlanned ? "completed".equals(reason) : !"completed".equals(reason);
        }

        void option(final String id, final ItemView item, final String field, final String name) {
            final String fieldId = fieldId(id, field);
            final String optionId =
                    current.config().optionIds().getOrDefault(field, Map.of()).get(name);
            if (optionId == null) {
                throw new StoreException.NotSavable(
                        id, "the project's field " + field + " has no option " + name);
            }
            put(id, item, field, GitHubGraphQl.FieldValue.option(item.itemId(), fieldId, optionId));
        }

        void number(final String id, final ItemView item, final String field, final double n) {
            put(
                    id,
                    item,
                    field,
                    GitHubGraphQl.FieldValue.number(item.itemId(), fieldId(id, field), n));
        }

        void clear(final String id, final ItemView item, final String field) {
            put(id, item, field, GitHubGraphQl.FieldValue.clear(item.itemId(), fieldId(id, field)));
        }

        /** Sets an option, or clears the field for null; a value the item holds is left. */
        void optionOrClear(
                final String id,
                final ItemView item,
                final String field,
                final @Nullable String name) {
            final String held = item.fields().getOrDefault(field, "");
            if (name == null) {
                if (!held.isEmpty()) {
                    clear(id, item, field);
                }
            } else if (!name.equals(held)) {
                option(id, item, field, name);
            }
        }

        /** Sets a whole number, or clears the field for null; a value the item holds is left. */
        void numberOrClear(
                final String id,
                final ItemView item,
                final String field,
                final @Nullable Integer n) {
            final String held = item.fields().getOrDefault(field, "");
            if (n == null) {
                if (!held.isEmpty()) {
                    clear(id, item, field);
                }
            } else if (!Integer.toString(n).equals(held)) {
                number(id, item, field, n);
            }
        }

        /** Adds an item that is only read before a write and checked unchanged. */
        void read(final String id, final ItemView item) {
            items.putIfAbsent(id, item);
        }

        /**
         * Reads the items named and refuses, naming the first, when one differs from the board the
         * write was planned on; writes nothing.
         *
         * @param token the person's token
         * @return each item's issue id in REST, by its node id
         */
        Map<String, Long> readUnchanged(final String token) {
            try {
                unchanged(token);
            } catch (final StoreException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw refusals.refusal(current.config().own(), e, token);
            }
            final Map<String, Long> ids = new LinkedHashMap<>();
            reads.forEach((node, issue) -> ids.put(node, issue.databaseId()));
            return ids;
        }

        /** Adds a link to change; both items are read before the write and checked unchanged. */
        void link(final Link.Kind kind, final String id, final ItemView from, final ItemView to) {
            items.putIfAbsent(id, from);
            items.putIfAbsent(idOf(to), to);
            links.add(new Link(kind, id, from, to, null, null));
        }

        /** Moves a sub-issue right after one sibling or right before another. */
        void order(
                final String id,
                final ItemView parent,
                final ItemView child,
                final @Nullable ItemView after,
                final @Nullable ItemView before) {
            items.putIfAbsent(id, parent);
            for (final ItemView each : java.util.Arrays.asList(child, after, before)) {
                if (each != null) {
                    items.putIfAbsent(idOf(each), each);
                }
            }
            links.add(new Link(Link.Kind.ORDER, id, parent, child, after, before));
        }

        /** Whether nothing is to be written. */
        boolean empty() {
            return values.isEmpty()
                    && openings.isEmpty()
                    && bodies.isEmpty()
                    && titles.isEmpty()
                    && links.isEmpty()
                    && stage == null
                    && closings.isEmpty();
        }

        /** Keeps a reason with an item as a comment; the item is read and checked unchanged. */
        void closing(final String id, final ItemView item, final Writes.Reason reason) {
            try {
                ClosingComment.write(reason);
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(id, "its reason " + e.getMessage());
            }
            items.putIfAbsent(id, item);
            closings.put(id, reason);
        }

        private String idOf(final ItemView item) {
            for (final Map.Entry<String, ItemView> entry : current.items().entrySet()) {
                if (entry.getValue().issueId().equals(item.issueId())) {
                    return entry.getKey();
                }
            }
            throw new IllegalStateException("An item of the snapshot has no id");
        }

        void body(final String id, final ItemView item, final String body) {
            items.putIfAbsent(id, item);
            bodies.put(id, body);
        }

        /** Sets a title, a body or both, written in one update. */
        void text(
                final String id,
                final ItemView item,
                final @Nullable String title,
                final @Nullable String body) {
            items.putIfAbsent(id, item);
            if (title != null) {
                titles.put(id, title);
            }
            if (body != null) {
                bodies.put(id, body);
            }
        }

        /**
         * Adds a stage: its comment, which names the number fields as they stand, and its figures
         * added to them; a field without a value counts as 0. A field the board reads as none, and
         * a sum past a field's bound, are held as refusals, never written over.
         */
        void stage(final String id, final ItemView item, final StageEntry entry) {
            final Map<String, Double> bases = new LinkedHashMap<>();
            if (StageComment.summed(entry)) {
                for (final String field : SUMMED) {
                    try {
                        bases.put(field, base(id, item, field));
                    } catch (final StoreException.NotSavable e) {
                        stageRefused.put(field, e);
                    }
                }
            }
            // With a field the board reads as none the comment is never written, so it needs no
            // base.
            final StageComment.Base base =
                    bases.size() == SUMMED.size()
                            ? new StageComment.Base(
                                    Objects.requireNonNull(bases.get(FieldNames.MINUTES)),
                                    Objects.requireNonNull(bases.get(FieldNames.VM_MINUTES)),
                                    Objects.requireNonNull(bases.get(FieldNames.TOKENS)),
                                    Objects.requireNonNull(bases.get(FieldNames.STAGES)))
                            : null;
            final String comment;
            try {
                comment = StageComment.write(entry, base);
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(
                        id, "its stage cannot be written as a comment: " + e.getMessage());
            }
            items.putIfAbsent(id, item);
            added(id, item, bases, FieldNames.MINUTES, entry.minutes());
            added(id, item, bases, FieldNames.VM_MINUTES, entry.vmMinutes());
            added(id, item, bases, FieldNames.TOKENS, StageComment.newTokens(entry));
            added(id, item, bases, FieldNames.STAGES, 1);
            stage = new Stage(id, entry.key(), comment, Map.copyOf(bases));
        }

        /**
         * Adds a stage's figure to a field's base; a sum past the field's bound is held as a
         * refusal, never written.
         */
        private void added(
                final String id,
                final ItemView item,
                final Map<String, Double> bases,
                final String field,
                final double figure) {
            final Double base = bases.get(field);
            if (base == null) {
                return;
            }
            final double sum = base + figure;
            if (IssueMapping.inBounds(field, sum)) {
                number(id, item, field, sum);
            } else {
                stageRefused.put(
                        field,
                        new StoreException.NotSavable(
                                id,
                                "its field " + field + " would pass its bound with this stage"));
            }
        }

        private double base(final String id, final ItemView item, final String field) {
            final String given = item.fields().getOrDefault(field, "").strip();
            if (given.isEmpty()) {
                return 0;
            }
            // A value the board reads as none is never added to nor written over: fail closed.
            final Double value = IssueMapping.bounded(field, given);
            if (value == null) {
                throw new StoreException.NotSavable(
                        id, "its field " + field + " holds no number in range");
            }
            return value;
        }

        private String fieldId(final String id, final String field) {
            final String fieldId = current.config().fieldIds().get(field);
            if (fieldId == null) {
                throw new StoreException.NotSavable(id, "the project has no field " + field);
            }
            return fieldId;
        }

        private void put(
                final String id,
                final ItemView item,
                final String field,
                final GitHubGraphQl.FieldValue value) {
            items.putIfAbsent(id, item);
            values.put(item.itemId() + "\n" + field, new Value(id, item, value));
        }

        /**
         * Checks that no item changed since the board was read, then makes every call: bodies
         * first, the stage's comment next, field values and open states after it, then the links
         * and the comments keeping reasons. A body or a stage comment GitHub refuses leaves the
         * state and the sums unwritten, so the result can be handed in again; a stage comment that
         * stands names the fields its figures were added to, so a result handed in again adds only
         * to a field that still stands there; and a reason is kept only with an item closed.
         */
        void write(final String token) {
            pace = new WritePace(clock, sleeper, token);
            final String own = current.config().own();
            if (current.config().projectId().isBlank()) {
                throw new StoreException.Unreadable(own, "the GitHub project's id was not read");
            }
            final Set<String> written = new LinkedHashSet<>();
            final @Nullable Stage planned = stage;
            // The comments may take many calls, so they are read before the check, never between
            // the check and the write.
            final StageComment.@Nullable Stage found =
                    planned == null ? null : stageWritten(token, planned);
            final @Nullable Stage toComment = planned == null || found != null ? null : planned;
            if (planned != null) {
                refuseStage(planned, found);
            }
            // A reason kept by an earlier try of the same move is not written twice.
            final Map<String, Writes.Reason> toClose = new LinkedHashMap<>();
            closings.forEach(
                    (id, reason) -> {
                        if (!closingWritten(token, id, reason)) {
                            toClose.put(id, reason);
                        }
                    });
            try {
                unchanged(token);
            } catch (final StoreException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw refusals.refusal(own, e, token);
            }
            for (final Map.Entry<String, ItemView> entry : items.entrySet()) {
                final String id = entry.getKey();
                final String title = titles.get(id);
                final String body = bodies.get(id);
                if (title == null && body == null) {
                    continue;
                }
                final ItemView item = entry.getValue();
                update(token, id, item, title, body, null, written);
            }
            if (toComment != null) {
                // Before the figures, so they are never added without the base they were added to.
                final ItemView item = Objects.requireNonNull(items.get(toComment.id()));
                try {
                    github.createIssueComment(
                            token,
                            current.config().project().owner(),
                            item.repository(),
                            item.number(),
                            toComment.comment());
                } catch (final RuntimeException e) {
                    throw failed(e, item.repository(), written, token);
                }
                written.add(item.repository());
            }
            final List<Value> all = new ArrayList<>(values.values());
            if (planned != null && found != null) {
                // The stage stands already: a field its figures were added to is left as it is.
                all.removeIf(
                        value ->
                                value.id().equals(planned.id())
                                        && added(value.value().fieldId(), planned, found));
            }
            final String projectId = current.config().projectId();
            for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_BATCH) {
                final List<Value> chunk =
                        all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_BATCH));
                final Batch<String> batch;
                try {
                    batch =
                            pace.again(
                                    "the field values of " + chunk.size() + " items",
                                    () ->
                                            github.setFieldValues(
                                                    token,
                                                    projectId,
                                                    chunk.stream().map(Value::value).toList()));
                } catch (final RuntimeException e) {
                    throw failed(e, chunk.get(0).item().repository(), written, token);
                }
                @Nullable Value refused = null;
                @Nullable String error = null;
                for (int at = 0; at < chunk.size(); at++) {
                    final Batch.Outcome<String> outcome =
                            at < batch.outcomes().size() ? batch.outcomes().get(at) : null;
                    if (outcome != null && outcome.saved()) {
                        written.add(chunk.get(at).item().repository());
                    } else if (refused == null) {
                        refused = chunk.get(at);
                        error = outcome == null ? null : outcome.error();
                    }
                }
                if (refused != null) {
                    final String reason =
                            "GitHub refused a field value of "
                                    + refused.id()
                                    + (error == null
                                            ? ""
                                            : ": " + Scrubbed.text(error, List.of(token)));
                    if (written.isEmpty()) {
                        throw new StoreException.NotSavable(refused.id(), reason);
                    }
                    throw new StoreException.PartlySaved(
                            refused.item().repository(), List.copyOf(written), reason);
                }
            }
            // After the field values: a save cut off between them leaves an issue whose Status
            // already says what the person chose, never a closed one the board shows in its old
            // state; the same move again closes what is still open.
            for (final Map.Entry<String, ItemView> entry : items.entrySet()) {
                final ProjectWrites.Opening opening = openings.get(entry.getKey());
                if (opening != null) {
                    update(token, entry.getKey(), entry.getValue(), null, null, opening, written);
                }
            }
            final String owner = current.config().project().owner();
            for (final Link link : links) {
                final ItemView from = link.from();
                try {
                    pace.once(
                            "a link of " + from.repository() + "#" + from.number(),
                            () -> {
                                change(token, owner, link);
                                return null;
                            });
                } catch (final RuntimeException e) {
                    throw refusedCall(e, link.id(), from.repository(), written, token);
                }
                written.add(from.repository());
            }
            for (final Map.Entry<String, Writes.Reason> closing : toClose.entrySet()) {
                final ItemView item = Objects.requireNonNull(items.get(closing.getKey()));
                try {
                    github.createIssueComment(
                            token,
                            current.config().project().owner(),
                            item.repository(),
                            item.number(),
                            ClosingComment.write(closing.getValue()));
                } catch (final RuntimeException e) {
                    throw failed(e, item.repository(), written, token);
                }
                written.add(item.repository());
            }
        }

        /**
         * Whether a summed field of a stage that stands already holds its figures: always for a
         * comment that names no base, which was written after them; else unless the field still
         * stands at the base the comment names, so a save cut off before the field took its sum
         * adds it once.
         */
        private boolean added(
                final String fieldId, final Stage planned, final StageComment.Stage found) {
            final StageComment.@Nullable Base before = found.base();
            if (!summedField(fieldId)) {
                return false;
            }
            if (before == null) {
                return true;
            }
            final Map<String, String> ids = current.config().fieldIds();
            final String field;
            final double was;
            if (fieldId.equals(ids.get(FieldNames.MINUTES))) {
                field = FieldNames.MINUTES;
                was = before.minutes();
            } else if (fieldId.equals(ids.get(FieldNames.VM_MINUTES))) {
                field = FieldNames.VM_MINUTES;
                was = before.vmMinutes();
            } else if (fieldId.equals(ids.get(FieldNames.TOKENS))) {
                field = FieldNames.TOKENS;
                was = before.tokens();
            } else {
                field = FieldNames.STAGES;
                was = before.stages();
            }
            // A field the board reads as none no longer stands at the base the comment names.
            final Double is = planned.bases().get(field);
            return is == null || Double.compare(was, is) != 0;
        }

        /**
         * Raises a stage's held refusal where the save would write its field: any one when the
         * comment is to be written; with the comment standing, one whose field still waits for its
         * sum, so a stage handed in again is never refused over a field it leaves as it is.
         */
        private void refuseStage(final Stage planned, final StageComment.@Nullable Stage found) {
            for (final Map.Entry<String, StoreException.NotSavable> refusal :
                    stageRefused.entrySet()) {
                final String fieldId = current.config().fieldIds().get(refusal.getKey());
                if (found == null || fieldId == null || !added(fieldId, planned, found)) {
                    throw refusal.getValue();
                }
            }
        }

        /** Writes an issue's title, text or open state, once more after a passing failure. */
        private void update(
                final String token,
                final String id,
                final ItemView item,
                final @Nullable String title,
                final @Nullable String body,
                final ProjectWrites.@Nullable Opening opening,
                final Set<String> written) {
            try {
                pace.again(
                        (opening == null ? "the text of " : "the open state of ")
                                + item.repository()
                                + "#"
                                + item.number(),
                        () -> {
                            github.updateIssue(
                                    token,
                                    current.config().project().owner(),
                                    item.repository(),
                                    item.number(),
                                    title,
                                    body,
                                    opening);
                            return null;
                        });
            } catch (final RuntimeException e) {
                // GitHub's words may repeat the title or body sent, so a refusal is fixed.
                throw refusedCall(e, id, item.repository(), written, token);
            }
            written.add(item.repository());
        }

        private void change(final String token, final String owner, final Link link) {
            final ItemView from = link.from();
            final long to = databaseId(link.to());
            switch (link.kind()) {
                case ADD_SUB ->
                        github.addSubIssue(token, owner, from.repository(), from.number(), to);
                case REMOVE_SUB ->
                        github.removeSubIssue(token, owner, from.repository(), from.number(), to);
                case ORDER -> {
                    final ItemView after = link.after();
                    final ItemView before = link.before();
                    github.reprioritizeSubIssue(
                            token,
                            owner,
                            from.repository(),
                            from.number(),
                            to,
                            after == null ? null : databaseId(after),
                            before == null ? null : databaseId(before));
                }
                case ADD_BLOCKER ->
                        github.addBlockedBy(token, owner, from.repository(), from.number(), to);
                case REMOVE_BLOCKER ->
                        github.removeBlockedBy(token, owner, from.repository(), from.number(), to);
            }
        }

        /** An issue's id in REST, as GitHub answered it in the read right before the write. */
        private long databaseId(final ItemView item) {
            final GitHubGraphQl.BoardIssue read = reads.get(item.issueId());
            if (read == null) {
                throw new IllegalStateException("An item linked was not read before the write");
            }
            return read.databaseId();
        }

        /**
         * The refusal of a call GitHub refused or did not answer: a refusal of the request itself,
         * such as a link GitHub does not allow, is not savable in fixed words, since GitHub's own
         * words may repeat what was sent; partly saved once anything was written before it.
         */
        StoreException refusedCall(
                final RuntimeException e,
                final String id,
                final String repository,
                final Set<String> written,
                final String token) {
            if (e instanceof GitHubException refused
                    && refused.delivery() != Delivery.SENT_UNANSWERED
                    && refused.status() >= 400
                    && refused.status() < 500
                    && refused.status() != 401
                    && refused.status() != 403
                    && refused.status() != 429) {
                final String reason = "GitHub refused the call with status " + refused.status();
                if (written.isEmpty()) {
                    return new StoreException.NotSavable(id, reason);
                }
                return new StoreException.PartlySaved(
                        repository,
                        List.copyOf(written),
                        "A part of the save was written before a call failed: " + reason);
            }
            return failed(e, repository, written, token);
        }

        private boolean summedField(final String fieldId) {
            for (final String field : SUMMED) {
                if (fieldId.equals(current.config().fieldIds().get(field))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reads the items to be written and refuses the save, naming the first, when one of them
         * differs from the board the save was planned on.
         */
        private void unchanged(final String token) {
            final Map<String, String> fieldNames = new LinkedHashMap<>();
            current.config().fieldIds().forEach((name, fieldId) -> fieldNames.put(fieldId, name));
            final List<Map.Entry<String, ItemView>> all = new ArrayList<>(items.entrySet());
            for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
                final List<Map.Entry<String, ItemView>> chunk =
                        all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
                final GitHubGraphQl.IssueBoard read =
                        github.issueBoard(
                                token,
                                chunk.stream().map(e -> e.getValue().issueId()).toList(),
                                current.config().projectId());
                for (int at = 0; at < chunk.size(); at++) {
                    final ItemView item = chunk.get(at).getValue();
                    final GitHubGraphQl.IssueOnBoard now =
                            at < read.issues().size() ? read.issues().get(at) : null;
                    if (now == null
                            || !item.itemId().equals(now.itemId())
                            || !Seen.of(item).equals(Seen.of(now, fieldNames))) {
                        throw new StoreException.StaleRevision(
                                current.board().revision().value(), chunk.get(at).getKey());
                    }
                    reads.put(item.issueId(), now.issue());
                }
            }
        }

        /** Whether the comment keeping a reason stands on its item already, found by its marker. */
        private boolean closingWritten(
                final String token, final String id, final Writes.Reason reason) {
            return holds(
                    token,
                    id,
                    body -> ClosingComment.keeps(body, reason),
                    "it holds more comments than are read to find whether its reason stands");
        }

        /** The stage's comment as it stands on its issue already, found by its key, or null. */
        private StageComment.@Nullable Stage stageWritten(final String token, final Stage written) {
            final StageComment.Stage[] found = new StageComment.Stage[1];
            holds(
                    token,
                    written.id(),
                    body -> {
                        final StageComment.Read read =
                                StageComment.read(new CommentView("comment", body, Instant.EPOCH))
                                        .orElse(null);
                        if (read instanceof StageComment.Stage stage
                                && stage.entry().key().equals(written.key())) {
                            found[0] = stage;
                            return true;
                        }
                        return false;
                    },
                    "it holds more comments than are read to find whether its stage stands");
            return found[0];
        }

        /** Whether one of an item's comments is found, reading at most the pages allowed. */
        private boolean holds(
                final String token,
                final String id,
                final java.util.function.Predicate<String> found,
                final String tooMany) {
            final ItemView item = Objects.requireNonNull(items.get(id));
            for (int page = 1; page <= MOST_COMMENT_PAGES; page++) {
                final ProjectWrites.CommentsPage comments;
                try {
                    comments =
                            github.issueComments(
                                    token,
                                    current.config().project().owner(),
                                    item.repository(),
                                    item.number(),
                                    page);
                } catch (final RuntimeException e) {
                    throw refusals.refusal(item.repository(), e, token);
                }
                for (final String body : comments.bodies()) {
                    if (found.test(body)) {
                        return true;
                    }
                }
                if (!comments.more()) {
                    return false;
                }
            }
            throw new StoreException.NotSavable(id, tooMany);
        }

        /**
         * The refusal of a call that failed: partly saved once anything was written before it or
         * when GitHub may have taken it without answering, so the window reads the board anew and
         * nothing is made twice.
         */
        private StoreException failed(
                final RuntimeException e,
                final String repository,
                final Set<String> written,
                final String token) {
            final StoreException refusal = refusals.refusal(repository, e, token);
            final boolean unanswered =
                    e instanceof GitHubException unknown
                            && unknown.delivery() == Delivery.SENT_UNANSWERED;
            if (written.isEmpty() && !unanswered) {
                return refusal;
            }
            return new StoreException.PartlySaved(
                    repository,
                    List.copyOf(written),
                    (unanswered
                                    ? "GitHub took a call without answering, so whether it was"
                                            + " written is unknown"
                                    : "A part of the save was written before a call failed")
                            + ": "
                            + refusal.getMessage());
        }
    }

    /**
     * One field value, with the item it is written to.
     *
     * @param id the item's Porelo id
     * @param item the item
     * @param value the value
     */
    private record Value(String id, ItemView item, GitHubGraphQl.FieldValue value) {}

    /**
     * A link of one issue to another to change.
     *
     * @param kind what changes
     * @param id the Porelo id of the item it is changed on
     * @param from the parent, or the issue whose blockers change
     * @param to the sub-issue, or the blocker
     * @param after the sibling an ordered sub-issue follows, or null
     * @param before the sibling an ordered sub-issue precedes, or null
     */
    private record Link(
            Kind kind,
            String id,
            ItemView from,
            ItemView to,
            @Nullable ItemView after,
            @Nullable ItemView before) {

        /** What a link's change does. */
        enum Kind {
            ADD_SUB,
            REMOVE_SUB,
            ORDER,
            ADD_BLOCKER,
            REMOVE_BLOCKER
        }
    }

    /**
     * A stage to be written as a comment.
     *
     * @param id the issue's id
     * @param key the stage's key
     * @param comment the comment's text
     * @param bases the number fields its figures are added to, by name: none for a stage not
     *     summed, and none for a field the board reads as none
     */
    private record Stage(String id, String key, String comment, Map<String, Double> bases) {}

    /**
     * What a person's change to an item shows in: its field values, its parent, its sub-issues, its
     * blockers, its labels, its issue type, whether either list runs past the one page read,
     * whether it is open, its title and its body.
     */
    private record Seen(
            Map<String, String> fields,
            @Nullable String parent,
            List<String> subIssues,
            List<String> blockers,
            List<String> labels,
            @Nullable String type,
            boolean more,
            boolean open,
            String title,
            String body) {

        static Seen of(final ItemView item) {
            // An item whose lists run past one page is compared by the page read; the board holds
            // it back, warned.
            return new Seen(
                    item.fields(),
                    item.parentIssueId(),
                    item.subIssueIds(),
                    item.blockedByIds(),
                    item.labels(),
                    item.issueType(),
                    false,
                    item.open(),
                    item.title(),
                    item.body());
        }

        static Seen of(final GitHubGraphQl.IssueOnBoard now, final Map<String, String> fieldNames) {
            final GitHubGraphQl.BoardIssue issue = now.issue();
            return new Seen(
                    ItemsBoardReader.values(now.values(), fieldNames),
                    issue.parentId(),
                    ids(issue.subIssues()),
                    ids(issue.blockedBy()),
                    List.copyOf(issue.labels()),
                    issue.issueType(),
                    Boolean.TRUE.equals(issue.subIssues().pageInfo().hasNextPage())
                            || Boolean.TRUE.equals(issue.blockedBy().pageInfo().hasNextPage()),
                    "OPEN".equals(issue.state()),
                    Objects.requireNonNullElse(issue.title(), ""),
                    Objects.requireNonNullElse(issue.body(), ""));
        }

        private static List<String> ids(final GitHubGraphQl.IssueRefs refs) {
            return refs.nodes().stream().map(GitHubGraphQl.IssueRef::id).toList();
        }
    }
}
