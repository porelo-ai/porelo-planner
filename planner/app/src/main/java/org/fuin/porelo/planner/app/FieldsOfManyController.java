package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.app.answers.BatchSaved;
import org.fuin.porelo.planner.app.answers.ItemResult;
import org.fuin.porelo.planner.core.FieldsOfMany;
import org.fuin.porelo.planner.core.MovePreview;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the call that sets the priority, size or story of many issues and the priority of many
 * stories in one save, as {@code openapi/planner.yaml} describes it: the core judges each item by
 * the rules its call alone keeps, and what stands is written as one group of commands.
 */
@RestController
public class FieldsOfManyController {

    private static final Logger LOG = LoggerFactory.getLogger(FieldsOfManyController.class);

    private final Projects projects;

    private final ChangesRegistry changes;

    private final CommandQueues queues;

    /**
     * Makes the controller, its saves written by calling each project's store directly.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     */
    public FieldsOfManyController(final Projects projects, final ChangesRegistry changes) {
        this(projects, changes, CommandQueues.DIRECT);
    }

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     * @param queues gives each project's saves the queue that writes their commands
     */
    @Autowired
    public FieldsOfManyController(
            final Projects projects, final ChangesRegistry changes, final CommandQueues queues) {
        this.projects = projects;
        this.changes = changes;
        this.queues = queues;
    }

    /**
     * Sets the fields of many issues and the priorities of many stories in one save: each item is
     * judged, each the rules refuse is dropped with its refusal, and the rest is saved against the
     * board that was judged. A board changed between the judging and the save is judged again once.
     *
     * @param project the project's name
     * @param request the request, whose body is the issues' fields and the stories' priorities
     * @return the revision after the call and what became of each item
     */
    @PutMapping("/projects/{project}/fields")
    public BatchSaved setFieldsOfMany(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final ManyFieldsRequests.ManyFields body = ManyFieldsRequests.read(request, name);
        final List<FieldsOfMany.IssueAsk> issues = body.issueAsks();
        final List<FieldsOfMany.StoryAsk> stories = body.storyAsks();
        final Projects.Given given = projects.given(name);
        try {
            return given.work()
                    .movingIssues(
                            (running, runs) ->
                                    saved(name, given.store(), issues, stories, running));
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not set the fields of many issues.",
                    Map.of("project", name));
        } finally {
            changes.saved(name);
        }
    }

    /** Judges and saves the call, judging it again once on the board a change left. */
    private BatchSaved saved(
            final String name,
            final PlanningStore store,
            final List<FieldsOfMany.IssueAsk> issues,
            final List<FieldsOfMany.StoryAsk> stories,
            final MovePreview.Running running) {
        Board board = store.board();
        for (int attempt = 0; ; attempt++) {
            final FieldsOfMany.Judged judged = FieldsOfMany.judge(board, issues, stories, running);
            if (judged.steps().isEmpty()) {
                // Nothing changes, so no repository is locked or asked.
                return answer(board.revision().value(), judged);
            }
            try {
                final Board saved =
                        CommandGroups.saved(
                                queues.queue(name, store).submit(group(board, judged.steps())),
                                store);
                LOG.info(
                        "Set fields of {} items of project {} as revision {}",
                        judged.steps().size(),
                        LogText.of(name),
                        LogText.of(saved.revision().value()));
                return answer(saved.revision().value(), judged);
            } catch (final StoreException.Changed changed) {
                if (attempt > 0) {
                    throw changed;
                }
            }
            board = store.board();
        }
    }

    /** One command per step, each judged on the versions the board holds of its targets. */
    private static CommandGroup group(final Board board, final List<FieldsOfMany.Step> steps) {
        final Basis all = Basis.of(board);
        final String group = UUID.randomUUID().toString();
        final List<Command> commands = new ArrayList<>();
        for (final FieldsOfMany.Step step : steps) {
            final String id = group + "/" + commands.size();
            final Basis basis = only(all, step.targets());
            commands.add(
                    switch (step) {
                        case FieldsOfMany.Members members ->
                                new Command.SetStoryIssues(
                                        id,
                                        members.story(),
                                        members.issues(),
                                        basis,
                                        Recheck.none());
                        case FieldsOfMany.Priority priority ->
                                new Command.Write(
                                        id,
                                        priority.story(),
                                        new Writes(
                                                Map.of(),
                                                Map.of(),
                                                Map.of(),
                                                Map.of(priority.story(), priority.priority())),
                                        basis,
                                        Recheck.none());
                        case FieldsOfMany.SetIssue set ->
                                new Command.SetFields(
                                        id, set.issue(), set.fields(), basis, Recheck.none());
                    });
        }
        return new CommandGroup(group, commands);
    }

    private static Basis only(final Basis all, final Set<String> targets) {
        return new Basis(
                all.items().entrySet().stream()
                        .filter(item -> targets.contains(item.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private static BatchSaved answer(final String revision, final FieldsOfMany.Judged judged) {
        return new BatchSaved(
                revision, judged.items().stream().map(FieldsOfManyController::result).toList());
    }

    private static ItemResult result(final FieldsOfMany.Item item) {
        final @Nullable RuntimeException refusal = item.refusal();
        return new ItemResult(
                item.item(),
                null,
                ItemResult.OutcomeEnum.valueOf(item.outcome().name()),
                null,
                item.raised(),
                refusal == null ? null : Problems.answer(refusal));
    }
}
