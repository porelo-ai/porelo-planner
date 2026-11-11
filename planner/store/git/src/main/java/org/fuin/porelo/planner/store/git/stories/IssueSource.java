package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.nio.file.Path;

/** Supplies a repository's issue index without prescribing a transport. */
@FunctionalInterface
public interface IssueSource {
    /**
     * Reads the repository's issues/README.md, including any linked indexes it needs. Multiple
     * pages use length-framed provenance headers as described by {@link IssueIndexes#read}. Sources
     * must reject reserved marker lines in a raw page before framing it.
     *
     * @param repository the repository name from project.yml
     * @return the index Markdown
     * @throws IOException when the index cannot be read
     */
    String index(String repository) throws IOException;

    /**
     * Reads checkouts beside the project's checkout, with no network fallback. The project itself
     * is read from projectRoot even when its directory has another name.
     *
     * @param projectRoot the checkout containing project.yml
     * @return a source that reads local issue indexes only
     */
    static IssueSource local(Path projectRoot) {
        return new LocalIssues(projectRoot, null);
    }

    /**
     * Reads checkouts beside the project's checkout as {@link #local(Path)} does, checking every
     * repository's issues against the project's one workflow instead of a file of its own.
     *
     * @param projectRoot the checkout containing project.yml
     * @param workflow the project's workflow
     * @return a source that reads local issue indexes only
     */
    static IssueSource local(Path projectRoot, Workflow workflow) {
        return new LocalIssues(projectRoot, workflow);
    }
}
