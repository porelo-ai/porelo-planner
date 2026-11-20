package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.git.CheckoutProject;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.github.CarryOverPlan;
import org.fuin.porelo.planner.store.github.ProjectRef;

/**
 * Carries a Markdown project's open stories and issues over to a GitHub project. Only the dry run
 * is built: it reads the project from its checkouts, needs no token, writes nothing and says one
 * line per item it would write. A project or a file it cannot read stops it before any line.
 */
final class CarryOverCommand {

    /** The command's form. */
    static final String USAGE = "carry-over --project <dir> --to <owner>/<number> --dry-run";

    private CarryOverCommand() {}

    /**
     * Runs the command.
     *
     * @param rest the arguments after {@code carry-over}
     * @param environment the environment Git searches the checkouts with
     * @param out where the plan's lines go
     * @param err where a fault or a failure goes
     * @return 0 when the plan is whole, 1 when an item cannot be carried, 2 when nothing could be
     *     planned
     */
    static int run(
            final List<String> rest,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err) {
        String project = null;
        String to = null;
        boolean dry = false;
        for (int i = 0; i < rest.size(); i++) {
            final String arg = rest.get(i);
            if (arg.equals("--project") && project == null && i + 1 < rest.size()) {
                project = rest.get(++i);
            } else if (arg.equals("--to") && to == null && i + 1 < rest.size()) {
                to = rest.get(++i);
            } else if (arg.equals("--dry-run") && !dry) {
                dry = true;
            } else {
                return usage(err);
            }
        }
        if (project == null || to == null) {
            return usage(err);
        }
        if (!dry) {
            err.println("carry-over: only the dry run is built; run it with --dry-run");
            return WorkCommands.UNANSWERED;
        }
        final ProjectRef target;
        try {
            target = ProjectRef.parse(to);
        } catch (final IllegalArgumentException e) {
            err.println("carry-over: --to: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        final Path root = Path.of(project);
        final CarryOverPlan plan;
        try {
            plan = plan(CheckoutProject.read(root, environment));
        } catch (final IOException | IllegalArgumentException | StoreException e) {
            err.println("carry-over: " + root + " could not be read whole: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        } catch (final Unread e) {
            e.faults.forEach(fault -> err.println("carry-over: " + fault));
            err.println(
                    "carry-over: "
                            + e.faults.size()
                            + " thing(s) of "
                            + root
                            + " could not be read; no plan is given");
            return WorkCommands.UNANSWERED;
        }
        plan.items().forEach(item -> out.println(item.line(true)));
        final long stories = plan.items().stream().filter(CarryOverPlan.Item::story).count();
        final long cannot = plan.items().stream().filter(item -> item.problem() != null).count();
        out.println(
                "DRY RUN  "
                        + plan.items().size()
                        + " item(s) to "
                        + target.owner()
                        + "/"
                        + target.number()
                        + ": "
                        + stories
                        + " story(ies), "
                        + (plan.items().size() - stories)
                        + " issue(s); nothing was written");
        if (cannot > 0) {
            err.println(
                    "carry-over: " + cannot + " item(s) cannot be carried; the plan is not whole");
            return WorkCommands.REFUSED;
        }
        return 0;
    }

    /** The board's faults, which leave the plan unwhole before it is made. */
    private static final class Unread extends Exception {
        private static final long serialVersionUID = 1L;

        private final transient List<String> faults;

        private Unread(final List<String> faults) {
            super(faults.size() + " fault(s)", null, false, false);
            this.faults = List.copyOf(faults);
        }
    }

    private static CarryOverPlan plan(final CheckoutProject project) throws IOException, Unread {
        final Board board = project.board();
        if (!board.faults().isEmpty()) {
            throw new Unread(board.faults());
        }
        final Map<String, CarryOverPlan.IssueSource> sources = new LinkedHashMap<>();
        for (final Issue issue : CarryOverPlan.carried(board)) {
            final IssueHead head = project.head(issue);
            final StageRecords.Carried stages = project.stages(issue);
            sources.put(
                    issue.id(),
                    new CarryOverPlan.IssueSource(
                            issue,
                            head.priority(),
                            head.rank(),
                            head.covers(),
                            project.below(issue),
                            stages.stages(),
                            stages.moves(),
                            stages.estimate()));
        }
        final Map<String, String> texts = new LinkedHashMap<>();
        for (final Story story : board.stories()) {
            texts.put(story.id(), CheckoutProject.storyText(story));
        }
        return CarryOverPlan.of(board, sources, texts, CarryOverPlan.References.BY_ALIAS);
    }

    private static int usage(final PrintStream err) {
        err.println("usage: " + USAGE);
        return WorkCommands.UNANSWERED;
    }
}
