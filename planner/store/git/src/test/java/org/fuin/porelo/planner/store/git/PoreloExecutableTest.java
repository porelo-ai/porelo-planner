package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.git.PoreloExecutable.OnPosix;
import org.fuin.porelo.planner.store.git.PoreloExecutable.OnPosix.Kind;
import org.fuin.porelo.planner.store.git.PoreloExecutable.OnPosix.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The check of Porelo's executable before Git may run it with a login. Harm guarded throughout: a
 * login lent through a program another account placed or could change.
 */
class PoreloExecutableTest {

    private static final int ME = 1000;

    private static final int OTHER = 1001;

    private static final String PORELO = "/opt/porelo/bin/porelo";

    /** The file system as a package manager leaves it: root's, written by root alone. */
    private static final class Fake implements OnPosix.Nodes {

        final Map<Path, Node> nodes = new HashMap<>();

        final Map<Path, Path> links = new HashMap<>();

        boolean unreadable;

        final java.util.Set<Path> privateGroups = new java.util.HashSet<>();

        Fake() {
            nodes.put(Path.of("/"), new Node(Kind.DIRECTORY, 0, 0755, true));
            nodes.put(Path.of("/opt"), new Node(Kind.DIRECTORY, 0, 0755, true));
            nodes.put(Path.of("/opt/porelo"), new Node(Kind.DIRECTORY, 0, 0755, true));
            nodes.put(Path.of("/opt/porelo/bin"), new Node(Kind.DIRECTORY, 0, 0755, true));
            nodes.put(Path.of(PORELO), new Node(Kind.FILE, 0, 0755, true));
        }

        @Override
        public int self() {
            return ME;
        }

        @Override
        public boolean privateGroup(final Path path) {
            return privateGroups.contains(path);
        }

        @Override
        public Path real(final Path path) throws IOException {
            if (unreadable) {
                throw new IOException("Permission denied");
            }
            return links.getOrDefault(path, path);
        }

        @Override
        public Node node(final Path path) throws IOException {
            final Node node = nodes.get(path);
            if (node == null) {
                throw new IOException(path + ": no such file");
            }
            return node;
        }
    }

    private static String refusal(final Fake fake, final String value) {
        return assertThatThrownBy(() -> new OnPosix(fake).checked(value))
                .as("'%s' is refused", value)
                .isInstanceOfAny(IllegalStateException.class, UncheckedIOException.class)
                .actual()
                .getMessage();
    }

    @Test
    void aProgramOnlyRootOrTheAccountCanChangeIsTaken() {
        assertThat(new OnPosix(new Fake()).checked(PORELO))
                .as("installed by the system, under directories only root writes")
                .isEqualTo(PORELO);
        final Fake own = new Fake();
        own.nodes.put(Path.of("/opt/porelo/bin"), new Node(Kind.DIRECTORY, ME, 0700, true));
        own.nodes.put(Path.of(PORELO), new Node(Kind.FILE, ME, 0700, true));
        assertThat(new OnPosix(own).checked(PORELO)).as("the account's own copy").isEqualTo(PORELO);
    }

    @Test
    void aRelativeOrDottedPathIsRefused() {
        assertThat(refusal(new Fake(), "porelo"))
                .as("the refusal says why")
                .contains("not an absolute path");
        assertThat(refusal(new Fake(), "bin/porelo"))
                .as("the refusal says why")
                .contains("not an absolute path");
        assertThat(refusal(new Fake(), "/opt/porelo/../porelo/bin/porelo"))
                .as("the refusal says why")
                .contains("not an absolute path");
    }

    @Test
    void aLinkOnTheWayOrTheProgramAsALinkIsRefused() {
        final Fake linked = new Fake();
        linked.links.put(Path.of(PORELO), Path.of("/home/other/porelo"));
        assertThat(refusal(linked, PORELO))
                .as("the refusal says why")
                .contains("reached through a link");
        final Fake link = new Fake();
        link.nodes.put(Path.of(PORELO), new Node(Kind.OTHER, 0, 0777, true));
        assertThat(refusal(link, PORELO)).as("the refusal says why").contains("not a regular file");
        final Fake directoryLink = new Fake();
        directoryLink.nodes.put(Path.of("/opt/porelo"), new Node(Kind.OTHER, 0, 0777, true));
        assertThat(refusal(directoryLink, PORELO))
                .as("the refusal says why")
                .contains("not a directory, or is a link");
    }

