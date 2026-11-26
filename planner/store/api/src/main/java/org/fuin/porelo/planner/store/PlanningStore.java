package org.fuin.porelo.planner.store;

import java.util.Set;

/**
 * A project's planning store, read through one interface; nothing behind it is visible to a caller.
 *
 * <p>Every method answers from the board of the last refresh, so its answers agree with each other.
 */
public interface PlanningStore {

    /** The words of a store's refusal of an edit it does not offer. */
    String NOT_OFFERED = "not offered by this store";

    /**
     * Answers the whole board.
     *
     * @return the board of the last refresh
     * @throws StoreException.WorkflowMissing if the project names no workflow
     */
    Board board();

    /**
     * Answers one story.
     *
     * @param id the story's id
     * @return the story at the board's revision
     * @throws StoreException.NoSuchStory if the board has none of that id
     */
    default Story story(final String id) {
        return board().story(id).orElseThrow(() -> new StoreException.NoSuchStory(id));
    }

    /**
     * Answers one issue.
     *
     * @param id the issue's id
     * @return the issue at the board's revision
     * @throws StoreException.NoSuchIssue if the board has none of that id
     */
    default Issue issue(final String id) {
        return board().issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
    }

    /**
     * Reads the store again and makes its board the one every method answers from.
     *
     * @return the new board
     * @throws StoreException.CredentialRefused if a repository refused the person's login
     * @throws StoreException.Unreachable if a repository could not be reached
     */
    Board refresh();

    /**
     * Reads the store again on the planner's own schedule, while a window follows the project; a
     * store whose source asked it to wait answers the board it holds instead, naming the wait among
     * its faults. A person's reload is {@link #refresh()}.
     *
     * @return the board
     * @throws StoreException.CredentialRefused if a repository refused the person's login
     * @throws StoreException.Unreachable if a repository could not be reached
     */
    default Board poll() {
        return refresh();
    }

    /**
     * Whether a person's reload is answered at once with the board held, while the whole read goes
     * on alone and its board reaches the project's streams when it ends: a store whose whole read
     * may outlast the window's wait for an answer.
     *
     * @return true for such a store; a store that fetches its source answers after the fetch
     */
    default boolean reloadsAlone() {
        return false;
    }

    /**
     * Answers the revision of the board held now, without reading the source, so a reload of a
     * store that reloads alone is answered at once even before its first board.
     *
     * @return the revision held, or empty while no board is held yet; a store that holds no board
     *     of its own reads it
     */
    default java.util.Optional<Revision> held() {
        return java.util.Optional.of(board().revision());
    }

    /**
     * Answers when a board was last read whole from its source, so a person sees how current a
     * value shown from it is: only while the store holds that very board, so a board and a time
     * read apart never pair a time with a board it does not describe.
     *
     * @param board a board the store answered
     * @return the time of that read, or empty when the store holds another board or tells no time
     */
    default java.util.Optional<java.time.Instant> boardReadAt(final Board board) {
        return java.util.Optional.empty();
    }

    /**
     * Answers how long to wait before the next scheduled poll.
     *
     * @param fetched the interval of a store that fetches its whole source
     * @param probed the interval of a store that asks cheaply whether its source changed
     * @return the wait; a store that fetches answers {@code fetched}
     */
    default java.time.Duration pollDelay(
            final java.time.Duration fetched, final java.time.Duration probed) {
        return fetched;
    }

    /**
     * Saves a batch of writes against the revision the window shows: all of them or none.
     *
     * @param revision the revision the writes were checked against
     * @param writes what to write
     * @return the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if an item cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later repository failed after earlier ones were saved
     */
    default Board save(final Revision revision, final Writes writes) {
        throw new UnsupportedOperationException("This store does not save moves");
    }

    /**
     * Whether a create writes the requested initial state in the same save as the new issue.
     *
     * @return true when the store honors {@link NewIssue#state()} during creation
     */
    default boolean createsInState() {
        return false;
    }

    /**
     * Whether a save keeps a move's reason with the item it closes; a store that keeps none refuses
     * a save carrying one before anything is written.
     *
     * @return true when {@link Writes#issueReasons()} and {@link Writes#storyReasons()} are kept
     */
    default boolean keepsReasons() {
        return false;
    }

