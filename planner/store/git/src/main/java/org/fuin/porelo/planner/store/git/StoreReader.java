package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.IssueIndex;
import org.fuin.porelo.planner.store.git.stories.IssueIndexes;
import org.fuin.porelo.planner.store.git.stories.IssueLinks;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.git.stories.Story;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * The {@link PartReader} over a {@link Remote}: the workflow, the stories and the issues' JSON
 * heads, each read with the store's own stories reading, the same reading that writes the indexes.
 */
public final class StoreReader implements PartReader {

    /** An issue's file: its id, a dash and its title. */
    private static final Pattern ISSUE_FILE =
            Pattern.compile("[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?-[^/]*\\.md");

    private static final Pattern STORY_FILE = Pattern.compile("ST[0-9]{1,5}-[^/]*\\.md");

    /** The file naming whole files that hold an issue number's shape on purpose. */
    private static final String EXEMPT = "citations-exempt.txt";

    /** Files no person writes about an issue: stage records, and the stories the index writes. */
    private static final Pattern GENERATED =
            Pattern.compile(
                    "stats/[^/]+\\.jsonl|stories/ST[0-9]+(-[^/]*)?\\.md|stories/README\\.md");

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
    public Part read(
            final Repository repository, final String commit, final @Nullable Workflow project) {
        final String name = repository.name();
        final boolean own = project == null;
        // Every repository's issues are checked against the project's one workflow.
        final Workflow workflow =
                project != null
                        ? project
                        : Workflow.read(
                                remote.read(name, commit, "stories/workflow.yml")
                                        .orElseThrow(
                                                () -> new StoreException.WorkflowMissing(name)));
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
            // An issue whose file is there but cannot be read is never taken from its index row.
            final Set<String> headed = new HashSet<>();
            final Map<String, Issue> byId = new LinkedHashMap<>();
            final Set<String> twice = new HashSet<>();
            // Each page read, kept for the reading of how the pages cite an issue.
            final Map<String, String> texts = new LinkedHashMap<>();
            for (final String file : issueFiles) {
                final String number = number(file);
                headed.add(number);
                final Issue issue;
                try {
                    final String text = text(name, commit, file);
                    texts.put(file, text);
                    issue = issue(name, text, workflow);
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
            final Map<String, Map<String, IssueIndex>> parsed = new LinkedHashMap<>();
            final Set<String> settled = new java.util.TreeSet<>();
            for (final IssueIndex row : rows(name, pages, workflow, faults, parsed).values()) {
                if (!headed.contains(row.number())) {
                    if (workflow.states().contains(row.state()) && workflow.closed(row.state())) {
                        settled.add(row.number());
                    }
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
            texts.putAll(pages);
            for (final String fault : IssueLinks.check(texts, parsed, linked(name, commit))) {
                faults.add(name + ": " + fault);
            }
            faults.addAll(mentions(name, commit, settled));
        }
        final List<Issue> issues = parentsWorkers(read);
        faults.addAll(records(name, commit, workflow));
        if (!own) {
            return new Part(null, List.of(), issues, null, paths, faults);
        }
        final Map<String, Story> stories = new LinkedHashMap<>();
        final Set<String> twice = new HashSet<>();
        for (final String file : bounded(remote.list(name, commit, "stories"))) {
            if (STORY_FILE.matcher(file.substring(file.lastIndexOf('/') + 1)).matches()) {
                final Story story;
                try {
                    story = Story.read(text(name, commit, file), workflow);
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
                workflow,
                List.copyOf(stories.values()),
                issues,
                WorkReader.read(remote.read(name, commit, WorkReader.FILE).orElse(null)),
                paths,
                faults);
    }

    /**
     * Gives each sub-issue its parent's worker, whatever its own head says; a sub-issue whose
     * parent was not read is worked by an agent, as a head naming none is.
     */
    private static List<Issue> parentsWorkers(final List<Issue> read) {
        final Map<String, Worker> workers = new HashMap<>();
        for (final Issue issue : read) {
            if (issue.parent() == null) {
                workers.put(issue.id(), issue.worker());
            }
        }
        final List<Issue> issues = new ArrayList<>(read.size());
        for (final Issue issue : read) {
            final String parent = issue.parent();
            if (parent == null) {
                issues.add(issue);
                continue;
            }
            issues.add(
                    new Issue(
                            issue.id(),
                            issue.repository(),
                            issue.title(),
                            issue.state(),
                            issue.size(),
                            issue.blockedBy(),
                            parent,
                            issue.link(),
                            issue.text(),
                            workers.getOrDefault(parent, Worker.AGENT)));
        }
        return issues;
    }

    /**
     * Names each stage record file that cannot be read or holds a stage still open, which the
     * repository's commit check refuses and the stage command's median cannot count.
     */
    private List<String> records(
            final String repository, final String commit, final Workflow workflow) {
        final List<String> faults = new ArrayList<>();
        for (final String file : bounded(remote.list(repository, commit, "stats"))) {
            if (!file.endsWith(".jsonl")) {
                continue;
            }
            try {
                StageRecords.read(
                        text(repository, commit, file),
                        file.substring(file.lastIndexOf('/') + 1),
                        true,
                        workflow);
            } catch (final IllegalArgumentException e) {
                faults.add(repository + ": " + file + ": " + e.getMessage());
            }
        }
        return faults;
    }

    private static Map<String, IssueIndex> rows(
            final String repository,
            final Map<String, String> pages,
            final Workflow workflow,
            final List<String> faults,
            final Map<String, Map<String, IssueIndex>> parsed) {
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
            parsed.put(page.getKey(), read);
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

    /**
     * Names each place that still names a settled issue: its number outside {@code issues/}, or a
     * link to its deleted file within it, found by one search of the commit's tree.
     */
    private List<String> mentions(
            final String repository, final String commit, final Set<String> settled) {
        if (settled.isEmpty()) {
            return List.of();
        }
        final List<Remote.Found> found;
        try {
            found = remote.grep(repository, commit, settled);
        } catch (final GitFailure e) {
            if (e.kind() != GitFailure.Kind.FAILED) {
                throw e;
            }
            return List.of(
                    repository
                            + ": the mentions of its settled issues could not be searched: "
                            + GitRunner.scrubbed(e.getMessage()));
        }
        final List<String> faults = new ArrayList<>();
        Set<String> exempt = null;
        for (final Remote.Found line : found) {
            final String path = line.path();
            final String where = repository + ": " + path + ":" + line.line() + ": ";
            if (path.startsWith("issues/")) {
                for (final String id : settled) {
                    if (word(id, "-[^\\s)/]*\\.md").matcher(line.text()).find()) {
                        faults.add(where + "links to the deleted file of " + id);
                    }
                }
                continue;
            }
            if (path.contains("/issues/")
                    || GENERATED.matcher(path).matches()
                    || line.text().contains("not-a-citation")) {
                continue;
            }
            if (exempt == null) {
                exempt = exempt(repository, commit);
            }
            if (exempt.contains(path)) {
                continue;
            }
            for (final String id : settled) {
                // A number followed by ".<digit>" is a sub-issue's, which may still be worked on.
                if (word(id, "(?![A-Za-z0-9_]|\\.[0-9])").matcher(line.text()).find()) {
                    faults.add(where + id + " names an issue that is settled");
                }
            }
        }
        return faults;
    }

    /** An issue's id as a whole word, followed by what the pattern adds. */
    private static Pattern word(final String id, final String after) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(id) + after);
    }

    /** The files the repository names as holding an issue number's shape on purpose. */
    private Set<String> exempt(final String repository, final String commit) {
        final Set<String> names = new HashSet<>(List.of(EXEMPT));
        remote.read(repository, commit, EXEMPT)
                .ifPresent(
                        text ->
                                text.lines()
                                        .map(each -> each.replaceFirst("#.*$", "").strip())
                                        .filter(each -> !each.isEmpty())
                                        .forEach(names::add));
        return names;
    }

    /**
     * Reads a page under {@code issues/} that a link names and no reading fetched; a page that is
     * no plain file has no anchors, so the link is named as broken instead of taking the board.
     */
    private Function<String, Optional<String>> linked(
            final String repository, final String commit) {
        return path -> {
            try {
                return remote.read(repository, commit, path);
            } catch (final GitFailure e) {
                if (e.kind() != GitFailure.Kind.FAILED) {
                    throw e;
                }
                return Optional.empty();
            } catch (final IllegalArgumentException e) {
                // A path no reading takes holds no anchor, so its link reaches none.
                return Optional.empty();
            }
        };
    }

    private Issue issue(final String repository, final String text, final Workflow workflow) {
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
                text,
                worker(head.worker()));
    }

    /** The worker a head names; the schema took only these spellings, or none. */
    private static Worker worker(final @Nullable String worker) {
        if (worker == null || worker.equals("Agent")) {
            return Worker.AGENT;
        }
        if (worker.equals("Manual")) {
            return Worker.MANUAL;
        }
        throw new IllegalArgumentException("/worker: " + worker + " is no worker");
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
