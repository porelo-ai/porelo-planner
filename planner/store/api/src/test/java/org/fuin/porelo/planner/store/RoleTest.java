package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Role.Broken;
import org.fuin.porelo.planner.store.Role.End;
import org.fuin.porelo.planner.store.Role.Right;
import org.fuin.porelo.planner.store.Role.Workspace;
import org.fuin.porelo.planner.store.Role.WriteAs;
import org.junit.jupiter.api.Test;

/** A role answers what a task of it does by its parts, and names every rule it breaks. */
class RoleTest {

    private static Role role(
            final End end,
            final Workspace workspace,
            final boolean services,
            final Right... rights) {
        return new Role("tester", end, workspace, false, services, WriteAs.REVIEW, List.of(rights));
    }

    @Test
    void theFourExampleRolesBreakNoRule() {
        // Harm: a role every project is shown as an example would fail its own load.
        final Role author =
                new Role(
                        "author",
                        End.PUSHES_WORK,
                        Workspace.OWN,
                        false,
                        true,
                        WriteAs.AUTHOR,
                        List.of(Right.PUSH, Right.REPLY));
        final Role reviewer =
                role(End.GIVES_POINTS, Workspace.FOREIGN, false, Right.REPLY, Right.OPEN_THREADS);
        final Role tester =
                role(
                        End.REPORTS_FINDINGS,
                        Workspace.FOREIGN,
                        false,
                        Right.OPEN_THREADS,
                        Right.REPORT);
        final Role screener =
                role(
                        End.GIVES_POINTS,
                        Workspace.FOREIGN,
                        false,
                        Right.REPLY,
                        Right.OPEN_THREADS,
                        Right.REJECT);
        assertThat(List.of(author, reviewer, tester, screener))
                .as("the example roles are sound")
                .allSatisfy(role -> assertThat(role.broken()).as(role.name()).isEmpty());
        assertThat(author.pushesWork()).as("the author pushes work").isTrue();
        assertThat(author.needsOtherEyes()).as("the author is bound by no four eyes").isFalse();
        assertThat(tester.readsAnothersWork()).as("the tester reads another's work").isTrue();
        assertThat(tester.reportsFindings()).as("the tester reports findings").isTrue();
        assertThat(screener.judges()).as("the screener holds the judgment").isTrue();
        assertThat(List.of(reviewer, tester, screener))
                .as("who reports, gives points or judges needs other eyes")
                .allMatch(Role::needsOtherEyes);
    }

    @Test
    void aRoleNamesEachOfPorelosRulesItBreaksByPorelosWord() {
        // Harm: a role Porelo refuses at Start loads, and fails only once a task is started.
        assertThat(role(End.GIVES_POINTS, Workspace.FOREIGN, true, Right.OPEN_THREADS).broken())
                .as("another task's work with the project's services")
                .containsExactly(Broken.FOREIGN_WORK_LEADS_OUT);
        assertThat(
                        role(
                                        End.GIVES_POINTS,
                                        Workspace.FOREIGN,
                                        false,
                                        Right.PUSH,
                                        Right.OPEN_THREADS)
                                .broken())
                .as("another task's work with push")
                .containsExactly(
                        Broken.FOREIGN_WORK_LEADS_OUT,
                        Broken.PUSH_WITHOUT_OWN_WORK,
                        Broken.PUSH_NEEDS_END);
        assertThat(role(End.GIVES_POINTS, Workspace.FOREIGN, false).broken())
                .as("another task's work with no right")
                .containsExactly(Broken.FOREIGN_WORK_NAMES_NO_RIGHTS, Broken.POINTS_NEED_THREADS);
        assertThat(role(End.PUSHES_WORK, Workspace.OWN, true, Right.PUSH, Right.REJECT).broken())
                .as("a judgment beside push, over its own work")
                .containsExactly(
                        Broken.REJECT_WITH_PUSH,
                        Broken.REJECT_OVER_OWN_WORK,
                        Broken.REJECT_NEEDS_POINTS);
        assertThat(role(End.GIVES_POINTS, Workspace.READ_ONLY, false, Right.REJECT).broken())
                .as("a judgment without another task's work")
                .containsExactly(Broken.REJECT_OVER_OWN_WORK, Broken.POINTS_NEED_ANOTHERS_WORK);
        assertThat(role(End.PUSHES_WORK, Workspace.NONE, false, Right.PUSH).broken())
                .as("push without a workspace of its own")
                .containsExactly(Broken.PUSH_WITHOUT_OWN_WORK);
        assertThat(Broken.FOREIGN_WORK_LEADS_OUT.word())
                .as("the word Porelo's StartRuleBroken carries")
                .isEqualTo("foreignWorkLeadsOut");
    }