    /**
     * Whether the store keeps items' labels and sets their topic labels, on a text edit and a new
     * issue; a store that keeps none refuses either carrying labels before anything is written.
     *
     * @return true when {@link TextEdit#labels()} and {@link NewIssue#labels()} are written
     */
    default boolean setsLabels() {
        return false;
    }

    /**
     * Whether the store keeps its repositories' labels: it reads them at the project's first read
     * and at each {@link #refresh()}, names each repository whose labels are not the topic labels
     * in {@link Board#repositoryWarnings()}, and makes a correction's calls on them.
     *
     * @return true when {@link #correctLabels} is carried out
     */
    default boolean keepsRepositoryLabels() {
        return false;
    }

    /**
     * Reads one repository's labels anew from its forge, bounded as every read of them is, and
     * keeps them, so a correction's calls are computed from the labels as they stand now; a
     * repository that is none of the project's is read nothing for.
     *
     * @param repository the repository's name
     * @return the board with the repository's warning as its labels stand now
     * @throws StoreException.NotSavable when the forge refuses the read, named by its status alone,
     *     or the repository has more labels than a read takes
     * @throws StoreException for a read the forge did not answer, or refused for the login or its
     *     rate limit
     * @throws UnsupportedOperationException when the store keeps no repository's labels
     */
    default Board rereadLabels(final String repository) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Makes the calls on one repository's labels in their order, with the person's own login on the
     * repository's forge, and keeps the labels as they stand afterwards; a call the forge refuses
     * stops the calls after it.
     *
     * @param repository the repository's name
     * @param calls the calls, as {@link RepositoryWarning#calls()} gives them
     * @return the board after the calls
     * @throws StoreException.NotSavable for a call the forge refused, named by its status alone
     * @throws UnsupportedOperationException when the store keeps no repository's labels
     */
    default Board correctLabels(
            final String repository, final java.util.List<RepositoryWarning.Call> calls) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Adds a new issue against the revision the window shows, in its requested state when {@link
     * #createsInState()} is true and otherwise the first state, saved as a move is saved: written,
     * indexed and pushed, or not at all.
     *
     * @param revision the revision the window showed when the person wrote it
     * @param issue what the person wrote
     * @return its id and the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if it cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        throw new UnsupportedOperationException("This store does not add issues");
    }

    /**
     * Adds an idea against the revision the window shows: its story and every issue it names in one
     * save, all of them or none. The answer means saved here; the pushes follow, and one that fails
     * or is dropped is named in the board's faults.
     *
     * @param revision the revision the window showed when the idea was checked
     * @param idea the idea
     * @return the story's id, the issues' ids and the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NotSavable if a part of it cannot be written in its repository
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        throw new UnsupportedOperationException("This store does not add ideas");
    }

    /**
     * Whether the store adds an issue made outside the project to it, so a caller asks this before
     * it checks the issue against the board's offer: a store that offers none is refused as such.
     *
     * @return true when {@link #addUnlistedIssue} is offered
     */
    default boolean addsUnlistedIssues() {
        return false;
    }

    /**
     * Adds an open issue of the project's repositories that is no item of the project, as the
     * board's {@link Board#unlisted()} offers it, against the revision the caller read: an issue
     * enters the workflow's first state, a story follows its issues.
     *
     * @param revision the revision the caller read
     * @param issue the issue's tracker reference
     * @return the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision, or the issue
     *     changed on the tracker since the board was read
     * @throws StoreException.NoSuchIssue if the tracker no longer holds the issue
     * @throws StoreException.NotSavable if the board could not hold the issue once added
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board addUnlistedIssue(final Revision revision, final String issue) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Answers whether the store makes a story over issues that stand, so a call it does not offer
     * is refused before its body is checked or anything is written.
     *
     * @return true when {@link #createStoryFromIssues} is offered
     */
    default boolean makesStoriesFromIssues() {
        return false;
    }

    /**
     * Makes a story over issues that stand on the board against the revision the caller read: the
     * story in the project's own repository, its body carrying the request's {@link
     * StoryFromIssues#marker() marker}, each issue hung under it in its order, keeping its own
     * fields. Every issue is checked before anything is written; a save cut off after the story was
     * made is finished by the same call sent again against the board read since, which takes the
     * story carrying that marker and hangs only the issues it lacks; the story keeps the priority
     * it holds.
     *
     * @param revision the revision the caller read
     * @param story the story and its issues
     * @return the story's id, the issues' ids in the story's order and the board after the save
     * @throws StoreException.StaleRevision if the store moved since that revision, or an issue
     *     changed since it was read
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if an issue cannot be hung under a new story
     * @throws StoreException.InStory if another story holds an issue
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after the story was made
     */
    default NewIdea.Added createStoryFromIssues(
            final Revision revision, final StoryFromIssues story) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets an issue's fields a person keeps by hand against the revision the caller read.
     *
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param fields the fields to write
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setFields(final Revision revision, final String issue, final Fields fields) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets a story's whole issue list, in its order, against the revision the caller read: an issue
     * taken out is no longer the story's, one hung in becomes the story's.
     *
     * @param revision the revision the caller read
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchStory if the board has no such story
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setStoryIssues(
            final Revision revision, final String story, final java.util.List<String> issues) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets an issue's whole list of blockers against the revision the caller read.
     *
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     * @throws StoreException.PartlySaved if a later call failed after an earlier one wrote
     */
    default Board setBlockedBy(
            final Revision revision, final String issue, final java.util.List<String> blockedBy) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Sets a story's or an issue's title and named parts of its text against the revision the
     * caller read; every byte of the text outside the parts named stays as it was.
     *
     * @param revision the revision the caller read
     * @param id the story's or the issue's id
     * @param edit the title and the parts to write or remove
     * @return the board after the save, the one read when nothing changed
     * @throws StoreException.StaleRevision if the store moved since that revision, or the item
     *     changed since it was read
     * @throws StoreException.NoSuchStory if the board has no such story
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws TextEdit.Refused if the text cannot be written as asked
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default Board setText(final Revision revision, final String id, final TextEdit edit) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /*
     * The writes below are checked against the versions the caller saw instead of a revision: the
     * targets are the items the write changes, the basis must name each of them, and each target's
     * version is compared with the one the store reads right before the write; a version the basis
     * holds of any other item is ignored. Inside
     * the write serialization, before that check, the recheck's rules run on the dependencies the
     * store reads afresh. Each throws, besides what its revision form throws,
     * StoreException.BasisIncomplete for an item the write changes that the basis names no version
     * for and StoreException.Changed for a target changed or gone since the caller read it, and
     * then writes nothing. A change between that check and the write still ends in
     * StoreException.PartlySaved.
     */

    /**
     * Judges a group of commands before the first of them is written: every command's rule runs on
     * what the store reads afresh for all of them in one read. A store that keeps its own lock
     * judges on the board it holds; each command is checked again on its own write.
     *
     * @param group the group
     * @throws StoreException a rule's refusal; then nothing of the group is written
     */
    default void judge(final CommandGroup group) {
        final Board board = board();
        for (final Command command : group.commands()) {
            command.recheck().rule().accept(Dependencies.of(board));
        }
    }

    /**
     * Writes one command alone, checked on its target as read right before the write. A store that
     * keeps its own lock writes it with its own call for it, which reads the board after.
     *
     * @param command the command
     * @return written, or a conflict for the owner account
     * @throws StoreException a refusal, as the command's own call gives it
     */
    default Written writeAlone(final Command command) {
        return DirectQueue.one(this, command);
    }

    /**
     * Reads the items a group wrote again, once after its last write.
     *
     * @param written the ids of the items written
     * @return the board after them
     */
    default Board readAfter(final Set<String> written) {
        return board();
    }

    /**
     * Saves a batch of writes against the versions the caller saw.
     *
     * @param basis the targets' versions the caller saw
     * @param writes what to write
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save
     */
    default Board save(final Basis basis, final Writes writes, final Recheck recheck) {
        return held(basis, board -> writes.targets(), recheck, revision -> save(revision, writes));
    }

    /**
     * Adds a new issue against the versions the caller saw: of the story it is hung into, if any.
     *
     * @param basis the targets' versions the caller saw
     * @param issue what the person wrote
     * @param recheck the rules checked again on dependencies read now
     * @return its id and the board after the save
     */
    default NewIssue.Added createIssue(
            final Basis basis, final NewIssue issue, final Recheck recheck) {
        final String story = issue.story();
        return held(
                basis,
                board -> story == null ? Set.of() : Set.of(story),
                recheck,
                revision -> createIssue(revision, issue));
    }

    /**
     * Adds an idea, which changes no existing item, after its rules are checked again.
     *
     * @param basis the targets' versions the caller saw, none for an idea
     * @param idea the idea
     * @param recheck the rules checked again on dependencies read now
     * @return the story's id, the issues' ids and the board after the save
     */
    default NewIdea.Added createIdea(final Basis basis, final NewIdea idea, final Recheck recheck) {
        return held(basis, board -> Set.of(), recheck, revision -> createIdea(revision, idea));
    }

    /**
     * Adds an issue made outside the project against the version the caller saw of it.
     *
     * @param basis the unlisted issue's version the caller saw
     * @param issue the issue's tracker reference
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save
     */
    default Board addUnlistedIssue(final Basis basis, final String issue, final Recheck recheck) {
        return held(
                basis,
                board -> Set.of(issue),
                recheck,
                revision -> addUnlistedIssue(revision, issue));
    }

    /**
     * Makes a story over issues that stand on the board against the versions the caller saw.
     *
     * @param basis the targets' versions the caller saw
     * @param story the story and its issues
     * @param recheck the rules checked again on dependencies read now
     * @return the story's id, the issues' ids in the story's order and the board after the save
     */
    default NewIdea.Added createStoryFromIssues(
            final Basis basis, final StoryFromIssues story, final Recheck recheck) {
        return held(
                basis,
                board -> Set.copyOf(story.existing()),
                recheck,
                revision -> createStoryFromIssues(revision, story));
    }

    /**
     * Sets an issue's fields against the version the caller saw of it.
     *
     * @param basis the targets' versions the caller saw
     * @param issue the issue's id
     * @param fields the fields to write
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save, the one read when nothing changed
     */
    default Board setFields(
            final Basis basis, final String issue, final Fields fields, final Recheck recheck) {
        return held(
                basis,
                board -> Set.of(issue),
                recheck,
                revision -> setFields(revision, issue, fields));
    }

    /**
     * Sets a story's whole issue list against the versions the caller saw.
     *
     * @param basis the targets' versions the caller saw
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save, the one read when nothing changed
     */
    default Board setStoryIssues(
            final Basis basis,
            final String story,
            final java.util.List<String> issues,
            final Recheck recheck) {
        return held(
                basis,
                board -> membership(board, story, issues),
                recheck,
                revision -> setStoryIssues(revision, story, issues));
    }

    /**
     * Sets an issue's whole list of blockers against the version the caller saw of it; the blockers
     * are dependencies, read by the recheck, not targets.
     *
     * @param basis the targets' versions the caller saw
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save, the one read when nothing changed
     */
    default Board setBlockedBy(
            final Basis basis,
            final String issue,
            final java.util.List<String> blockedBy,
            final Recheck recheck) {
        return held(
                basis,
                board -> Set.of(issue),
                recheck,
                revision -> setBlockedBy(revision, issue, blockedBy));
    }

    /**
     * Sets a story's or an issue's title and named parts of its text against the version the caller
     * saw of it.
     *
     * @param basis the targets' versions the caller saw
     * @param id the story's or the issue's id
     * @param edit the title and the parts to write or remove
     * @param recheck the rules checked again on dependencies read now
     * @return the board after the save, the one read when nothing changed
     */
    default Board setText(
            final Basis basis, final String id, final TextEdit edit, final Recheck recheck) {
        return held(basis, board -> Set.of(id), recheck, revision -> setText(revision, id, edit));
    }

    /** How often a write on the held board is tried again when the board moved under it. */
    int HELD_ATTEMPTS = 3;

    /**
     * Runs a write on the board the store holds, for a store that checks its writes by revision:
     * the targets' versions are checked on that board and the recheck's rule runs on it, then the
     * write goes against its revision. A board that moved between that read and the write is read
     * and checked again, a few times at most, so only a change to a target refuses the write.
     *
     * @param basis the versions the caller saw
     * @param targets the write's targets on a board
     * @param recheck the rules checked again on the board read
     * @param write the write against the revision of the board checked
     * @return what the write answers
     * @throws StoreException.Busy when the board moved under every attempt
     */
    private <T> T held(
            final Basis basis,
            final java.util.function.Function<Board, Set<String>> targets,
            final Recheck recheck,
            final java.util.function.Function<Revision, T> write) {
        for (int attempt = 1; ; attempt++) {
            final Board board = board();
            basis.unchanged(board, targets.apply(board));
            recheck.rule().accept(Dependencies.of(board));
            try {
                return write.apply(board.revision());
            } catch (final StoreException.StaleRevision moved) {
                if (attempt >= HELD_ATTEMPTS) {
                    throw new StoreException.Busy(
                            board.repositories().isEmpty()
                                    ? ""
                                    : board.repositories().getFirst().name());
                }
            }
        }
    }

    /** The story and every issue a whole new list of its issues gains or loses. */
    private static Set<String> membership(
            final Board board, final String story, final java.util.List<String> issues) {
        final Set<String> ids = new java.util.TreeSet<>();
        ids.add(story);
        final java.util.List<String> before =
                board.story(story).map(Story::issues).orElse(java.util.List.of());
        before.stream().filter(id -> !issues.contains(id)).forEach(ids::add);
        issues.stream().filter(id -> !before.contains(id)).forEach(ids::add);
        return ids;
    }

    /**
     * Writes one comment of the person's text on an issue, with the person's own access; the board
     * shows no comment, so no revision is checked.
     *
     * @param issue the issue's id
     * @param text the comment's text
     * @throws StoreException.NoSuchIssue if the board has no such issue
     * @throws StoreException.NotSavable if the store cannot write it
     * @throws UnsupportedOperationException if the store does not offer it
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default void comment(final String issue, final String text) {
        throw new UnsupportedOperationException(NOT_OFFERED);
    }

    /**
     * Saves what a stage handed back for its issues, each with its new state, in one save on the
     * store as it stands when saved: a stage runs for minutes, and a change elsewhere meanwhile
     * does not refuse it. All of it is written, or none.
     *
     * @param result the stage's result
     * @return the board after the save
     * @throws StoreException.NoSuchIssue if the board has no issue the result names
     * @throws StoreException.NotSavable if an issue no longer stands in the state the stage
     *     completes, its new state is closed, or its file cannot take the result
     * @throws StoreException.Busy if another save or a check holds the project too long
     */
    default Board saveResult(final StageResult result) {
        throw new UnsupportedOperationException("This store does not save stage results");
    }

    /**
     * Reads now what the issues named wait on outside the board, as a start reads it before it
     * offers work on them: a wait the board holds as done may have opened again since.
     *
     * @param issues the issues' ids
     * @return each issue's waits by its id, an issue the board lacks left out; a store that keeps
     *     no tracker answers the board's
     * @throws StoreException.Unreachable if the tracker cannot be asked
     */
    default java.util.Map<String, java.util.List<Wait>> waitsNow(
            final java.util.Collection<String> issues) {
        final Board board = board();
        final java.util.Map<String, java.util.List<Wait>> waits = new java.util.LinkedHashMap<>();
        for (final String id : issues) {
            board.issue(id).ifPresent(issue -> waits.put(id, issue.waitsOn()));
        }
        return waits;
    }

    /**
     * Derives a story's state from its issues' states, as the store's stories index does.
     *
     * @param issueStates the states of the story's issues
     * @return the story's state, or null when none follows
     */
    default @org.jspecify.annotations.Nullable String storyState(
            final java.util.List<String> issueStates) {
        throw new UnsupportedOperationException("This store does not derive story states");
    }

    /**
     * Names the format of every text the store gives - a story's description, an issue's text -
     * since one store writes one format.
     *
     * @return the format, plain text unless the store names its own
     */
    default TextFormat textFormat() {
        return TextFormat.PLAIN;
    }

    /**
     * Answers where the store keeps the project's stories and issues, without reading them.
     *
     * @return the tracker; GitHub's unless the store names its own
     */
    default Tracker tracker() {
        return Tracker.GITHUB;
    }

    /**
     * Retires the store when its project was given anew: it touches its data no more, and a read
     * that waited for it is refused.
     */
    default void retire() {}

    /**
     * Answers how far a whole read of the board under way has come, at once and without a lock.
     *
     * @return the read's progress, or empty while none runs or the store tells none
     */
    default java.util.Optional<ReadProgress> reading() {
        return java.util.Optional.empty();
    }

    /**
     * Notes a Reload asked for before it may wait behind a fetch under way, so {@link #reading()}
     * tells its whole read from that moment; each is ended by {@link #reloadEnded()}.
     */
    default void reloadAsked() {}

    /** Ends a Reload {@link #reloadAsked()} noted, whether its read ended or failed. */
    default void reloadEnded() {}
}
