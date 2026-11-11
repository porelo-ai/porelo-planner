package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.stories.IssueHead;
import org.fuin.porelo.stories.IssueIndex;
import org.fuin.porelo.stories.IssueIndexes;
import org.fuin.porelo.stories.Story;
import org.fuin.porelo.stories.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * The {@link PartReader} over a {@link Remote}: the workflow, the stories and the issues' JSON
 * heads, each read with the {@code porelo-stories} library, the same reading that writes the
 * indexes.
 */
public final class StoreReader implements PartReader {

    /** An issue's file: its id, a dash and its title. */
    private static final Pattern ISSUE_FILE =
            Pattern.compile("[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?-[^/]*\\.md");

    private static final Pattern STORY_FILE = Pattern.compile("ST[0-9]{1,5}-[^/]*\\.md");

    private final Remote remote;

    private final int filesPerRepository;

    /**
     * Makes a reader.
     *
     * @param remote reads the fetched commits
     * @param filesPerRepository the most issue and story files one repository may hold
     */
    public StoreReader(final Remote remote, final int filesPerRepository) {
        this.remote = remote;
        this.filesPerRepository = filesPerRepository;
    }

    @Override
    public Part read(final Repository repository, final String commit, final boolean own) {
        final String name = repository.name();
        final Optional<String> storiesWorkflow = remote.read(name, commit, "stories/workflow.yml");
        if (own && storiesWorkflow.isEmpty()) {
            throw new StoreException.WorkflowMissing(name);
        }
        final List<String> groups = remote.directories(name, commit, "issues");
        final List<String> files = new ArrayList<>(remote.list(name, commit, "issues"));
        final List<String> indexes = new ArrayList<>(List.of("issues/README.md"));
        for (final String group : groups) {
            files.addAll(remote.list(name, commit, group));
            indexes.add(group + "/README.md");
        }
        final List<String> issueFiles =
                bounded(files).stream()
                        .filter(
                                file ->
                                        ISSUE_FILE
                                                .matcher(file.substring(file.lastIndexOf('/') + 1))
                                                .matches())
                        .toList();
        final Map<String, String> pages = new LinkedHashMap<>();
        for (final String index : indexes) {
            remote.read(name, commit, index).ifPresent(text -> pages.put(index, text));
        }
        final List<Issue> read = new ArrayList<>();
        final Map<String, String> paths = new LinkedHashMap<>();
        final List<String> faults = new ArrayList<>();
        if (!issueFiles.isEmpty() || !pages.isEmpty()) {
            // An issues directory is checked against its own workflow, or else the project's
            // beside its stories.
            final Workflow issues =
                    remote.read(name, commit, "issues/workflow.yml")
                            .or(() -> storiesWorkflow)
                            .map(Workflow::read)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "neither issues/workflow.yml nor"
                                                            + " stories/workflow.yml"));
            // An issue whose file is there but cannot be read is never taken from its index row.
            final Set<String> headed = new HashSet<>();
            final Map<String, Issue> byId = new LinkedHashMap<>();
            final Set<String> twice = new HashSet<>();
            for (final String file : issueFiles) {
                final String number = number(file);
                headed.add(number);
                final Issue issue;
                try {
                    issue = issue(name, commit, file, issues);
                } catch (final IllegalArgumentException e) {
                    // One file a person got wrong leaves out that file, not the project's board.
                    faults.add(name + ": " + file + ": " + e.getMessage());
                    continue;
                }
                headed.add(issue.id());
                if (byId.containsKey(issue.id()) || twice.contains(issue.id())) {
                    twice.add(issue.id());
                    faults.add(
                            name
                                    + ": "
                                    + issue.id()
                                    + " is read from two files, "
                                    + paths.getOrDefault(issue.id(), "")
                                    + " and "
                                    + file
                                    + ", and is left out");
                    byId.remove(issue.id());
                    continue;
                }
                byId.put(issue.id(), issue);
                paths.put(issue.id(), file);
            }
            twice.forEach(paths::remove);
            read.addAll(byId.values());
            // A finished or rejected issue's file is deleted; its index row is all that is left.
            for (final IssueIndex row : rows(name, pages, issues, faults).values()) {
                if (!headed.contains(row.number())) {
                    try {
                        read.add(issue(name, row));
                    } catch (final IllegalArgumentException e) {
                        faults.add(
                                name
                                        + ": the index row of "
                                        + row.number()
                                        + ": "
                                        + e.getMessage());
                    }
                }
            }
        }
        if (!own) {
            return new Part(null, List.of(), read, null, paths, faults);
        }
        final Workflow board = Workflow.read(storiesWorkflow.orElseThrow());
        final Map<String, Story> stories = new LinkedHashMap<>();
        final Set<String> twice = new HashSet<>();
        for (final String file : bounded(remote.list(name, commit, "stories"))) {
            if (STORY_FILE.matcher(file.substring(file.lastIndexOf('/') + 1)).matches()) {
                final Story story;
                try {
                    story = Story.read(text(name, commit, file), board);
                } catch (final IllegalArgumentException e) {
                    faults.add(name + ": " + file + ": " + e.getMessage());
                    continue;
                }
                if (stories.containsKey(story.id()) || twice.contains(story.id())) {
                    // Kept as one, a move would write whichever file was read last.
                    twice.add(story.id());
                    faults.add(
                            name
                                    + ": "
                                    + story.id()
                                    + " is read from two files, "
                                    + paths.getOrDefault(Part.story(story.id()), "")
                                    + " and "
                                    + file
                                    + ", and is left out");
                    stories.remove(story.id());
                    continue;
                }
                stories.put(story.id(), story);
                paths.put(Part.story(story.id()), file);
            }
        }
        twice.forEach(id -> paths.remove(Part.story(id)));
        return new Part(
                board,
                List.copyOf(stories.values()),
                read,
                WorkReader.read(remote.read(name, commit, WorkReader.FILE).orElse(null)),
                paths,
                faults);
    }

    private static Map<String, IssueIndex> rows(
            final String repository,
            final Map<String, String> pages,
            final Workflow workflow,
            final List<String> faults) {
        final Map<String, IssueIndex> rows = new LinkedHashMap<>();
        for (final Map.Entry<String, String> page : pages.entrySet()) {
            final Map<String, IssueIndex> read;
            try {
                read = IssueIndexes.read(page.getValue(), repository, workflow);
            } catch (final IOException | IllegalArgumentException e) {
                // An index that cannot be read leaves out the closed issues it alone names.
                faults.add(repository + ": " + page.getKey() + ": " + e.getMessage());
                continue;
            }
            for (final IssueIndex row : read.values()) {
                if (rows.putIfAbsent(row.number(), row) != null) {
                    faults.add(
                            repository
                                    + ": "
                                    + row.number()
                                    + " has a row in two of the repository's indexes");
                }
            }
        }
        return rows;
    }

    /** The issue number a file's name starts with. */
    private static String number(final String file) {
        final String name = file.substring(file.lastIndexOf('/') + 1);
        return name.substring(0, name.indexOf('-'));
    }

    private static Issue issue(final String repository, final IssueIndex row) {
        final String id = row.number();
        final int dot = id.lastIndexOf('.');
        return new Issue(
                id,
                repository,
                row.title(),
                row.state(),
                size(row.size()),
                row.blockedBy(),
                dot < 0 ? null : id.substring(0, dot),
                null,
                "");
    }

    private static @Nullable String size(final @Nullable String size) {
        return size == null || size.isBlank() || size.equals("-") ? null : size;
    }

    private Issue issue(
            final String repository,
            final String commit,
            final String file,
            final Workflow workflow) {
        final String text = text(repository, commit, file);
        final IssueHead head = IssueHead.read(text, workflow);
        if (head == null) {
            throw new IllegalArgumentException("it has no JSON head");
        }
        return new Issue(
                head.id(),
                repository,
                head.title(),
                head.state(),
                size(head.size()),
                head.blockedBy(),
                head.parent(),
                null,
                text);
    }

    private String text(final String repository, final String commit, final String file) {
        return remote.read(repository, commit, file)
                .orElseThrow(
                        () -> new IllegalArgumentException(file + " vanished from its commit"));
    }

    private List<String> bounded(final List<String> files) {
        if (files.size() > filesPerRepository) {
            throw new IllegalArgumentException(
                    "more than " + filesPerRepository + " files in one directory of a repository");
        }
        return files;
    }
}
