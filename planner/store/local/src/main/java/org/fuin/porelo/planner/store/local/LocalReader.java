package org.fuin.porelo.planner.store.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.github.IssueMapping;
import org.fuin.porelo.planner.store.github.ItemView;

/**
 * Reads the items directory: only regular files of an item's name, none followed through a link,
 * each read with a bound; and the digest of their names, sizes, times and contents, which is the
 * board's revision.
 */
final class LocalReader {

    /** The largest item file read. */
    static final int MOST_BYTES = 1 << 20;

    /** The most bytes one read holds of all item files together; past it, none is read. */
    static final long MOST_TOTAL = 64L << 20;

    /** The most item files a directory may hold; one with more is not read. */
    static final int MOST_ITEMS = 10_000;

    /** An item file's name: the repository, and the number without leading zeros. */
    static final Pattern NAME =
            Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,99})-([1-9][0-9]{0,8})\\.md");

    /**
     * What one look at the directory found.
     *
     * @param revision the digest of the project's files and the item files
     * @param contents each item file's bytes, by its name, for those that may be read
     * @param faults each item file left out, and why
     * @param numbers the highest number of each repository among the item files' names, by the
     *     repository's name in lower case
     */
    record Scan(
            Revision revision,
            Map<String, byte[]> contents,
            List<String> faults,
            Map<String, Integer> numbers) {}

    /**
     * One read of the directory.
     *
     * @param board the board
     * @param items every item on it, by its id
     * @param numbers the highest number of each repository among the item files' names
     */
    record Snapshot(Board board, Map<String, LocalItem> items, Map<String, Integer> numbers) {

        /** Copies the maps. */
        Snapshot {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            numbers = Map.copyOf(numbers);
        }
    }

    private LocalReader() {}

    /**
     * Looks at the directory: every item file's name, size and time, and the contents of those that
     * may be read.
     *
     * @param directory the items directory, checked already
     * @param files the account's checks
     * @param project the project as its files name it now
     * @return what was found
     * @throws UncheckedIOException when the directory cannot be listed
     */
    static Scan scan(final Path directory, final PrivateFiles files, final TrackedProject project) {
        final MessageDigest digest = digest();
        add(digest, project.filesCommit());
        final List<Path> candidates = new ArrayList<>();
        final Map<String, Integer> numbers = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path file : entries) {
                final Matcher name = NAME.matcher(file.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                final BasicFileAttributes attributes;
                try {
                    attributes =
                            Files.readAttributes(
                                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (final NoSuchFileException e) {
                    // Removed since it was listed, as an editor's save does; the next read sees
                    // what took its place.
                    add(digest, "gone: " + name.group());
                    continue;
                }
                // A directory, a pipe or a device of an item's name is no item; a link is one
                // refused.
                if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    continue;
                }
                numbers.merge(
                        name.group(1).toLowerCase(Locale.ROOT),
                        Integer.parseInt(name.group(2)),
                        Math::max);
                candidates.add(file);
                if (candidates.size() > MOST_ITEMS) {
                    add(digest, "more than the most items");
                    return new Scan(
                            revision(digest),
                            Map.of(),
                            List.of(
                                    "The items directory holds more than "
                                            + MOST_ITEMS
                                            + " item files; none is read"),
                            numbers);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("The items directory cannot be listed", e);
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        final Map<String, Integer> cases = new HashMap<>();
        for (final Path file : candidates) {
            cases.merge(file.getFileName().toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        final Map<String, byte[]> contents = new LinkedHashMap<>();
        final List<String> faults = new ArrayList<>();
        long total = 0;
        for (final Path file : candidates) {
            final String name = file.getFileName().toString();
            add(digest, name);
            try {
                final BasicFileAttributes attributes =
                        Files.readAttributes(
                                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                add(digest, Long.toString(attributes.size()));
                add(digest, attributes.lastModifiedTime().toString());
                try {
                    files.requireItemFile(file);
                } catch (final UncheckedIOException e) {
                    if (e.getCause() instanceof NoSuchFileException gone) {
                        throw gone;
                    }
                    throw new Refused("it is a link, another account's or has a second name");
                } catch (final IllegalStateException e) {
                    throw new Refused("it is a link, another account's or has a second name");
                }
                if (attributes.size() > MOST_BYTES) {
                    throw new Refused("it is larger than 1 MiB");
                }
                if (total + attributes.size() > MOST_TOTAL) {
                    return tooLarge(digest, numbers);
                }
                final byte[] content = bounded(file);
                final BasicFileAttributes after =
                        Files.readAttributes(
                                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (content.length != attributes.size()
                        || after.size() != attributes.size()
                        || !after.lastModifiedTime().equals(attributes.lastModifiedTime())
                        || !Objects.equals(after.fileKey(), attributes.fileKey())) {
                    // Written while it was read; the next read sees it whole.
                    add(digest, "changed");
                    continue;
                }
                total += content.length;
                if (total > MOST_TOTAL) {
                    return tooLarge(digest, numbers);
                }
                add(digest, HexFormat.of().formatHex(sha256(content)));
                if (Objects.requireNonNull(cases.get(name.toLowerCase(Locale.ROOT))) > 1) {
                    throw new Refused("another item file's name differs from it only in case");
                }
                contents.put(name, content);
            } catch (final Refused e) {
                add(digest, "refused: " + e.getMessage());
                faults.add(name + " is left out: " + e.getMessage());
            } catch (final NoSuchFileException e) {
                // Removed since it was listed; the next read sees what took its place.
                add(digest, "gone");
            } catch (final IOException e) {
                add(digest, "unread");
                faults.add(name + " is left out: it cannot be read");
            }
        }
        return new Scan(revision(digest), contents, faults, numbers);
    }

    /** What a look answers when the item files hold more together than one read may hold. */
    private static Scan tooLarge(final MessageDigest digest, final Map<String, Integer> numbers) {
        add(digest, "more than the most bytes");
        return new Scan(
                revision(digest),
                Map.of(),
                List.of("The item files hold more than 64 MiB together; none is read"),
                numbers);
    }

    /** Reads a file, never following a link and never more than one byte past the bound. */
    private static byte[] bounded(final Path file) throws IOException {
        try (InputStream input =
                Channels.newInputStream(
                        Files.newByteChannel(
                                file,
                                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            final byte[] content = input.readNBytes(MOST_BYTES + 1);
            if (content.length > MOST_BYTES) {
                throw new Refused("it is larger than 1 MiB");
            }
            return content;
        }
    }

    /**
     * Maps what a look found to the board, as the GitHub store maps its items.
     *
     * @param scan what the look found
     * @param project the project as its files name it now
     * @return the board and its items
     */
    static Snapshot read(final Scan scan, final TrackedProject project) {
        final Map<String, LocalItem> items = new LinkedHashMap<>();
        final List<String> faults = new ArrayList<>(scan.faults());
        for (final Map.Entry<String, byte[]> file : scan.contents().entrySet()) {
            final Matcher name = NAME.matcher(file.getKey());
            if (!name.matches()) {
                throw new IllegalStateException("An item file's name was not checked");
            }
            if (!project.repositories().contains(name.group(1))) {
                faults.add(file.getKey() + " is left out: its repository is none of the project's");
                continue;
            }
            try {
                final String text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(file.getValue()))
                                .toString()
                                .replace("\r\n", "\n");
                final LocalItem item =
                        LocalItem.read(text, name.group(1), Integer.parseInt(name.group(2)));
                items.put(item.id(), item);
            } catch (final CharacterCodingException e) {
                faults.add(file.getKey() + " is left out: it is not UTF-8");
            } catch (final IllegalArgumentException e) {
                faults.add(file.getKey() + " is left out: " + e.getMessage());
            }
        }
        final Map<String, List<String>> listedBy = new HashMap<>();
        for (final LocalItem item : items.values()) {
            if (item.story()) {
                for (final String issue : item.links("issues")) {
                    listedBy.computeIfAbsent(issue, any -> new ArrayList<>()).add(item.id());
                }
            }
        }
        final List<ItemView> views = new ArrayList<>();
        for (final LocalItem item : items.values()) {
            views.add(
                    item.view(project.workflow(), item.story() ? item.links("issues") : List.of()));
        }
        final IssueMapping.Mapped mapped =
                IssueMapping.local(
                        views,
                        project.workflow(),
                        project.repositories(),
                        // The whole rule, its warnings and its fault with it, as the GitHub store
                        // takes it.
                        org.fuin.porelo.planner.store.github.StoryStates.following(
                                project::storyStates));
        faults.addAll(mapped.faults());
        final List<Issue> issues = new ArrayList<>();
        for (final Issue issue : mapped.issues()) {
            final LocalItem item = Objects.requireNonNull(items.get(issue.id()));
            final List<Warning> warnings = new ArrayList<>(issue.warnings());
            warnings.addAll(item.warnings());
            if (!agrees(item, items, listedBy.getOrDefault(item.id(), List.of()))) {
                warnings.add(
                        new Warning(
                                "LocalParentDisagrees",
                                "Its parent and the issues its story lists disagree.",
                                Set.of(Warning.Concern.PARENT)));
            }
            issues.add(
                    new Issue(
                            issue.id(),
                            issue.repository(),
                            issue.title(),
                            issue.state(),
                            issue.size(),
                            issue.blockedBy(),
                            issue.parent(),
                            null,
                            issue.text(),
                            issue.worker(),
                            issue.risk(),
                            issue.estimate(),
                            issue.minutes(),
                            issue.priority(),
                            warnings,
                            issue.waitsOn()));
        }
        final List<Story> stories = new ArrayList<>();
        for (final Story story : mapped.stories()) {
            final LocalItem item = Objects.requireNonNull(items.get(story.id()));
            final List<Warning> warnings = new ArrayList<>(story.warnings());
            warnings.addAll(item.warnings());
            stories.add(
                    // Its version is taken over its own values, a closed story's state among them.
                    new Story(
                            story.id(),
                            story.title(),
                            story.state(),
                            project.workflow().closed().contains(story.state()),
                            story.priority(),
                            story.size(),
                            story.issues(),
                            story.description(),
                            null,
                            story.issuesWhole(),
                            story.closedAt(),
                            warnings));
        }
        // No tracker's owner: a local id must never reach a forge as an issue's reference.
        return new Snapshot(
                new Board(
                        scan.revision(),
                        project.workflow(),
                        stories,
                        issues,
                        project.work(),
                        project.repositories(),
                        faults,
                        null),
                items,
                scan.numbers());
    }

    /**
     * Whether an issue's parent and the stories listing it agree: a story parent lists it and no
     * other story does; an issue under no story or under an issue is listed by none.
     */
    private static boolean agrees(
            final LocalItem item, final Map<String, LocalItem> items, final List<String> listing) {
        final String parent = item.parent();
        final LocalItem above = parent == null ? null : items.get(parent);
        if (above != null && above.story()) {
            return listing.equals(List.of(above.id()));
        }
        return listing.isEmpty();
    }

    private static Revision revision(final MessageDigest digest) {
        return new Revision(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM has no SHA-256", e);
        }
    }

    private static byte[] sha256(final byte[] content) {
        return digest().digest(content);
    }

    /** Adds a text with its length before it, so no two lists of texts digest alike. */
    private static void add(final MessageDigest digest, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** Why an item file is left out, in fixed words. */
    private static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(final String why) {
            super(why);
        }
    }
}