    @Test
    void aRoleWhoseEndNeedsARightItLacksBreaksThePlannersRule() {
        // Harm: a task that cannot push or report ends with nothing the planner can read.
        assertThat(role(End.PUSHES_WORK, Workspace.OWN, true, Right.REPLY).broken())
                .as("pushing work without push")
                .containsExactly(Broken.END_NEEDS_PUSH);
        assertThat(
                        role(End.REPORTS_FINDINGS, Workspace.FOREIGN, false, Right.OPEN_THREADS)
                                .broken())
                .as("reporting findings without report")
                .containsExactly(Broken.END_NEEDS_REPORT);
        assertThat(role(End.HANDS_BACK_RESULT, Workspace.READ_ONLY, true, Right.DONE).broken())
                .as("handing back a result without report")
                .containsExactly(Broken.END_NEEDS_REPORT);
        assertThat(role(End.HANDS_BACK_RESULT, Workspace.READ_ONLY, true, Right.REPORT).broken())
                .as("handing back a result with report")
                .isEmpty();
        assertThat(
                        role(End.REPORTS_FINDINGS, Workspace.OWN, true, Right.PUSH, Right.REPORT)
                                .broken())
                .as("a push nobody reads, from a role that ends by findings")
                .containsExactly(Broken.PUSH_NEEDS_END, Broken.FINDINGS_NEED_LANDED_WORK);
        assertThat(role(End.GIVES_POINTS, Workspace.READ_ONLY, true, Right.OPEN_THREADS).broken())
                .as("points with no other task's work to give them at")
                .containsExactly(Broken.POINTS_NEED_ANOTHERS_WORK);
        assertThat(
                        role(
                                        End.REPORTS_FINDINGS,
                                        Workspace.FOREIGN,
                                        false,
                                        Right.OPEN_THREADS,
                                        Right.REPORT,
                                        Right.REJECT)
                                .broken())
                .as("a judgment from a role that does not end by points")
                .containsExactly(Broken.REJECT_NEEDS_POINTS);
        assertThat(role(End.REPORTS_FINDINGS, Workspace.FOREIGN, false, Right.REPORT).broken())
                .as("findings at another task's work that can stand nowhere there")
                .containsExactly(Broken.FINDINGS_NEED_THREADS);
        assertThat(role(End.HANDS_BACK_RESULT, Workspace.FOREIGN, false, Right.REPORT).broken())
                .as("another task's work that is neither reported on nor given points")
                .containsExactly(Broken.FOREIGN_WORK_NEEDS_READING);
        assertThat(role(End.REPORTS_FINDINGS, Workspace.READ_ONLY, true, Right.REPORT).broken())
                .as("findings in the answer about no landed work")
                .containsExactly(Broken.FINDINGS_NEED_LANDED_WORK);
        assertThat(
                        new Role(
                                        "reader",
                                        End.REPORTS_FINDINGS,
                                        Workspace.NONE,
                                        true,
                                        true,
                                        WriteAs.AUTHOR,
                                        List.of(Right.REPORT))
                                .broken())
                .as("landed work read without a workspace to read it in")
                .containsExactly(Broken.READ_LANDED_NEEDS_WORKSPACE);
        assertThat(role(End.GIVES_POINTS, Workspace.FOREIGN, false, Right.REPLY).broken())
                .as("points that can neither open a thread nor reject")
                .containsExactly(Broken.POINTS_NEED_THREADS);
    }

    @Test
    void theRightsAreKeptOnceInPorelosOrderAndCannotBeChanged() {
        // Harm: a start hands Porelo a right twice or a list a caller changed after the load.
        final List<Right> given =
                new ArrayList<>(List.of(Right.REPORT, Right.OPEN_THREADS, Right.REPORT));
        final Role tester = role(End.REPORTS_FINDINGS, Workspace.FOREIGN, false);
        final Role withRights =
                new Role(
                        tester.name(),
                        tester.end(),
                        tester.workspace(),
                        true,
                        false,
                        WriteAs.REVIEW,
                        given);
        given.clear();
        assertThat(withRights.rights())
                .as("each right once, in Porelo's order")
                .containsExactly(Right.OPEN_THREADS, Right.REPORT);
        assertThat(tester.rights()).as("a role holding no right").isEmpty();
        assertThatThrownBy(() -> withRights.rights().add(Right.PUSH))
                .as("the rights cannot be changed")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void porelosWordsAreReadBackToTheirValuesAndAnUnknownWordToNone() {
        // Harm: a word Porelo does not have would reach its Start and be refused there.
        assertThat(Right.values())
                .as("every right is found by its word")
                .allSatisfy(
                        right ->
                                assertThat(Right.of(right.word()))
                                        .as("the right %s", right)
                                        .contains(right));
        assertThat(Workspace.values())
                .as("every workspace kind is found by its word")
                .allSatisfy(
                        kind ->
                                assertThat(Workspace.of(kind.word()))
                                        .as("the kind %s", kind)
                                        .contains(kind));
        assertThat(WriteAs.of("review")).as("the review account").contains(WriteAs.REVIEW);
        assertThat(Right.of("readOthers")).as("no right of Porelo's").isEmpty();
        assertThat(Workspace.of("foreignWork")).as("no workspace kind").isEmpty();
        assertThat(WriteAs.of("operator")).as("no account").isEmpty();
        assertThat(Right.RESOLVE_THREADS.word())
                .as("Porelo's spelling")
                .isEqualTo("resolveThreads");
        assertThat(Workspace.ALL_REPOSITORIES.word())
                .as("Porelo's spelling")
                .isEqualTo("allRepositories");
    }

    @Test
    void aNameThatIsBlankTooLongOrHoldsAControlOrFormatCharacterIsRefused() {
        // Harm: a role's name reaches the screen and the run record as given, and a direction
        // character in it turns the text shown around it.
        for (final String name :
                List.of(
                        " ",
                        "a".repeat(Role.LONGEST + 1),
                        "te\nster",
                        "tes\u202Eter",
                        "tes\u200Bter",
                        "\uFEFFtester",
                        "tes\u2066ter",
                        "tes\u061Cter")) {
            assertThatThrownBy(
                            () ->
                                    new Role(
                                            name,
                                            End.GIVES_POINTS,
                                            Workspace.FOREIGN,
                                            false,
                                            false,
                                            WriteAs.REVIEW,
                                            List.of(Right.REPLY)))
                    .as("the name '%s'", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
