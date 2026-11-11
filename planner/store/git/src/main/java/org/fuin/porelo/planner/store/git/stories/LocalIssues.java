package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class LocalIssues implements IssueSource {
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");
    private final Path root;

    /** The project's workflow, or null to read each repository's own file. */
    private final @org.jspecify.annotations.Nullable Workflow workflow;

    LocalIssues(Path root, @org.jspecify.annotations.Nullable Workflow workflow) {
        this.root = root.toAbsolutePath().normalize();
        this.workflow = workflow;
    }

    @Override
    public String index(String repository) throws IOException {
        if (!repository.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IOException("invalid repository: " + repository);
        final Path tree = repository.equals(own()) ? root : root.resolveSibling(repository);
        final Path file =
                tree.resolve(
                        repository.equals("porelo-project")
                                ? "stories/workflow.yml"
                                : "issues/workflow.yml");
        // Read only where needed, so a refused index page is named before a missing workflow.
        final Flow checked = () -> workflow != null ? workflow : Workflow.read(file);
        final StringBuilder text = new StringBuilder();
        read(tree, "issues/README.md", new HashSet<>(), text, checked);
        // A missing or ambiguous row is an unusable source, including across linked pages.
        IssueIndexes.read(text.toString(), repository, checked.get());
        return text.toString();
    }

    /** The workflow an index is checked against, read when first needed. */
    @FunctionalInterface
    private interface Flow {
        Workflow get() throws IOException;
    }

    private String own() throws IOException {
        final Path file = root.resolve("project.yml");
        if (!Files.isRegularFile(file)) return root.getFileName().toString();
        return Project.own(file);
    }

    private static void read(
            Path tree, String path, Set<String> visited, StringBuilder text, Flow workflow)
            throws IOException {
        if (!visited.add(path)) return;
        final Path file = tree.resolve(path);
        if (!Files.isRegularFile(file))
            throw new IOException(file + ": index not readable: not found");
        if (!file.toRealPath().startsWith(tree.toRealPath().resolve("issues"))) {
            throw new IOException(file + ": index is outside the checkout's issues directory");
        }
        final String written = Files.readString(file, StandardCharsets.UTF_8);
        // Where a file has a head, the head is the issue's data and the row only its copy.
        final Path directory = java.util.Objects.requireNonNull(file.getParent());
        final String generated =
                IssuesIndex.headed(directory)
                        ? IssuesIndex.render(tree.resolve("issues"), directory, workflow.get())
                        : null;
        final String page = generated == null ? written : generated;
        text.append(IssueIndexes.page(path, file.toString(), page)).append('\n');
        final var links = LINK.matcher(IssueIndexes.unfenced(page));
        while (links.find()) {
            final String child = IssueIndexes.indexPath(path, links.group(2));
            if (child != null && child.endsWith("/README.md"))
                read(tree, child, visited, text, workflow);
        }
    }
}