    @Test
    void aProgramAnotherAccountOwnsOrMayWriteIsRefused() {
        final Fake owned = new Fake();
        owned.nodes.put(Path.of(PORELO), new Node(Kind.FILE, OTHER, 0755, true));
        assertThat(refusal(owned, PORELO))
                .as("the refusal says why")
                .contains("belongs to account 1001");
        final Fake group = new Fake();
        group.nodes.put(Path.of(PORELO), new Node(Kind.FILE, 0, 0775, true));
        assertThat(refusal(group, PORELO))
                .as("the refusal says why")
                .contains("a group other than this account's own private one can write");
        final Fake others = new Fake();
        others.nodes.put(Path.of(PORELO), new Node(Kind.FILE, ME, 0702, true));
        assertThat(refusal(others, PORELO)).as("the refusal says why").contains("others can write");
    }

    @Test
    void aDirectoryAboveThatAnotherAccountOwnsOrMayWriteIsRefused() {
        final Fake owned = new Fake();
        owned.nodes.put(Path.of("/opt/porelo"), new Node(Kind.DIRECTORY, OTHER, 0755, true));
        assertThat(refusal(owned, PORELO))
                .as("the refusal says why")
                .contains("/opt/porelo belongs to account 1001");
        // A POSIX ACL granting a write shows in the group bits as its mask.
        final Fake acl = new Fake();
        acl.nodes.put(Path.of("/opt"), new Node(Kind.DIRECTORY, 0, 0775, true));
        assertThat(refusal(acl, PORELO))
                .as("the refusal says why")
                .contains("/opt is a directory a group other than this account's own private one");
        final Fake root = new Fake();
        root.nodes.put(Path.of("/"), new Node(Kind.DIRECTORY, 0, 0757, true));
        assertThat(refusal(root, PORELO))
                .as("the refusal says why")
                .contains("/ is a directory others can write");
    }

    @Test
    void aFolderOnlyTheAccountsOwnPrivateGroupMayWriteIsTaken() {
        // Harm: a person's own ~/.local/bin under umask 002 lends no login, so no repository reads.
        final Fake own = new Fake();
        own.nodes.put(Path.of("/opt/porelo/bin"), new Node(Kind.DIRECTORY, ME, 0775, true));
        own.nodes.put(Path.of(PORELO), new Node(Kind.FILE, ME, 0775, true));
        own.privateGroups.add(Path.of("/opt/porelo/bin"));
        own.privateGroups.add(Path.of(PORELO));
        assertThat(new OnPosix(own).checked(PORELO))
                .as("the folder and the program belong to the account's own private group")
                .isEqualTo(PORELO);
    }

    @Test
    void aStickyFolderOthersMayWriteIsRefused() {
        final Fake sticky = new Fake();
        sticky.nodes.put(Path.of("/opt/porelo"), new Node(Kind.DIRECTORY, 0, 01777, true));
        assertThat(refusal(sticky, PORELO))
                .as("whoever may add a name there may add the program's own")
                .contains("/opt/porelo is a directory others can write");
    }

    @Test
    void aGroupIsPrivateOnlyWithNoOtherAccountInIt() {
        final List<String> passwd =
                List.of("root:x:0:0::/root:/bin/sh", "person:x:1000:1000::/nonexistent:/bin/sh");
        final List<String> group = List.of("root:x:0:", "person:x:1000:");
        assertThat(PoreloExecutable.privateGroup(1000, 1000, passwd, group))
                .as("the person's own group, nobody else in it")
                .isTrue();
        assertThat(
                        PoreloExecutable.privateGroup(
                                1000,
                                1000,
                                List.of(
                                        passwd.get(0),
                                        passwd.get(1),
                                        "other:x:1001:1000::/:/bin/sh"),
                                group))
                .as("another account has it as its primary group")
                .isFalse();
        assertThat(
                        PoreloExecutable.privateGroup(
                                1000, 1000, passwd, List.of("root:x:0:", "person:x:1000:other")))
                .as("another account is a member")
                .isFalse();
        assertThat(PoreloExecutable.privateGroup(1000, 1000, passwd, List.of("root:x:0:")))
                .as("a group the account files do not name")
                .isFalse();
    }

    @Test
    void aProgramTheAccountMayNotRunIsRefused() {
        final Fake fake = new Fake();
        fake.nodes.put(Path.of(PORELO), new Node(Kind.FILE, 0, 0644, false));
        assertThat(refusal(fake, PORELO)).as("the refusal says why").contains("may run");
    }

