package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.model.AuthorAssociation;
import org.fuin.porelo.forge.github.model.Issue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryIssuesTest {

    private static final String OWN = "example-project";

    private static final String LIB = "example-lib";

    private final OpenIssues github = new OpenIssues();

    private final RepositoryIssues issues = new RepositoryIssues();

    private RepositoryIssues.Read read(final String... repositories) {
        return issues.read(List.of(repositories), github.fetch(), github.clock(), "a-token");
    }

    private static List<String> numbers(final RepositoryIssues.Read read) {
        return read.issues().stream().map(i -> i.repository() + "#" + i.number()).toList();
    }

    @Test
    void anIssuesAuthorIsListedOnlyByALoginAndAnAssociationGitHubNames() {
        // Harm: a person adds an issue anyone could open seeing only its title, or a text that is
        // no login reaches the window as the account that wrote it.
        github.holds(
                OWN,
                List.of(
                        OpenIssues.authored(OWN, 1, "a-person", AuthorAssociation.FIRST_TIMER),
                        OpenIssues.authored(OWN, 2, "no login", AuthorAssociation.OWNER),
                        OpenIssues.authored(OWN, 3, null, null)));

        final RepositoryIssues.Read read = read(OWN);

        assertThat(read.issues())
                .as("each issue's author and association")
                .extracting(Snapshot.Unlisted::author, Snapshot.Unlisted::authorAssociation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a-person", "FIRST_TIMER"),
                        org.assertj.core.groups.Tuple.tuple(null, "OWNER"),
                        org.assertj.core.groups.Tuple.tuple(null, null));
    }

    @Test
    void anIssueOnALaterPageIsListed() {
        // Harm: the older issues of a repository with more than a hundred are never shown.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 101));

        final RepositoryIssues.Read read = read(OWN);

        assertThat(numbers(read)).as("the issues listed").hasSize(101).contains(OWN + "#101");
        assertThat(github.asks).as("the pages asked for").hasSize(2);
        assertThat(read.faults()).as("the faults").isEmpty();
    }

    @Test
    void aPageFullOfPullRequestsStillLeadsToTheNextPage() {
        // Harm: an issue behind a page of mostly pull requests is never shown.
        final List<Issue> list = new ArrayList<>(OpenIssues.numbered(OWN, 1, 40));
        for (int n = 41; n <= 100; n++) {
            list.add(OpenIssues.pullRequest(OWN, n));
        }
        list.add(OpenIssues.issue(OWN, 101, "Behind the pull requests", null));
        github.holds(OWN, list);

        final RepositoryIssues.Read read = read(OWN);

        assertThat(numbers(read))
                .as("the issues listed, none of them a pull request")
                .hasSize(41)
                .contains(OWN + "#101")
                .doesNotContain(OWN + "#41", OWN + "#100");
    }

    @Test
    void anUnchangedPageKeepsItsIssuesAndIsAskedWithItsTag() {
        // Harm: the list empties while the repository is unchanged, or every read costs the rate
        // limit.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 101));
        read(OWN);
        github.asks.clear();

        final RepositoryIssues.Read again = read(OWN);

        assertThat(numbers(again)).as("the issues listed again").hasSize(101);
        assertThat(github.asks)
                .as("every page asked with the tag of its last answer")
                .allSatisfy(ask -> assertThat(ask.etag()).as("the tag sent").isNotNull());
        assertThat(again.unchanged()).as("the pages answered unchanged").isEqualTo(2);
    }

    @Test
    void aChangedRepositoryDoesNotReadAnotherRepositorysPagesAnew() {
        // Harm: one new issue in one repository costs the rate limit of every repository.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 5));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 150));
        read(OWN, LIB);
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 6));
        github.asks.clear();

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(github.asksOf(LIB))
                .as("the other repository's pages, each sent with its tag")
                .hasSize(2)
                .allSatisfy(ask -> assertThat(ask.etag()).as("the tag sent").isNotNull());
        assertThat(again.unchanged()).as("the pages answered unchanged").isEqualTo(2);
        assertThat(numbers(again)).as("the issues listed").hasSize(156);
    }

    @Test
    void aPageBeyondAListThatShrankIsDropped() {
        // Harm: an issue closed long ago is still shown from a page the list no longer has.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 105));
        read(OWN);
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 50));

        final RepositoryIssues.Read again = read(OWN);

        assertThat(numbers(again)).as("the issues listed").hasSize(50).doesNotContain(OWN + "#103");
    }

    @Test
    void aRepositoryOfManyPagesIsListedToItsEnd() {
        // Harm: a repository with thousands of open issues never listed, its issues missing from
        // "Issues not on the project", though GitHub still gave a next page.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 100 * 50 + 1));

        final RepositoryIssues.Read read = read(OWN);

        assertThat(numbers(read))
                .as("the issues listed, the last page's among them")
                .hasSize(100 * 50 + 1)
                .contains(OWN + "#5001");
        assertThat(read.faults()).as("the faults").isEmpty();
        assertThat(github.asks).as("the pages asked for").hasSize(51);
    }

    @Test
    void aRepositoryWhoseNextPageGitHubAnswersAsTheOneBeforeKeepsItsListAndIsNamed() {
        // Harm: a repository's list asked for without end on a GitHub that answers the same page
        // again, the repositories after it never read.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 3));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 4));
        read(OWN, LIB);
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 250));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 5));
        github.cycles.put(OWN, 1);
        github.asks.clear();

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(again.faults())
                .as("the one fault naming the repository")
                .containsExactly(
                        "GitHub answered the same page again for the issues of "
                                + OWN
                                + "; the list last read of it is kept");
        assertThat(numbers(again))
                .as("the list last read of it, the other repository's read anew")
                .hasSize(3 + 5);
        assertThat(github.asksOf(OWN)).as("its pages asked for").hasSize(2);
    }

    @Test
    void aRepositoryWhosePagesGitHubAnswersInACycleKeepsItsListAndIsNamed() {
        // Harm: a repository's list asked for until the deadline, or without end, on a GitHub that
        // answers two pages in turn, the repositories after it never read.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 3));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 4));
        read(OWN, LIB);
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 250));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 5));
        github.cycles.put(OWN, 2);
        github.asks.clear();

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(again.faults())
                .as("the one fault naming the repository")
                .containsExactly(
                        "GitHub answered the same page again for the issues of "
                                + OWN
                                + "; the list last read of it is kept");
        assertThat(numbers(again))
                .as("the list last read of it, the other repository's read anew")
                .hasSize(3 + 5);
        assertThat(github.asksOf(OWN)).as("its pages asked for, the first again last").hasSize(3);
    }

    @Test
    void aSpentDeadlineShowsTheListLastReadWithOneFault() {
        // Harm: a slow GitHub empties the list, or holds the board for minutes.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 3));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 4));
        read(OWN, LIB);
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 101));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 5));
        github.perAsk = RepositoryIssues.DEADLINE.plus(Duration.ofSeconds(1));
        github.asks.clear();

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(numbers(again)).as("the lists last read").hasSize(7);
        assertThat(github.asks).as("the pages asked for after the deadline").hasSize(1);
        assertThat(again.faults())
                .as("one fault naming the repositories not read")
                .singleElement()
                .asString()
                .contains(OWN, LIB);
    }

    @Test
    void aForbiddenRepositoryKeepsItsListAndIsNamedAlone() {
        // Harm: one repository the token may not list empties its issues or fails the board.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 2));
        github.holds(LIB, List.of(OpenIssues.issue(LIB, 9, "Secret plan", null)));
        read(OWN, LIB);
        github.fails(
                LIB,
                page ->
                        org.fuin.porelo.forge.github.GitHubRefusals.answered(
                                403,
                                "Resource not accessible by personal access token",
                                false,
                                "issues=read"));

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(numbers(again))
                .as("the lists")
                .containsExactly(OWN + "#1", OWN + "#2", LIB + "#9");
        assertThat(again.faults())
                .as("the fault naming the repository and GitHub's answer, never a title")
                .singleElement()
                .asString()
                .contains(LIB, "403", "Issues")
                .doesNotContain("Secret plan");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 410})
    void aMovedRepositoryOrOneWithoutIssuesKeepsItsListAndIsNamedAlone(final int status) {
        // Harm: a repository moved, or one whose issues were turned off, empties its list or
        // fails the board.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 2));
        github.holds(LIB, List.of(OpenIssues.issue(LIB, 9, "Secret plan", null)));
        read(OWN, LIB);
        github.fails(LIB, page -> OpenIssues.refused(status));

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(numbers(again))
                .as("the lists")
                .containsExactly(OWN + "#1", OWN + "#2", LIB + "#9");
        assertThat(again.faults())
                .as("the fault naming the repository and GitHub's status")
                .singleElement()
                .asString()
                .contains(LIB, Integer.toString(status));
    }

    @Test
    void aRepositoryTheLastReadDidNotReachIsReadFirst() {
        // Harm: a large repository keeps those after it unread at every read, so their issues
        // made by hand are never shown.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 100 * 30));
        github.holds(LIB, OpenIssues.numbered(LIB, 1, 100 * 30));
        // Each list takes 31 asks; the deadline leaves room for 41.
        github.perAsk = RepositoryIssues.DEADLINE.dividedBy(40);
        final RepositoryIssues.Read first = read(OWN, LIB);
        assertThat(numbers(first)).as("the lists of the first read").hasSize(3000);

        final RepositoryIssues.Read second = read(OWN, LIB);

        assertThat(numbers(second))
                .as("the lists of the second read, the second repository's among them")
                .hasSize(6000)
                .contains(LIB + "#3000");
        assertThat(second.faults())
                .as("the fault of the second read, naming the repository it did not reach")
                .singleElement()
                .asString()
                .contains(OWN)
                .doesNotContain(LIB);
    }

    @Test
    void aMissingRepositoryWithNoListHeldShowsNone() {
        // Harm: a repository gone or private to the token fails every read of the board.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 2));
        github.fails(LIB, page -> OpenIssues.refused(404));

        final RepositoryIssues.Read read = read(OWN, LIB);

        assertThat(numbers(read)).as("the lists").containsExactly(OWN + "#1", OWN + "#2");
        assertThat(read.faults())
                .as("the fault naming the repository")
                .singleElement()
                .asString()
                .contains(LIB, "404");
    }

    @Test
    void aSpentRateLimitStopsTheRead() {
        // Harm: a spent rate limit read as a repository's refusal, so the board shows its lists
        // as current and the reads go on against the limit.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 2));
        github.fails(
                LIB,
                page -> GitHubExceptions.rateLimited(403, "API rate limit", Duration.ofMinutes(5)));

        assertThatThrownBy(() -> read(OWN, LIB))
                .as("the read stopped by the rate limit")
                .isInstanceOf(GitHubException.class);
    }

    @Test
    void aRefusedTokenStopsTheRead() {
        // Harm: a token GitHub no longer takes read as a repository's refusal, so the person is
        // never asked for another.
        github.fails(OWN, page -> OpenIssues.refused(401));

        assertThatThrownBy(() -> read(OWN))
                .as("the read stopped by the refused token")
                .isInstanceOf(GitHubException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void upToTenRepositoriesTheBudgetIsTheDeadline(final int repositories) {
        // Harm: a board of a few repositories given more time than it needs, so a slow GitHub
        // holds the board longer.
        assertThat(issues.budget(repositories))
                .as("the budget of up to ten repositories")
                .isEqualTo(RepositoryIssues.DEADLINE);
    }

    @ParameterizedTest
    @CsvSource({"11,22", "20,40", "35,70"})
    void beyondTenRepositoriesTheBudgetGrowsByAShareOfTheDeadlineForEach(
            final int repositories, final int seconds) {
        // Harm: the lists of a board with many repositories are cut short at every load, since
        // they are asked one after another and take longer than one deadline.
        assertThat(issues.budget(repositories))
                .as("the budget of " + repositories + " repositories")
                .isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void aSpentBudgetOfManyRepositoriesNamesTheScaledBudgetNotTheDeadline() {
        // Harm: a person told the lists were cut at 20 s on a board that was given 40 s, so the
        // fault misleads what to change.
        final String[] names = new String[20];
        for (int n = 0; n < names.length; n++) {
            names[n] = "example-repository-" + n;
            github.holds(names[n], OpenIssues.numbered(names[n], 1, 2));
        }
        // The first ask leaves 25 s of the 40 s, the second 50 s: past the scaled budget, and
        // past the deadline already after the first.
        github.perAsk = Duration.ofSeconds(25);

        final RepositoryIssues.Read read = read(names);

        assertThat(github.asks).as("the pages asked within the scaled budget").hasSize(2);
        assertThat(read.faults())
                .as("one fault naming the scaled budget")
                .singleElement()
                .asString()
                .contains(RepositoryIssues.BUDGET_SPENT, "within 40 s");
    }
}
