package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.model.Issue;
import org.junit.jupiter.api.Test;

class RepositoryIssuesTest {

    private static final String OWN = "example-project";

    private static final String LIB = "example-lib";

    private final OpenIssues github = new OpenIssues();

    private final RepositoryIssues issues = new RepositoryIssues();

    private RepositoryIssues.Read read(final String... repositories) {
        return issues.read(List.of(repositories), github.fetch(), github.clock());
    }

    private static List<String> numbers(final RepositoryIssues.Read read) {
        return read.issues().stream().map(i -> i.repository() + "#" + i.number()).toList();
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
    void aRepositoryBeyondThePageBoundListsNoneOfItsIssues() {
        // Harm: a list cut short in silence, as if the issues past the bound did not exist.
        github.holds(OWN, OpenIssues.numbered(OWN, 1, 100 * RepositoryIssues.MOST_PAGES + 1));

        final RepositoryIssues.Read read = read(OWN);

        assertThat(read.issues()).as("the issues listed").isEmpty();
        assertThat(read.faults())
                .as("the fault naming the repository")
                .singleElement()
                .asString()
                .contains(OWN);
        assertThat(github.asks).as("the pages asked for").hasSize(RepositoryIssues.MOST_PAGES);
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
        github.fails(LIB, page -> OpenIssues.refused(403));

        final RepositoryIssues.Read again = read(OWN, LIB);

        assertThat(numbers(again))
                .as("the lists")
                .containsExactly(OWN + "#1", OWN + "#2", LIB + "#9");
        assertThat(again.faults())
                .as("the fault naming the repository and GitHub's answer, never a title")
                .singleElement()
                .asString()
                .contains(LIB, "403")
                .doesNotContain("Secret plan");
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
}
