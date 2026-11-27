package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.junit.jupiter.api.Test;

class IdeaDiscussionTest {

    @Test
    void theOfferNamesEveryRepositoryOfTheProjectAndCarriesTheIdeaFormat() {
        // An agent that misses a repository or the format writes an idea nobody can save.
        final IdeaDiscussion offer =
                IdeaDiscussion.offer(
                        WorkProposalTest.board(WorkRule.Per.REPOSITORY), "Let people tag stories");

        assertThat(offer.prompt())
                .as(
                        "each repository of the project, the own one among them, where the task reads it")
                .contains("/workspace/project/", "/workspace/alpha/", "/workspace/beta/");
        assertThat(offer.prompt())
                .as("the person's first words and the idea format the planner holds")
                .contains("Let people tag stories", "\"$id\": \"idea-1.json\"");
        // Harm: a discussion told to write a result file nobody reads, or to report its idea
        // under a kind the planner does not take, so the person's idea is lost.
        assertThat(offer.prompt())
                .as("the idea reported back through Report, kind idea, within Porelo's bound")
                .contains(
                        "through Porelo's Report",
                        "with the kind `idea`",
                        "at most 65536 characters")
                .doesNotContain("idea.json", "result");
        assertThat(IdeaDiscussion.format(List.of("project")))
                .as("the format asks who works each issue, never assuming it")
                .contains("\"$id\": \"idea-1.json\"", "\"worker\"", "\"Agent\"", "\"Manual\"");
        assertThat(List.of(offer.agent(), offer.model(), offer.mode()))
                .as("the first rule's first agent and model, in the conversation's mode")
                .containsExactly("zeta", "large", "AGENT");
    }

    @Test
    void theBriefNamesEachRepositoryBesideTheFolderTheTaskReadsItFrom() {
        // Harm: an agent sent to a folder that does not exist, or writing a name the planner
        // refuses, when a repository's folder is not its name.
        final IdeaDiscussion offer = IdeaDiscussion.offer(namedApart(), "Let people tag stories");

        assertThat(offer.prompt())
                .as("the own repository read in the project's name, the other in its key")
                .contains(
                        "`porelo-project`, read at `/workspace/porelo/`",
                        "`porelo-pi`, read at `/workspace/pi/`")
                .doesNotContain("/workspace/porelo-project/", "/workspace/porelo-pi/");
    }

    @Test
    void theIdeaFormatAllowsOnlyTheProjectsRepositoryNames() {
        // Harm: a discussion writes a repository the planner then refuses, and the idea is lost.
        final IdeaDiscussion offer = IdeaDiscussion.offer(namedApart(), "An idea");

        assertThat(offer.prompt())
                .as("the repository's names as the format's only values, the own one first")
                .contains("\"repository\": { \"enum\": [\"porelo-project\", \"porelo-pi\"]");
        assertThat(IdeaDiscussion.format(List.of("porelo-project", "porelo-pi")))
                .as("the format the brief carries")
                .isSubstringOf(offer.prompt());
    }

    @Test
    void theIdeaFormatTakesOnlyRepositoryNames() {
        // Harm: a name with a quote breaks the schema the agent is given, and one holding the
        // placeholder is put in place of the names a second time.
        for (final String name : new String[] {"a\"b", "[\"{repositories}\"]", "{repositories}"}) {
            assertThatThrownBy(() -> IdeaDiscussion.format(List.of("porelo", name)))
                    .as("the name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A project whose folders are not its repositories' names, as Porelo places them. */
    private static Board namedApart() {
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        return new Board(
                board.revision(),
                board.workflow(),
                List.of(),
                List.of(),
                board.work(),
                List.of(
                        new ProjectRepository("porelo-project", "porelo"),
                        new ProjectRepository("porelo-pi", "pi")));
    }

    @Test
    void wordsOfTheIdeaCannotCloseTheirQuote() {
        // Backticks in the person's words would otherwise end the quoted idea inside the brief.
        final IdeaDiscussion offer =
                IdeaDiscussion.offer(WorkProposalTest.board(WorkRule.Per.REPOSITORY), "a ```` b");

        assertThat(offer.prompt())
                .as("the idea is fenced longer than any backtick run it holds")
                .contains("`````\na ```` b\n`````");
    }

    @Test
    void anEmptyOrTooLongIdeaIsRefused() {
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);

        assertThatThrownBy(() -> IdeaDiscussion.offer(board, " \n"))
                .as("a discussion starts from some words")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> assertThat(refusal.name()).isEqualTo("InvalidSettings"));
        assertThatThrownBy(() -> IdeaDiscussion.offer(board, "x".repeat(4001)))
                .as("the first words are bounded as a comment is")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> assertThat(refusal.name()).isEqualTo("InvalidSettings"));
        assertThat(IdeaDiscussion.offer(board, "😀".repeat(4000)).prompt())
                .as("the bound counts characters, not UTF-16 units")
                .contains("😀".repeat(4000));
    }

    @Test
    void aProjectWithoutASoundWorkRuleIsRefused() {
        // Without a rule there is no agent and model the project offers.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final Board ruleless =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        board.issues(),
                        new Work(Map.of(), List.of()),
                        board.repositories());

        assertThatThrownBy(() -> IdeaDiscussion.offer(ruleless, "An idea"))
                .as("no agent is offered when the project names none")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> assertThat(refusal.name()).isEqualTo("NoDiscussion"));
    }

    @Test
    void aRuleWhoseFirstAgentHasNoUsableNameIsPassedOver() {
        // An overlong agent would reach Start, which refuses it after the person typed.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final WorkRule sound = board.work().rules().get("Building");
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        rules.put("Backlog", rule(sound, Map.of("a".repeat(129), "large")));
        rules.put("Building", sound);

        final IdeaDiscussion offer =
                IdeaDiscussion.offer(
                        new Board(
                                board.revision(),
                                board.workflow(),
                                board.stories(),
                                board.issues(),
                                new Work(rules, List.of()),
                                board.repositories()),
                        "An idea");

        assertThat(offer.agent()).as("the next rule's agent").isEqualTo("zeta");
    }

    @Test
    void aBriefOverItsBoundIsRefused() {
        // Start takes at most 64 KiB of brief; a longer one would fail in the window instead.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<ProjectRepository> many = new ArrayList<>(board.repositories());
        for (int i = 0; i < 700; i++) {
            many.add(ProjectRepository.named("r" + i + "-".repeat(90)));
        }

        assertThatThrownBy(
                        () ->
                                IdeaDiscussion.offer(
                                        new Board(
                                                board.revision(),
                                                board.workflow(),
                                                board.stories(),
                                                board.issues(),
                                                board.work(),
                                                many),
                                        "An idea"))
                .as("a project of many repositories cannot be named in one brief")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> assertThat(refusal.name()).isEqualTo("NoDiscussion"));
    }

    @Test
    void aProjectNamingNoRepositoryIsRefused() {
        // An idea needs a repository for each of its issues; without one the agent cannot write it.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);

        assertThatThrownBy(
                        () ->
                                IdeaDiscussion.offer(
                                        new Board(
                                                board.revision(),
                                                board.workflow(),
                                                List.of(),
                                                List.of(),
                                                board.work(),
                                                List.of()),
                                        "An idea"))
                .as("nothing to discuss the idea over")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> assertThat(refusal.name()).isEqualTo("NoDiscussion"));
    }

    private static WorkRule rule(final WorkRule like, final Map<String, String> models) {
        return new WorkRule(
                List.of("Building"),
                "Finished",
                null,
                null,
                like.per(),
                like.modes(),
                like.order(),
                models,
                Roles.forRule(null, null));
    }
}
