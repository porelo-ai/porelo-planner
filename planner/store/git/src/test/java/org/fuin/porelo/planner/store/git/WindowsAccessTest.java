package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WindowsAccessTest {

    private static final String ME = "S-1-5-21-1004336348-1177238915-682003330-1001";

    private static final String OTHER = "S-1-5-21-1004336348-1177238915-682003330-1002";

    private static final String USERS = "S-1-5-32-545";

    private static final String AUTHENTICATED = "S-1-5-11";

    private static final String CREATOR_OWNER = "S-1-3-0";

    /**
     * C:\, C:\Users, the profile, AppData, Local and porelo-planner as a fresh account has them.
     */
    private static String fresh(final String planner) {
        return "self "
                + ME
                + "\n"
                + "path 0 "
                + WindowsAccess.TRUSTED_INSTALLER
                + " 0 d\n"
                + "ace 0 A S-1-5-32-544 2032127 0 0\n"
                + "ace 0 A S-1-5-18 2032127 3 0\n"
                + "ace 0 A "
                + USERS
                + " 1179817 3 0\n"
                + "ace 0 A "
                + AUTHENTICATED
                + " 4 0 0\n"
                + "ace 0 A "
                + AUTHENTICATED
                + " -536805376 3 2\n"
                + "ace 0 A "
                + CREATOR_OWNER
                + " 268435456 3 2\n"
                + "path 1 S-1-5-18 0 d\n"
                + "ace 1 A S-1-5-18 2032127 3 0\n"
                + "ace 1 A S-1-5-32-544 2032127 3 0\n"
                + "ace 1 A "
                + USERS
                + " 1179817 3 0\n"
                + "path 2 "
                + ME
                + " 0 d\n"
                + "ace 2 A S-1-5-18 2032127 3 0\n"
                + "ace 2 A S-1-5-32-544 2032127 3 0\n"
                + "ace 2 A "
                + ME
                + " 2032127 3 0\n"
                + "path 3 "
                + ME
                + " 0 d\n"
                + "ace 3 A "
                + ME
                + " 2032127 3 0\n"
                + planner;
    }

    private static final String OWN = "path 4 " + ME + " 0 d\nace 4 A " + ME + " 2032127 3 0\n";

    private static void check(final String answer) {
        final WindowsAccess access = WindowsAccess.parse(answer, 5);
        for (int i = 0; i < 4; i++) {
            access.requireSafeAncestor("dir " + i, i);
        }
        access.requireOwnersAlone("base", 3, true, false);
        access.requireOwnersAlone("porelo-planner", 4, true, true);
    }

    @Test
    void aFreshAccountsDirectoriesAndThePlannersOwnPass() {
        // The root lets every account make a folder and hands creators full control below; neither
        // lets one account move another's directory away.
        assertThatCode(() -> check(fresh(OWN)))
                .as("the defaults of a fresh account are accepted")
                .doesNotThrowAnyException();
    }

    @Test
    void anotherAccountsReadEntryOnThePlannersDirectoryIsRefused() {
        // The token in planner.json would be readable by every user of the machine.
        assertThatThrownBy(() -> check(fresh(OWN + "ace 4 A " + USERS + " 1179817 3 0\n")))
                .as("an entry for Users")
                .hasMessageContaining("lets " + USERS + " in");
    }

    @Test
    void anotherAccountsInheritOnlyEntryOnThePlannersDirectoryIsRefused() {
        // It reaches every file made below, the token and the clones among them.
        assertThatThrownBy(() -> check(fresh(OWN + "ace 4 A " + OTHER + " 2032127 3 2\n")))
                .as("an inherit-only entry for another account")
                .hasMessageContaining("lets " + OTHER + " in");
    }

    @Test
    void aPlannersDirectoryOwnedByAnotherAccountIsRefused() {
        // Another account made it first and can change its list at will.
        assertThatThrownBy(
                        () ->
                                check(
                                        fresh(
                                                "path 4 "
                                                        + OTHER
                                                        + " 0 d\nace 4 A "
                                                        + ME
                                                        + " 2032127 3 0\n")))
                .as("a directory another account owns")
                .hasMessageContaining("belongs to " + OTHER);
    }

    @Test
    void directoriesAnElevatedPlannerMadeOwnedByAdministratorsPass() {
        // An elevated process makes what it creates the Administrators' own, who can read
        // everything anyway; refusing it would keep an elevated planner from starting at all.
        final String answer =
                fresh(
                                "path 4 "
                                        + WindowsAccess.ADMINISTRATORS
                                        + " 0 d\nace 4 A "
                                        + ME
                                        + " 2032127 3 0\n")
                        .replace(
                                "path 3 " + ME + " 0 d\n",
                                "path 3 " + WindowsAccess.ADMINISTRATORS + " 0 d\n");
        assertThatCode(() -> check(answer))
                .as("a base and a planner's directory the Administrators own")
                .doesNotThrowAnyException();
    }

    @Test
    void aJunctionInPlaceOfThePlannersDirectoryIsRefused() {
        assertThatThrownBy(
                        () ->
                                check(
                                        fresh(
                                                "path 4 "
                                                        + ME
                                                        + " 1 d\nace 4 A "
                                                        + ME
                                                        + " 2032127 3 0\n")))
                .as("a reparse point")
                .hasMessageContaining("link or junction");
    }

    @Test
    void aPlannersDirectoryWhoseEntryIsNotInheritedIsRefused() {
        // Git makes its files with no list of their own; they would take the account's default.
        assertThatThrownBy(
                        () ->
                                check(
                                        fresh(
                                                "path 4 "
                                                        + ME
                                                        + " 0 d\nace 4 A "
                                                        + ME
                                                        + " 2032127 0 0\n")))
                .as("full control for the account that nothing below inherits")
                .hasMessageContaining("does not pass");
    }

    @Test
    void anAncestorAnotherAccountMayDeleteBelowIsRefused() {
        // It could rename the profile away and put its own in its place.
        final String answer =
                fresh(OWN)
                        .replace(
                                "ace 1 A " + USERS + " 1179817 3 0",
                                "ace 1 A " + USERS + " 1245631 3 0");
        assertThatThrownBy(() -> check(answer))
                .as("Users with modify on C:\\Users")
                .hasMessageContaining("lets " + USERS + " rename, delete or re-permission");
    }

    @Test
    void aBaseAnotherAccountMayAddToIsRefused() {
        // It could make porelo-planner before the planner does.
        final String answer =
                fresh(OWN)
                        .replace(
                                "ace 3 A " + ME + " 2032127 3 0\n",
                                "ace 3 A " + ME + " 2032127 3 0\nace 3 A " + USERS + " 4 0 0\n");
        assertThatThrownBy(() -> check(answer))
                .as("Users may make folders in %LOCALAPPDATA%")
                .hasMessageContaining("lets " + USERS + " in");
    }

    @Test
    void anAnswerOutOfItsFormOrShortOfAPathIsRefused() {
        // What cannot be read is never taken as clean.
        assertThatThrownBy(() -> WindowsAccess.parse(fresh(OWN), 6))
                .as("an answer for fewer paths than asked")
                .hasMessageContaining("5 of 6");
        assertThatThrownBy(() -> WindowsAccess.parse(fresh(OWN) + "ace 4 A Everyone 1 0 0\n", 5))
                .as("an account by name, not by SID")
                .hasMessageContaining("out of its form");
        assertThatThrownBy(() -> WindowsAccess.parse("path 0 " + ME + " 0 d\n", 1))
                .as("an answer without the account")
                .hasMessageContaining("without the account");
    }
}
