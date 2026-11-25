package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.core.TaskReport;
import org.fuin.porelo.planner.core.TaskReports;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A discussion's idea, reported as a task's text, read by the planner's idea format and saved. */
class IdeaReportsTest {

    private static final String OFFER = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";

    private static final String TASK = "porelo-alpha_talk";

    private static final String IDEA =
            "{\"story\": {\"title\": \" Tag stories \", \"gain\": \"People find stories\","
                    + " \"test\": \"Tag one\", \"priority\": \"High\"}, \"issues\":\n"
                    + " [{\"repository\": \"porelo-other\", \"title\": \"Tags\", \"mustBeTrue\":"
                    + " \"A story can be tagged.\", \"size\": \"S\", \"worker\": \"Manual\"}]}";

    private final List<NewIdea> added = new ArrayList<>();

    private final List<TaskReport> reports = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private Board current = board("r1");

    private WorkRun work;

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
                org.fuin.porelo.planner.store.ProjectRepository.named(
                        List.of("porelo-store", "porelo-other")));
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        final RuntimeException refused = failure;
                        if (refused != null) {
                            throw refused;
                        }
                        added.add(idea);
                        current = IdeaReportsTest.board("r" + (added.size() + 1));
                        return new NewIdea.Added("ST" + added.size(), List.of("CD3"), current);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
        work = projects.work("alpha");
        work.discussed(OFFER);
    }

    private ReportTaking.Outcome report(final String text) {
        reports.add(new TaskReport(reports.size() + 1, "idea", text, "2026-11-25T08:00:01Z"));
        return work.takeReports("alpha", records(), page(), WorkRequests::ideaText);
    }

    private TaskRecords records() {
        return (project, since) ->
                List.of(
                        new TaskRecord(
                                TASK,
                                "alpha",
                                null,
                                List.of(),
                                Instant.now(Clock.systemUTC()),
                                null,
                                null,
                                List.of(),
                                OFFER));
    }

    private TaskReports page() {
        return (container, after) -> {
            final List<TaskReport> later =
                    reports.stream().filter(report -> report.seq() > after).toList();
            return new TaskReports.Page(later, reports.size(), false);
        };
    }

    @Test
    void aReportedIdeaIsSavedOnceInTheIdeaFormat() {
        // Harm: an idea of several lines refused, or its issues saved for another worker than the
        // person chose.
        final ReportTaking.Outcome outcome = report(IDEA);
        work.takeReports("alpha", records(), page(), WorkRequests::ideaText);

        assertThat(outcome.stories()).containsExactly(TASK + "#1 ST1");
        assertThat(added).as("one save").hasSize(1);
        final NewIdea idea = added.getFirst();
        assertThat(idea.title()).as("the story's title, stripped").isEqualTo("Tag stories");
        assertThat(idea.issues())
                .singleElement()
                .satisfies(
                        issue -> {
                            assertThat(issue.repository()).isEqualTo("porelo-other");
                            assertThat(issue.worker()).isEqualTo(Worker.MANUAL);
                        });
    }

    @Test
    void aTextThatIsNoIdeaIsRefusedAndTakenWithoutRepeatingIt() {
        // Harm: a task's text quoted into the planner's log, or a broken text read every minute.
        final List<String> texts =
                List.of(
                        "secret not json",
                        "[\"secret\"]",
                        "\"secret\"",
                        IDEA + " \"secret\"",
                        "{\"secret\": 1}",
                        "[".repeat(30000) + "]".repeat(30000));
        for (final String text : texts) {
            final ReportTaking.Outcome outcome = report(text);
            assertThat(outcome.refused())
                    .as("the text of %s characters", text.length())
                    .singleElement()
                    .asString()
                    .contains("idea")
                    .doesNotContain("secret");
        }
        assertThat(added).as("nothing is saved").isEmpty();
        assertThat(work.takeReports("alpha", records(), page(), WorkRequests::ideaText).refused())
                .as("each taken")
                .isEmpty();
    }

    @Test
    void aMalformedIdeaListsItsFaultsByFieldWithoutRepeatingTheText() {
        // Harm: an idea half checked writes issues nobody can read; an echo leaks the text.
        final ReportTaking.Outcome outcome =
                report(
                        "{\"story\": {\"title\": \"secret\\n\", \"gain\": \"G\", \"test\": \"T\","
                                + " \"priority\": \"High\", \"secret\": 1}, \"issues\":"
                                + " [{\"repository\": \"secret repo\", \"title\": \"T\","
                                + " \"mustBeTrue\": \"W\", \"size\": 3, \"worker\": \"AGENT\"},"
                                + " 5]}");

        assertThat(outcome.refused())
                .singleElement()
                .asString()
                .contains("story: ", "story.title: ", "issues[0].repository: ", "issues[1]: ")
                .doesNotContain("secret");
        assertThat(added).isEmpty();
    }

    @Test
    void aStoreThatCannotWriteTheIdeaIsTakenWithItsWords() {
        // Harm: an idea the store refuses for good read again every minute, its reason unsaid.
        failure = new StoreException.NotSavable("", "The repository's issues give no prefix.");

        final ReportTaking.Outcome outcome = report(IDEA);

        assertThat(outcome.refused())
                .singleElement()
                .asString()
                .contains("The repository's issues give no prefix.");
        failure = null;
        assertThat(work.takeReports("alpha", records(), page(), WorkRequests::ideaText).stories())
                .as("not saved on the next read")
                .isEmpty();
    }
}
