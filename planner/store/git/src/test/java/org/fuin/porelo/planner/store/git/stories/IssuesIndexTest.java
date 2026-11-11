package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssuesIndexTest {
    @TempDir Path work;

    private Path issues;

    private static final String INDEX =
            """
            # Issues

            The open tasks, grouped by state.

            ## Review

            | # | Size | Blocked by | What it covers |
            |---|---|---|---|
            | <a id="ex12"></a>[EX12](EX12-Twelve.md) | S | - | Twelve \\| with a pipe. |
            | <a id="ex10"></a>[EX10](EX10-Ten.md) | M | [EX12](README.md#ex12) | Ten. |

            ## Backlog

            | # | Size | Blocked by | What it covers |
            |---|---|---|---|
            | <a id="ex14"></a>[EX14](EX14-Fourteen.md) | - | - | Fourteen. |

            ## Finished

            | # | Title | What was built |
            |---|---|---|
            | <a id="ex01"></a>EX01 | One | The first. |

            ## What was decided along the way

            - **Something.** Kept as it is.

            ## Rejected

            | # | Title | Why |
            |---|---|---|
            | <a id="ex02"></a>EX02 | Two | Not wanted. |
            """;

    @BeforeEach
    void repository() throws IOException {
        issues = Files.createDirectories(work.resolve("issues"));
        Files.copy(Path.of("src/test/resources/workflow.yml"), issues.resolve("workflow.yml"));
        Files.writeString(issues.resolve("README.md"), INDEX);
        Files.writeString(
                issues.resolve("EX12-Twelve.md"),
                "# EX12 — Twelve\n\n**Status:** Review.\n**Size:** S - one file,\nand a test.\n\n"
                        + "**What must be true.** Twelve.\n");
        Files.writeString(
                issues.resolve("EX10-Ten.md"),
                "# EX10 — Ten\n\n**Status:** Review; blocked by [EX12](README.md#ex12) of this"
                        + " repository.\n**Size:** M - several modules.\n**Priority:** 🔼 High\n\n"
                        + "**What must be true.** Ten.\n\n## Plan\n\n- A step.\n");
        Files.writeString(
                issues.resolve("EX14-Fourteen.md"),
                "# EX14 — Fourteen\n\n**Status:** Backlog.\n\n**What must be true.** Fourteen.\n");
    }

    // Prevents a head from being written that contradicts the index, or prose lost on the way.
    @Test
    void convertsWhereTheLinesAndTheRowAgreeAndLeavesEveryOtherFileAsItWas() throws IOException {
        Files.writeString(
                issues.resolve("EX14-Fourteen.md"),
                "# EX14 — Fourteen\n\n**Status:** Accepted.\n**Size:** M - big.\n\n"
                        + "**What must be true.** Fourteen.\n");
        final byte[] before = Files.readAllBytes(issues.resolve("EX14-Fourteen.md"));

        final IssuesIndex.Result result = IssuesIndex.convert(issues);

        assertThat(result.faults())
                .as("every difference of the one disagreeing file, and nothing else")
                .containsExactly(
                        "EX14-Fourteen.md: **Status:** says Accepted, the index row stands under"
                                + " Backlog",
                        "EX14-Fourteen.md: **Size:** says M, the index row -");
        assertThat(Files.readAllBytes(issues.resolve("EX14-Fourteen.md")))
                .as("a file whose lines and row disagree is not written")
                .isEqualTo(before);
        assertThat(result.written())
                .containsExactlyInAnyOrder(
                        issues.resolve("EX10-Ten.md"), issues.resolve("EX12-Twelve.md"));
        assertThat(Files.readString(issues.resolve("EX10-Ten.md")))
                .as("the head below the heading, the prose lines gone, the rest kept")
                .isEqualTo(
                        """
                        # EX10 — Ten

                        ```json
                        {
                          "$schema": "issue-1.json",
                          "id": "EX10",
                          "title": "Ten",
                          "state": "Review",
                          "size": "M",
                          "priority": "High",
                          "rank": 2,
                          "blockedBy": ["EX12"],
                          "covers": "Ten."
                        }
                        ```

                        **Why this size.** several modules.

                        **What must be true.** Ten.

                        ## Plan

                        - A step.
                        """);
        final IssueHead twelve =
                IssueHead.read(
                        Files.readString(issues.resolve("EX12-Twelve.md")),
                        Workflow.read(issues.resolve("workflow.yml")));
        assertThat(twelve).isNotNull();
        assertThat(twelve.covers())
                .as("an escaped pipe of the row is the pipe itself in the head")
                .isEqualTo("Twelve | with a pipe.");
        assertThat(twelve.rank()).as("the row's place in its table").isEqualTo(1);
        assertThat(twelve.priority()).as("no priority line reads as Medium").isEqualTo("Medium");
    }

    // Prevents a second run from writing a file twice or rewriting a head.
    @Test
    void aSecondConversionLeavesNothingToDo() throws IOException {
        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        final Map<Path, String> converted = texts();

        final IssuesIndex.Result again = IssuesIndex.convert(issues);

        assertThat(again.faults()).isEmpty();
        assertThat(again.written()).as("nothing left to convert").isEmpty();
        assertThat(texts()).isEqualTo(converted);
    }

    // Prevents the generated index from losing the rows of finished work or the prose kept in it.
    @Test
    void theIndexComesFromTheHeadsAndKeepsWhatNoHeadHolds() throws IOException {
        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();

        final IssuesIndex.Result written = IssuesIndex.write(issues);

        assertThat(written.faults()).isEmpty();
        assertThat(written.written()).containsExactly(issues.resolve("README.md"));
        final String index = Files.readString(issues.resolve("README.md"));
        assertThat(index)
                .as(
                        "states in the workflow's order, High before Medium, kept parts where they were")
                .isEqualTo(
                        """
                        # Issues

                        The open tasks, grouped by state.

                        ## Backlog

                        | # | Size | Blocked by | What it covers |
                        |---|---|---|---|
                        | <a id="ex14"></a>[EX14](EX14-Fourteen.md) | - | - | Fourteen. |

                        ## Review

                        | # | Size | Blocked by | What it covers |
                        |---|---|---|---|
                        | <a id="ex10"></a>[EX10](EX10-Ten.md) | M | [EX12](README.md#ex12) | Ten. |
                        | <a id="ex12"></a>[EX12](EX12-Twelve.md) | S | - | Twelve \\| with a pipe. |

                        ## Finished

                        | # | Title | What was built |
                        |---|---|---|
                        | <a id="ex01"></a>EX01 | One | The first. |

                        ## What was decided along the way

                        - **Something.** Kept as it is.

                        ## Rejected

                        | # | Title | Why |
                        |---|---|---|
                        | <a id="ex02"></a>EX02 | Two | Not wanted. |
                        """);
        assertThat(IssuesIndex.check(issues).faults())
                .as("the written index is accepted")
                .isEmpty();
        assertThat(IssuesIndex.write(issues).written()).as("an unchanged index stays").isEmpty();
    }

    // Prevents a hand edit of the index from passing the check.
    @Test
    void refusesAHandEditedIndexNamingItsFirstDifferingLine() throws IOException {
        IssuesIndex.convert(issues);
        IssuesIndex.write(issues);
        final Path readme = issues.resolve("README.md");
        Files.writeString(readme, Files.readString(readme).replace("| Fourteen. |", "| 14. |"));

        assertThat(IssuesIndex.check(issues).faults())
                .singleElement()
                .asString()
                .startsWith(
                        "README.md:9: is '| <a id=\"ex14\"></a>[EX14](EX14-Fourteen.md) | - | - |"
                                + " 14. |', the heads give '| <a id=\"ex14\"></a>[EX14]"
                                + "(EX14-Fourteen.md) | - | - | Fourteen. |'")
                .contains("never edited by hand");
    }

    // Prevents a wait or a parent on nothing, or a head another heading contradicts, from passing.
    @Test
    void refusesWhatAHeadNamesThatIsNotThere() throws IOException {
        IssuesIndex.convert(issues);
        IssuesIndex.write(issues);
        final Path ten = issues.resolve("EX10-Ten.md");
        Files.writeString(
                ten,
                Files.readString(ten)
                        .replace("[\"EX12\"]", "[\"EX12\", \"EX99\", \"EX01\"]")
                        .replace("# EX10 — Ten", "# EX10 — Eleven"));
        Files.writeString(
                issues.resolve("EX14.1-Part.md"),
                "# EX14.1 — Part\n\n```json\n{\"$schema\": \"issue-1.json\", \"id\": \"EX14.1\","
                        + " \"title\": \"Part\", \"state\": \"Backlog\", \"size\": \"-\","
                        + " \"priority\": \"Low\", \"rank\": 1, \"blockedBy\": [], \"covers\":"
                        + " \"A part.\", \"parent\": \"EX13\", \"role\": \"builder\"}\n```\n");
        Files.writeString(
                issues.resolve("EX15-Fifteen.md"),
                "# EX15 — Fifteen\n\n```json\n{\"id\": 1}\n```\n");

        assertThat(IssuesIndex.check(issues).faults())
                .as("the finished EX01 is an issue still; each other fault named with its file")
                .containsExactly(
                        "EX15-Fifteen.md: /id: integer found, string expected",
                        "EX15-Fifteen.md: /: required property '$schema' not found",
                        "EX15-Fifteen.md: /: required property 'title' not found",
                        "EX15-Fifteen.md: /: required property 'state' not found",
                        "EX15-Fifteen.md: /: required property 'size' not found",
                        "EX15-Fifteen.md: /: required property 'priority' not found",
                        "EX15-Fifteen.md: /: required property 'rank' not found",
                        "EX15-Fifteen.md: /: required property 'blockedBy' not found",
                        "EX15-Fifteen.md: /: required property 'covers' not found",
                        "EX15-Fifteen.md: /: required property 'parent' not found",
                        "EX10-Ten.md: the heading is not '# EX10 — Ten'",
                        "EX10-Ten.md: /blockedBy: EX99 is no issue of this repository",
                        "EX14.1-Part.md: /parent: EX13 is not the id before the dot of EX14.1");
    }

    // Prevents an issue whose file has no head from being left out of the generated index.
    @Test
    void refusesAFileWithoutAHead() throws IOException {
        assertThat(IssuesIndex.check(issues).faults())
                .containsExactly(
                        "EX10-Ten.md: no head right below the heading; run porelo-release issues"
                                + " convert",
                        "EX12-Twelve.md: no head right below the heading; run porelo-release issues"
                                + " convert",
                        "EX14-Fourteen.md: no head right below the heading; run porelo-release"
                                + " issues convert");
        assertThat(IssuesIndex.write(issues).written()).as("nothing written").isEmpty();
        assertThat(Files.readString(issues.resolve("README.md"))).isEqualTo(INDEX);
    }

    // Prevents a conversion from changing what another repository reads from an index's rows.
    @Test
    void convertsRealIndexesKeepingEveryOpenRow() throws IOException {
        for (final String set :
                List.of("porelo-buildtools/issues", "porelo/issues", "porelo-project/issues")) {
            final Path copy = work.resolve(set);
            final Path from = Path.of("src/test/resources/checkouts").resolve(set);
            final Path stories = from.resolveSibling("stories/workflow.yml");
            if (Files.exists(stories)) {
                Files.createDirectories(copy.resolveSibling("stories"));
                Files.copy(stories, copy.resolveSibling("stories/workflow.yml"));
            }
            final List<Path> pages;
            try (Stream<Path> files = Files.walk(from)) {
                pages = files.filter(Files::isRegularFile).sorted().toList();
            }
            for (final Path page : pages) {
                final Path target = copy.resolve(from.relativize(page).toString());
                Files.createDirectories(target.getParent());
                Files.copy(page, target);
            }
            // porelo's B346, B352 and B256 hold bars in code spans unescaped, which GitHub splits
            // into cells of their own: the check refuses that, so the copy escapes them first.
            if (set.equals("porelo/issues")) {
                final Path base = copy.resolve("base/README.md");
                Files.writeString(
                        base,
                        Files.readString(base)
                                .replace(
                                        "--project|--host|--registry",
                                        "--project\\|--host\\|--registry")
                                .replace(
                                        "init|add|remove|lock|unlock",
                                        "init\\|add\\|remove\\|lock\\|unlock")
                                .replace("--logs=none|failure|all", "--logs=none\\|failure\\|all"));
            }
            final Workflow workflow = Workflow.read(copy.resolve("workflow.yml"));
            final Map<String, IssueIndexes.Row> before = new TreeMap<>();
            final Map<IssueIndexes.Row, Path> unsized = new LinkedHashMap<>();
            for (final Path page : pages)
                if (page.getFileName().toString().equals("README.md"))
                    for (final IssueIndexes.Row row :
                            IssueIndexes.indexRows(
                                    Files.readString(page), workflow, page.toString())) {
                        before.put(row.number(), row);
                        if (workflow.closed(row.state())) continue;
                        final Path file = file(copy, from, page, row, row.size());
                        if (List.of("-", "—").contains(row.size()) && workflow.measured(row.state())
                                || !List.of("XS", "S", "M", "L", "XL", "-", "—")
                                        .contains(row.size())) unsized.put(row, file);
                    }
            assertThat(before).as("the reference set of %s", set).hasSizeGreaterThan(40);

            // Measured work without a size, and a size of two, is named; nothing is written for it.
            final Set<String> named = new TreeSet<>();
            for (final Path file : unsized.values())
                named.add(copy.relativize(file).toString().replace('\\', '/'));
            final Set<String> differing = new TreeSet<>();
            for (final String fault : IssuesIndex.convert(copy).faults())
                differing.add(fault.substring(0, fault.indexOf(": ")));
            assertThat(differing)
                    .as("%s names only its work without one size", set)
                    .isEqualTo(named);
            final Map<String, String> sized = new HashMap<>();
            for (final Map.Entry<IssueIndexes.Row, Path> entry : unsized.entrySet()) {
                final IssueIndexes.Row row = entry.getKey();
                final Path index = entry.getValue().resolveSibling("README.md");
                final String anchor =
                        "<a id=\"" + row.number().toLowerCase(Locale.ROOT) + "\"></a>";
                final List<String> lines = new ArrayList<>();
                for (final String line : Files.readString(index).split("\n", -1))
                    lines.add(
                            line.contains(anchor)
                                    ? line.replaceFirst("^(\\|[^|]*)\\|[^|]*\\|", "$1| S |")
                                    : line);
                Files.writeString(index, String.join("\n", lines));
                file(copy, from, from.resolve(copy.relativize(index).toString()), row, "S");
                sized.put(row.number(), "S");
            }

            final IssuesIndex.Result converted = IssuesIndex.convert(copy);
            assertThat(converted.faults()).as("%s converts without a difference", set).isEmpty();
            assertThat(IssuesIndex.write(copy).faults()).isEmpty();
            assertThat(IssuesIndex.check(copy).faults()).isEmpty();

            final Map<String, IssueIndexes.Row> after = new TreeMap<>();
            try (Stream<Path> files = Files.walk(copy)) {
                for (final Path page :
                        files.filter(path -> path.getFileName().toString().equals("README.md"))
                                .toList())
                    for (final IssueIndexes.Row row :
                            IssueIndexes.indexRows(
                                    Files.readString(page), workflow, page.toString()))
                        after.put(row.number(), row);
            }
            assertThat(after.keySet()).as("every row of %s", set).isEqualTo(before.keySet());
            for (final IssueIndexes.Row row : before.values()) {
                final IssueIndexes.Row now = after.get(row.number());
                assertThat(List.of(now.state(), now.size(), now.title(), now.blockedBy()))
                        .as("%s keeps its row", row.number())
                        .isEqualTo(
                                List.of(
                                        row.state(),
                                        sized.getOrDefault(
                                                row.number(),
                                                row.size().equals("—") ? "-" : row.size()),
                                        row.title(),
                                        row.blockedBy()));
            }
        }
    }

    // Prevents a malformed fence from reading as no head, so that prose lines would win.
    @Test
    void refusesAFenceBelowTheHeadingThatIsNoHeadFence() throws IOException {
        for (final String fence : List.of("```json ", "```JSON")) {
            Files.writeString(
                    issues.resolve("EX12-Twelve.md"),
                    "# EX12 — Twelve\n\n" + fence + "\n{}\n```\n\n**Status:** Review.\n");
            assertThat(IssuesIndex.check(issues).faults())
                    .as("a fence that is no head fence, %s", fence)
                    .contains(
                            "EX12-Twelve.md: no ```json head closed by ``` right below the"
                                    + " heading");
            assertThat(IssuesIndex.convert(issues).faults())
                    .as("convert does not read the prose below it")
                    .contains(
                            "EX12-Twelve.md: no ```json head closed by ``` right below the"
                                    + " heading");
        }
    }

    // Prevents a conversion from dropping words that stand beside the waits, the parent or the
    // role.
    @Test
    void convertNamesWhatItCannotKeepBesideWaitsParentAndRole() throws IOException {
        Files.writeString(
                issues.resolve("EX10-Ten.md"),
                "# EX10 — Ten\n\n**Status:** Review.\n**Size:** M - x.\n**Blocked by:**"
                        + " [EX12](README.md#ex12), once its review is done\n\nText.\n");
        Files.writeString(
                issues.resolve("README.md"),
                INDEX.replace(
                        "| <a id=\"ex14\"></a>[EX14](EX14-Fourteen.md) | - | - | Fourteen. |",
                        "| <a id=\"ex14\"></a>[EX14](EX14-Fourteen.md) | - | - | Fourteen. |\n"
                                + "| <a id=\"ex14.1\"></a>[EX14.1](EX14.1-Part.md) | - | - |"
                                + " A part. |"));
        Files.writeString(
                issues.resolve("EX14.1-Part.md"),
                "# EX14.1 — Part\n\n**Status:** Backlog.\n**Role:** builder, for now\n"
                        + "**Parent:** [EX14](README.md#ex14) as its builder\n\nText.\n");

        assertThat(IssuesIndex.convert(issues).faults())
                .containsExactly(
                        "EX10-Ten.md: **Blocked by:** says more than its waits: [EX12](README.md#ex12),"
                                + " once its review is done",
                        "EX14.1-Part.md: **Role:** is no role: builder, for now",
                        "EX14.1-Part.md: **Parent:** says more than its parent: [EX14](README.md#ex14)"
                                + " as its builder");
    }

    // Prevents a conversion from writing a head that its own heading then contradicts.
    @Test
    void convertNamesAHeadingTheHeadWouldNotRepeat() throws IOException {
        Files.writeString(
                issues.resolve("EX12-Twelve.md"),
                "# EX12 —  Twelve\n\n**Status:** Review.\n**Size:** S - x.\n\nText.\n");
        final byte[] before = Files.readAllBytes(issues.resolve("EX12-Twelve.md"));

        assertThat(IssuesIndex.convert(issues).faults())
                .containsExactly(
                        "EX12-Twelve.md: the heading is not '# EX12 — Twelve', as the head would"
                                + " give it");
        assertThat(Files.readAllBytes(issues.resolve("EX12-Twelve.md"))).isEqualTo(before);
    }

    // Prevents a finished issue's size from being refused, or dropped, for a row without one.
    @Test
    void aFinishedFileKeepsItsSizeBesideARowOfTheFinishedForm() throws IOException {
        Files.writeString(
                issues.resolve("README.md"),
                INDEX.replace(
                        "| <a id=\"ex01\"></a>EX01 | One | The first. |",
                        "| <a id=\"ex01\"></a>[EX01](EX01-One.md) | One | The first. |"));
        Files.writeString(
                issues.resolve("EX01-One.md"),
                "# EX01 — One\n\n**Status:** Finished.\n**Size:** M - two modules.\n\nText.\n");

        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        assertThat(Files.readString(issues.resolve("EX01-One.md")))
                .contains(
                        "\"state\": \"Finished\"", "\"size\": \"M\"", "\"covers\": \"The first.\"");
    }

    // Prevents a finished row's own size column from being read as no size at all.
    @Test
    void aFinishedRowWithASizeColumnIsComparedByIt() throws IOException {
        final String open =
                "## Finished\n\n| # | Size | Blocked by | What it covers |\n|---|---|---|---|\n"
                        + "| <a id=\"ex01\"></a>[EX01](EX01-One.md) | M | - | The first. |\n";
        Files.writeString(
                issues.resolve("README.md"),
                INDEX.replace(
                        "## Finished\n\n| # | Title | What was built |\n|---|---|---|\n"
                                + "| <a id=\"ex01\"></a>EX01 | One | The first. |\n",
                        open));
        Files.writeString(
                issues.resolve("EX01-One.md"),
                "# EX01 — One\n\n**Status:** Finished.\n**Size:** S - one module.\n\nText.\n");

        assertThat(IssuesIndex.convert(issues).faults())
                .containsExactly("EX01-One.md: **Size:** says S, the index row M");

        Files.writeString(
                issues.resolve("EX01-One.md"),
                "# EX01 — One\n\n**Status:** Finished.\n**Size:** M - two modules.\n\nText.\n");
        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        assertThat(Files.readString(issues.resolve("EX01-One.md")))
                .contains("\"size\": \"M\"", "\"covers\": \"The first.\"");
    }

    // Prevents rows of the finished form from being written under a header of the open form.
    @Test
    void aClosedTableKeptInTheOpenFormGetsRowsOfItsForm() throws IOException {
        final String open =
                "## Finished\n\n| # | size | Blocked by | What it covers |\n|---|---|---|---|\n"
                        + "| <a id=\"ex01\"></a>EX01 | S | - | The first. |\n";
        Files.writeString(
                issues.resolve("README.md"),
                INDEX.replace(
                        "## Finished\n\n| # | Title | What was built |\n|---|---|---|\n"
                                + "| <a id=\"ex01\"></a>EX01 | One | The first. |\n",
                        open));
        Files.writeString(
                issues.resolve("EX03-Three.md"),
                "# EX03 — Three\n\n**Status:** Finished.\n**Size:** M - two modules.\n\nText.\n");
        Files.writeString(
                issues.resolve("README.md"),
                Files.readString(issues.resolve("README.md"))
                        .replace(
                                "| <a id=\"ex01\"></a>EX01 | S | - | The first. |\n",
                                "| <a id=\"ex01\"></a>EX01 | S | - | The first. |\n"
                                        + "| <a id=\"ex03\"></a>[EX03](EX03-Three.md) | M | - |"
                                        + " The third. |\n"));

        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        assertThat(IssuesIndex.write(issues).faults()).isEmpty();
        assertThat(Files.readString(issues.resolve("README.md")))
                .contains(
                        "| # | size | Blocked by | What it covers |\n|---|---|---|---|\n"
                                + "| <a id=\"ex03\"></a>[EX03](EX03-Three.md) | M | - | The third. |\n"
                                + "| <a id=\"ex01\"></a>EX01 | S | - | The first. |\n");
        assertThat(IssuesIndex.check(issues).faults()).isEmpty();
    }

    // Prevents index from refusing a misfit row it rewrites from the issue's head anyway.
    @Test
    void rewritesAMisfitRowOfAnIssueWithAHead() throws IOException {
        Files.writeString(
                issues.resolve("EX03-Three.md"),
                "# EX03 — Three\n\n**Status:** Finished.\n**Size:** M - two modules.\n\nText.\n");
        final Path index = issues.resolve("README.md");
        Files.writeString(
                index,
                Files.readString(index)
                        .replace(
                                "| <a id=\"ex01\"></a>EX01 | One | The first. |\n",
                                "| <a id=\"ex01\"></a>EX01 | One | The first. |\n"
                                        + "| <a id=\"ex03\"></a>[EX03](EX03-Three.md) | Three |"
                                        + " The third. |\n"));
        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        Files.writeString(
                index,
                Files.readString(index)
                        .replace(
                                "| # | Title | What was built |\n|---|---|---|\n",
                                "| # | Size | Blocked by | What it covers |\n|---|---|---|---|\n")
                        .replace(
                                "| <a id=\"ex01\"></a>EX01 | One | The first. |\n",
                                "| <a id=\"ex01\"></a>EX01 | S | - | The first. |\n"));

        assertThat(IssuesIndex.write(issues).faults()).isEmpty();
        assertThat(Files.readString(index))
                .contains("| <a id=\"ex03\"></a>[EX03](EX03-Three.md) | M | - | The third. |\n");
        assertThat(IssuesIndex.check(issues).faults()).isEmpty();
    }

    // Prevents a closed row whose cells do not fit its table's header from passing the check.
    @Test
    void refusesAClosedRowThatDoesNotFitItsHeader() throws IOException {
        IssuesIndex.convert(issues);
        IssuesIndex.write(issues);
        final Path index = issues.resolve("README.md");
        Files.writeString(
                index,
                Files.readString(index)
                        .replace(
                                "| # | Title | What was built |\n|---|---|---|\n",
                                "| # | Size | Blocked by | What it covers |\n|---|---|---|---|\n"));
        final List<String> lines = List.of(Files.readString(index).split("\n", -1));
        final int line =
                1
                        + lines.indexOf(
                                lines.stream()
                                        .filter(each -> each.contains("id=\"ex01\""))
                                        .findFirst()
                                        .orElseThrow());

        assertThat(IssuesIndex.check(issues).faults())
                .contains("README.md:" + line + ": EX01 has 3 cells under a header of 4");
    }

    // Prevents a second table under one state from being dropped without a word.
    @Test
    void refusesAStateHeadingTwice() throws IOException {
        IssuesIndex.convert(issues);
        Files.writeString(
                issues.resolve("README.md"),
                INDEX
                        + "\n## Finished\n\n| # | Title | What was built |\n|---|---|---|\n"
                        + "| <a id=\"ex03\"></a>EX03 | Three | The third. |\n");

        assertThat(IssuesIndex.write(issues).faults())
                .containsExactly("README.md:34: a second section Finished; each state has one");
        assertThat(IssuesIndex.check(issues).faults())
                .containsExactly("README.md:34: a second section Finished; each state has one");
    }

    // Prevents measured work without a size from passing the check that stage would refuse.
    @Test
    void refusesADashForTheSizeOfAMeasuredState() throws IOException {
        IssuesIndex.convert(issues);
        IssuesIndex.write(issues);
        final Path twelve = issues.resolve("EX12-Twelve.md");
        Files.writeString(
                twelve, Files.readString(twelve).replace("\"size\": \"S\"", "\"size\": \"-\""));

        assertThat(IssuesIndex.check(issues).faults())
                .contains("EX12-Twelve.md: /size: a dash, but Review needs a size");
    }

    // Prevents a file reached through a link from being read or written as one of the issues.
    @Test
    void refusesSymbolicLinks() throws IOException {
        final Path outside = Files.writeString(work.resolve("outside.md"), "# Outside\n");
        final Path linkedRoot = Files.createSymbolicLink(work.resolve("linked"), issues);
        assertThatThrownBy(() -> IssuesIndex.check(linkedRoot))
                .as("the root")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");
        for (final String name : List.of("README.md", "EX12-Twelve.md", "workflow.yml")) {
            final Path file = issues.resolve(name);
            final Path kept = Files.move(file, work.resolve("kept-" + name));
            Files.createSymbolicLink(file, name.equals("workflow.yml") ? kept : outside);
            assertThatThrownBy(() -> IssuesIndex.check(issues))
                    .as("a linked %s", name)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("symbolic link");
            Files.delete(file);
            Files.move(kept, file);
        }
        // porelo-project's workflow beside its stories is not walked with the issues.
        final Path stories = Files.createDirectories(work.resolve("stories"));
        Files.move(issues.resolve("workflow.yml"), work.resolve("kept-workflow.yml"));
        Files.createSymbolicLink(
                stories.resolve("workflow.yml"), work.resolve("kept-workflow.yml"));
        assertThatThrownBy(() -> IssuesIndex.check(issues))
                .as("a linked workflow beside the stories")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");
    }

    // Prevents an issue outside porelo-project from waiting on another repository's issue.
    @Test
    void onlyTheProjectWaitsOnAnotherRepositorysIssue() throws IOException {
        IssuesIndex.convert(issues);
        final Path ten = issues.resolve("EX10-Ten.md");
        Files.writeString(
                ten, Files.readString(ten).replace("[\"EX12\"]", "[\"EX12\", \"porelo#B539\"]"));

        assertThat(IssuesIndex.check(issues).faults())
                .containsExactly(
                        "EX10-Ten.md: /blockedBy: porelo#B539 is another repository's issue; only"
                                + " porelo-project's issues wait on one");

        Files.createDirectories(work.resolve("stories"));
        Files.copy(issues.resolve("workflow.yml"), work.resolve("stories/workflow.yml"));
        assertThat(IssuesIndex.write(issues).faults()).as("in porelo-project").isEmpty();
        assertThat(IssuesIndex.check(issues).faults()).isEmpty();
        assertThat(Files.readString(issues.resolve("README.md")))
                .contains(
                        "| [EX12](README.md#ex12), `porelo` [B539](https://github.com/porelo-ai/"
                                + "porelo/blob/main/issues/README.md#b539) |");
    }

    // Prevents porelo-project's conversion from losing which repository a wait belongs to.
    @Test
    void theProjectConvertsWaitsOnAnotherRepository() throws IOException {
        Files.createDirectories(work.resolve("stories"));
        Files.copy(issues.resolve("workflow.yml"), work.resolve("stories/workflow.yml"));
        final String link =
                "[B539](https://github.com/porelo-ai/porelo/blob/main/issues/README.md#b539)";
        Files.writeString(
                issues.resolve("README.md"),
                INDEX.replace(
                        "| [EX12](README.md#ex12) | Ten. |", "| `porelo` " + link + " | Ten. |"));
        Files.writeString(
                issues.resolve("EX10-Ten.md"),
                "# EX10 — Ten\n\n**Status:** Review; blocked by `porelo` "
                        + link
                        + ".\n"
                        + "**Size:** M - x.\n\nText.\n");

        assertThat(IssuesIndex.convert(issues).faults()).isEmpty();
        final IssueHead head =
                IssueHead.read(
                        Files.readString(issues.resolve("EX10-Ten.md")),
                        Workflow.read(issues.resolve("workflow.yml")));
        assertThat(head).isNotNull();
        assertThat(head.blockedBy()).containsExactly("porelo#B539");
    }

    /** An issue file whose prose lines say what its row says, as the rules write it. */
    private static Path file(Path copy, Path from, Path page, IssueIndexes.Row row, String size)
            throws IOException {
        final Path dir = copy.resolve(from.relativize(page).toString()).getParent();
        // The file names its waits as its row does, other repositories' among them.
        final String cell = row.cells().getOrDefault("blocked by", "-");
        final List<String> waits = List.of("", "-", "—").contains(cell) ? List.of() : List.of(cell);
        final Path file =
                dir.resolve(
                        row.file() == null || row.file().contains("#")
                                ? row.number() + "-X.md"
                                : row.file());
        Files.writeString(
                file,
                "# "
                        + row.number()
                        + " — A title\n\n**Status:** "
                        + row.state()
                        + (waits.isEmpty() ? "." : "; blocked by " + String.join(", ", waits) + ".")
                        + (size.equals("-") || size.equals("—")
                                ? ""
                                : "\n**Size:** " + size + " - why.")
                        + "\n\n**What must be true.** Something.\n");
        return file;
    }

    private Map<Path, String> texts() throws IOException {
        final Map<Path, String> texts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(issues)) {
            for (final Path file : files.filter(Files::isRegularFile).toList())
                texts.put(file, Files.readString(file));
        }
        return texts;
    }
}
