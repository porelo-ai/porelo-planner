package org.fuin.porelo.planner.cleanup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads which snapshot builds the repositories pin: every snapshot version written anywhere on a
 * repository's default branch, in a workflow, a script or a pom alike, so a pin is never missed for
 * where it stands. Keeping a build some text only mentions costs nothing; deleting a pinned one
 * breaks a repository's CI.
 */
public final class Pins {

    // "~" percent-encoded too, as a pin written as a URL may carry it.
    private static final Pattern SNAPSHOT =
            Pattern.compile(
                    "(?<![0-9.])([0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9})(?:~|%7[Ee])snapshot\\.([0-9]{1,9})(?![0-9])");

    private Pins() {}

    /**
     * Reads the pins of every named repository from its checkout, or refuses.
     *
     * @param checkouts the directory holding one checkout of each repository's default branch,
     *     under its name
     * @param names every repository the project file names
     * @return every snapshot version written in any of them, as {@code x.y.z~snapshot.N}
     * @throws Unreadable when a repository has no checkout, or a file of one cannot be read
     */
    public static Set<String> read(final Path checkouts, final Collection<String> names)
            throws Unreadable {
        if (names.isEmpty()) {
            throw new Unreadable("the project file names no repository");
        }
        final Set<String> pins = new TreeSet<>();
        for (final String name : names) {
            final Path checkout = checkouts.resolve(name);
            if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(checkout.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new Unreadable(
                        "repository "
                                + name
                                + " has no checkout of its default branch at "
                                + checkout);
            }
            try {
                Files.walkFileTree(checkout, new Reader(checkout.resolve(".git"), pins));
            } catch (final IOException e) {
                throw new Unreadable("repository " + name + " could not be read whole", e);
            }
        }
        return Set.copyOf(pins);
    }

    /**
     * Answers every snapshot version a text names.
     *
     * @param text any text
     * @param into where the versions go, as {@code x.y.z~snapshot.N}
     */
    static void scan(final String text, final Set<String> into) {
        final Matcher match = SNAPSHOT.matcher(text);
        while (match.find()) {
            into.add(match.group(1) + "~snapshot." + match.group(2));
        }
    }

    /** Reads every regular file but Git's own; a link is not followed out of the checkout. */
    private static final class Reader extends SimpleFileVisitor<Path> {

        private final Path git;

        private final Set<String> pins;

        Reader(final Path git, final Set<String> pins) {
            this.git = git;
            this.pins = pins;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
            return dir.equals(git) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                throws IOException {
            if (attrs.isSymbolicLink()) {
                // A tracked link may name the executable by its target; the link is not followed.
                scan(Files.readSymbolicLink(file).toString(), pins);
            } else if (attrs.isRegularFile()) {
                final byte[] bytes = Files.readAllBytes(file);
                // ISO-8859-1 maps every byte, so no file fails to decode; UTF-16 for a script
                // written so, such as PowerShell's, in either byte order, with or without a BOM.
                scan(new String(bytes, StandardCharsets.ISO_8859_1), pins);
                scan(new String(bytes, StandardCharsets.UTF_16LE), pins);
                scan(new String(bytes, StandardCharsets.UTF_16BE), pins);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException e)
                throws IOException {
            throw e;
        }
    }
}