    @Test
    void aCheckThatCannotRunRefuses() {
        final Fake unreadable = new Fake();
        unreadable.unreadable = true;
        assertThat(refusal(unreadable, PORELO))
                .as("the refusal says why")
                .contains("cannot be checked");
        final Fake missing = new Fake();
        missing.nodes.remove(Path.of("/opt"));
        assertThat(refusal(missing, PORELO))
                .as("the refusal says why")
                .contains("cannot be checked");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onTheRealFileSystemALinkInThePathIsRefusedAndTheRealPathTaken() throws IOException {
        // Not in the shared temporary directory, which others may write.
        final Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "porelo-");
        final Path bin = Files.createDirectory(dir.resolve("bin"));
        // The umask may leave the group a write; the directory is made the account's alone.
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"));
        final Path program = Files.writeString(bin.resolve("porelo"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        final Path link = Files.createSymbolicLink(dir.resolve("link"), bin);
        final OnPosix check = new OnPosix(new OnPosix.RealNodes());
        final String real = program.toRealPath().toString();
        assertThat(check.checked(real)).as("the program by its real path").isEqualTo(real);
        assertThatThrownBy(() -> check.checked(dir.toRealPath().resolve("link/porelo").toString()))
                .as("the same program through a link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reached through a link");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx---rw-"));
        assertThatThrownBy(() -> check.checked(real))
                .as("the program once others may write it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("others can write");
    }

    /** Windows, as the shell's answer and Java's real path tell it. */
    private static final class Windows {

        private static final String SELF = "S-1-5-21-1004336348-1177238915-682003330-1001";

        private static final String OTHER_SID = "S-1-5-21-1004336348-1177238915-682003330-1002";

        private static final String USERS = "S-1-5-32-545";

        private static final String AUTHENTICATED = "S-1-5-11";

        static final String PROGRAM =
                "C:\\Users\\person\\AppData\\Local\\Programs\\porelo\\porelo.exe";

        /** Read and run for Users, full control for the account, SYSTEM and Administrators. */
        static String entries(final int index, final String extra) {
            return "ace "
                    + index
                    + " A S-1-5-18 2032127 3 0\n"
                    + "ace "
                    + index
                    + " A S-1-5-32-544 2032127 3 0\n"
                    + "ace "
                    + index
                    + " A "
                    + SELF
                    + " 2032127 3 0\n"
                    + "ace "
                    + index
                    + " A "
                    + USERS
                    + " 1179817 3 0\n"
                    + extra;
        }

        /** C:\ to the program's directory, then the program, each with the entries given. */
        static String answer(final Map<Integer, String> extra, final Map<Integer, String> lines) {
            final StringBuilder text = new StringBuilder("self " + SELF + "\n");
            for (int i = 0; i < 8; i++) {
                text.append(
                        lines.getOrDefault(
                                i,
                                "path "
                                        + i
                                        + " "
                                        + (i < 2 ? "S-1-5-18" : SELF)
                                        + " 0 "
                                        + (i < 7 ? "d" : "f")
                                        + "\n"));
                text.append(entries(i, extra.getOrDefault(i, "")));
            }
            return text.toString();
        }

        static PoreloExecutable.OnWindows check(
                final String answer, final String real, final boolean executable) {
            return new PoreloExecutable.OnWindows(
                    new PoreloExecutable.OnWindows.Paths() {
                        @Override
                        public String real(final String path) {
                            return real;
                        }

                        @Override
                        public boolean executable(final String path) {
                            return executable;
                        }
                    },
                    (protect, paths) -> {
                        assertThat(protect).as("nothing is protected, only read").isEmpty();
                        assertThat(paths)
                                .as("each directory from the drive down, then the program")
                                .isEqualTo(
                                        "C:\\\n"
                                                + "C:\\Users\n"
                                                + "C:\\Users\\person\n"
                                                + "C:\\Users\\person\\AppData\n"
                                                + "C:\\Users\\person\\AppData\\Local\n"
                                                + "C:\\Users\\person\\AppData\\Local\\Programs\n"
                                                + "C:\\Users\\person\\AppData\\Local\\Programs\\porelo\n"
                                                + PROGRAM
                                                + "\n");
                        return answer;
                    });
        }
    }

    @Test
    void onWindowsAProgramOnlyTheAccountAndTheSystemCanChangeIsTaken() {
        assertThat(
                        Windows.check(Windows.answer(Map.of(), Map.of()), Windows.PROGRAM, true)
                                .checked(Windows.PROGRAM))
                .as("a program in the account's own programs folder")
                .isEqualTo(Windows.PROGRAM);
    }

    private static String windowsRefusal(
            final String value, final String answer, final String real, final boolean executable) {
        return assertThatThrownBy(() -> Windows.check(answer, real, executable).checked(value))
                .as("'%s' is refused", value)
                .isInstanceOf(IllegalStateException.class)
                .actual()
                .getMessage();
    }

    @Test
    void onWindowsARelativeShareOrDottedPathIsRefused() {
        for (final String value :
                List.of(
                        "porelo.exe",
                        "C:porelo.exe",
                        "\\\\server\\share\\porelo.exe",
                        "\\\\?\\C:\\porelo.exe",
                        "C:\\Users\\..\\porelo.exe",
                        "C:\\Users\\.\\porelo.exe",
                        "C:\\Users\\\\porelo.exe")) {
            assertThat(windowsRefusal(value, "", value, true))
                    .as("the refusal says why")
                    .contains("not a drive's absolute path");
        }
    }

    @Test
    void onWindowsALinkOrJunctionOnTheWayIsRefused() {
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(Map.of(), Map.of()),
                                "D:\\elsewhere\\porelo.exe",
                                true))
                .as("the refusal says why")
                .contains("reached through a link or junction");
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(), Map.of(4, "path 4 " + Windows.SELF + " 1 d\n")),
                                Windows.PROGRAM,
                                true))
                .as("the refusal says why")
                .contains("is a link or junction");
    }

    @Test
    void onWindowsAProgramAnotherAccountOwnsOrMayWriteIsRefused() {
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(),
                                        Map.of(7, "path 7 " + Windows.OTHER_SID + " 0 f\n")),
                                Windows.PROGRAM,
                                true))
                .as("the refusal says why")
                .contains("belongs to " + Windows.OTHER_SID);
        // Write data alone, as an entry inherited from the folder above gives it.
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(7, "ace 7 A " + Windows.AUTHENTICATED + " 2 0 0\n"),
                                        Map.of()),
                                Windows.PROGRAM,
                                true))
                .as("the refusal says why")
                .contains("lets " + Windows.AUTHENTICATED + " write");
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(7, "ace 7 A " + Windows.OTHER_SID + " 524288 0 0\n"),
                                        Map.of()),
                                Windows.PROGRAM,
                                true))
                .as("taking ownership")
                .contains("lets " + Windows.OTHER_SID);
    }

    @Test
    void onWindowsADirectoryAboveAnotherAccountMayRenameDeleteOrTakeIsRefused() {
        // Modify for every signed-in account, inherited by the folder from its parent.
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(
                                                5,
                                                "ace 5 A "
                                                        + Windows.AUTHENTICATED
                                                        + " 1245631 3 0\n"),
                                        Map.of()),
                                Windows.PROGRAM,
                                true))
                .as("the refusal says why")
                .contains("lets " + Windows.AUTHENTICATED + " rename, delete");
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(
                                        Map.of(),
                                        Map.of(3, "path 3 " + Windows.OTHER_SID + " 0 d\n")),
                                Windows.PROGRAM,
                                true))
                .as("the refusal says why")
                .contains("belongs to " + Windows.OTHER_SID);
    }

    @Test
    void onWindowsAProgramTheAccountMayNotRunOrACheckThatCannotRunIsRefused() {
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                Windows.answer(Map.of(), Map.of()),
                                Windows.PROGRAM,
                                false))
                .as("the refusal says why")
                .contains("may run");
        assertThat(
                        windowsRefusal(
                                Windows.PROGRAM,
                                "self " + Windows.SELF + "\n",
                                Windows.PROGRAM,
                                true))
                .as("an answer that misses the paths")
                .contains("Windows answered for 0 of 8");
        final PoreloExecutable.OnWindows failing =
                new PoreloExecutable.OnWindows(
                        new PoreloExecutable.OnWindows.Paths() {
                            @Override
                            public String real(final String path) throws IOException {
                                throw new IOException("Access is denied");
                            }

                            @Override
                            public boolean executable(final String path) {
                                return true;
                            }
                        },
                        (protect, paths) -> {
                            throw new IllegalStateException("powershell exited 1");
                        });
        assertThatThrownBy(() -> failing.checked(Windows.PROGRAM))
                .as("a real path that cannot be read")
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("cannot be checked");
    }
}
