package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.junit.jupiter.api.Test;

class WorkReaderTest {

    private static final String SOUND =
            """
            roles:
              author: {end: push, workspace: own, rights: [push, reply]}
              reader: {end: findings, workspace: readOnly, readLanded: true, rights: [report]}
            work:
              Implementing:
                from: [Backlog, Rework]
                done: Review
                then: Reading
                per: repository
                modes: [UNATTENDED, ATTENDED]
                order: "Build {issues} of {story}."
                models: {claude-code: claude-opus-5-5, codex: gpt-6.1-sol}
                role: author
              Reading:
                from: [Implementing]
                done: Review
                blocked: Reworking
                per: story
                modes: [UNATTENDED]
                order: "Read {tasks}."
                models: {agy: gemini-4-argon-high}
                role: reader
            """;

    private static final Role AUTHOR =
            new Role(
                    "author",
                    Role.End.PUSHES_WORK,
                    Role.Workspace.OWN,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.PUSH, Role.Right.REPLY));

    private static final Role READER =
            new Role(
                    "reader",
                    Role.End.REPORTS_FINDINGS,
                    Role.Workspace.READ_ONLY,
                    true,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.REPORT));

    /** The sound role of a tester at another task's work, as a project defines it. */
    private static final String TESTER =
            "{end: findings, workspace: foreign, writeAs: review, rights: [openThreads, report]}";

    /**
     * A file whose roles are an author and a tester defined as given, and whose one rule, Building,
     * offers the role named.
     */
    private static String offering(final String role, final String tester) {
        return """
               roles:
                 author: {end: push, workspace: own, rights: [push, reply]}
                 tester: %s
               work:
                 Building:
                   from: [Backlog]
                   done: Review
                   per: repository
                   modes: [UNATTENDED]
                   order: "Build {issues}."
                   models: {claude-code: claude-opus-5-5}
                   role: %s
               """
                .formatted(tester, role);
    }

    /** The faults of a tester defined as given and offered by Building, which it drops. */
    private static void refusedTester(final String tester, final String fault) {
        final Work work = WorkReader.read(offering("tester", tester));
        assertThat(work.faults())
                .as("the role's fault, then its rule's, for %s", tester)
                .containsExactly(
                        "stories/work.yml: role tester: " + fault,
                        "stories/work.yml: Building: role 'tester' did not load");
        assertThat(work.rules()).as("the rule offering %s is dropped", tester).isEmpty();
        assertThat(work.roles().keySet())
                .as("the other role still loads beside %s", tester)
                .containsExactly("author");
    }

    @Test
    void soundRulesAreReadInTheFilesOrder() {
        final Work work = WorkReader.read(SOUND);
        assertThat(work.faults()).as("no fault").isEmpty();
        assertThat(work.rules().keySet())
                .as("the working states in the file's order")
                .containsExactly("Implementing", "Reading");
        final WorkRule implementing = work.rules().get("Implementing");
        assertThat(implementing)
                .as("every field read")
                .isEqualTo(
                        new WorkRule(
                                List.of("Backlog", "Rework"),
                                "Review",
                                "Reading",
                                null,
                                WorkRule.Per.REPOSITORY,
                                List.of("UNATTENDED", "ATTENDED"),
                                "Build {issues} of {story}.",
                                implementing.models(),
                                AUTHOR));
        assertThat(implementing.models().keySet())
                .as("the agents in the file's order, the first the default")
                .containsExactly("claude-code", "codex");
        assertThat(work.rules().get("Reading").per()).as("per story").isEqualTo(WorkRule.Per.STORY);
        assertThat(work.rules().get("Reading").role())
                .as("the reader reads the landed work, with the project's services by default")
                .isEqualTo(READER);
        assertThat(work.roles().keySet())
                .as("the roles in the file's order")
                .containsExactly("author", "reader");
    }

    // Prevents a project's own role from being refused or loaded with other parts than it names,
    // so a task would start with more or fewer rights than the project gave it.
    @Test
    void aProjectsOwnRoleOfferedByARuleLoadsWithThatRole() {
        final Work work = WorkReader.read(offering("tester", TESTER));
        assertThat(work.faults()).as("no fault").isEmpty();
        assertThat(work.rules().get("Building").role())
                .as("the tester, without the project's services at another task's work")
                .isEqualTo(
                        new Role(
                                "tester",
                                Role.End.REPORTS_FINDINGS,
                                Role.Workspace.FOREIGN,
                                false,
                                false,
                                Role.WriteAs.REVIEW,
                                List.of(Role.Right.OPEN_THREADS, Role.Right.REPORT)));
        assertThat(work.roles().keySet())
                .as("every role that loaded, also one no rule offers, in the file's order")
                .containsExactly("author", "tester");
    }

    // Prevents a rule from starting a task under a role nobody defined.
    @Test
    void aRuleNamingARoleTheRolesDoNotDefineFailsWithTheRuleAndTheName() {
        final Work work = WorkReader.read(offering("builder", TESTER));
        assertThat(work.faults())
                .as("the rule and the role it names")
                .containsExactly(
                        "stories/work.yml: Building: role 'builder' is not defined under roles");
        assertThat(work.rules()).as("the rule dropped").isEmpty();
        assertThat(work.roles().keySet())
                .as("the roles still load")
                .containsExactly("author", "tester");
    }

    // Prevents a rule from starting a task with no role, which Porelo would start with none.
    @Test
    void aRuleNamingNoRoleIsAFault() {
        final Work work =
                WorkReader.read(offering("tester", TESTER).replace("    role: tester\n", ""));
        assertThat(work.faults())
                .as("the rule without a role")
                .containsExactly("stories/work.yml: Building: role is missing");
        assertThat(work.rules()).as("the rule dropped").isEmpty();
    }

    // Prevents a role at another task's work from pushing there, which Porelo refuses at Start.
    @Test
    void aRoleAtAnotherTasksWorkHoldingPushFailsWithTheRoleAndTheRulesItBreaks() {
        refusedTester(
                "{end: points, workspace: foreign, rights: [push, reply]}",
                "breaks foreignWorkLeadsOut (another task's work with the project's services or"
                        + " push), pushWithoutOwnWork (push outside own work), pushNeedsEnd (push with"
                        + " an end that pushes no work), pointsNeedThreads (points without openThreads"
                        + " and without reject)");
    }

    // Prevents a role Porelo would refuse at Start from loading, so no task fails at its start.
    @Test
    void aRoleBreakingAnyRuleOfAStartFailsNamingEachRuleItBreaks() {
        final Map<String, String> broken = new LinkedHashMap<>();
        broken.put(
                "{end: findings, workspace: foreign, projectServices: true,"
                        + " rights: [openThreads, report]}",
                "breaks foreignWorkLeadsOut (another task's work with the project's services or"
                        + " push)");
        broken.put(
                "{end: points, workspace: foreign, rights: []}",
                "breaks foreignWorkNamesNoRights (another task's work with no right at all),"
                        + " pointsNeedThreads (points without openThreads and without reject)");
        broken.put(
                "{end: result, workspace: foreign, rights: [report]}",
                "breaks foreignWorkNeedsReading (another task's work with an end that neither"
                        + " reports findings nor gives points)");
        broken.put(
                "{end: findings, workspace: none, readLanded: true, rights: [report]}",
                "breaks readLandedNeedsWorkspace (readLanded without a workspace)");
        broken.put(
                "{end: push, workspace: own, rights: [push, reject]}",
                "breaks rejectWithPush (reject together with push), rejectOverOwnWork (reject"
                        + " without another task's work), rejectNeedsPoints (reject with an end that"
                        + " gives no points)");
        broken.put(
                "{end: points, workspace: readOnly, rights: [reject]}",
                "breaks rejectOverOwnWork (reject without another task's work),"
                        + " pointsNeedAnothersWork (an end that gives points without another task's"
                        + " work)");
        broken.put(
                "{end: findings, workspace: own, rights: [push, report]}",
                "breaks pushNeedsEnd (push with an end that pushes no work), findingsNeedLandedWork"
                        + " (findings in its answer without readLanded)");
        broken.put(
                "{end: findings, workspace: foreign, rights: [report]}",
                "breaks findingsNeedThreads (findings at another task's work without"
                        + " openThreads)");
        broken.put(
                "{end: push, workspace: readOnly, rights: [push]}",
                "breaks pushWithoutOwnWork (push outside own work)");
        broken.put(
                "{end: push, workspace: own, rights: [reply]}",
                "breaks endNeedsPush (an end that pushes work without push)");
        broken.put(
                "{end: findings, workspace: readOnly, rights: []}",
                "breaks findingsNeedLandedWork (findings in its answer without readLanded),"
                        + " endNeedsReport (an end that reports findings or hands back a result"
                        + " without report)");
        broken.put(
                "{end: result, workspace: none, rights: [done]}",
                "breaks endNeedsReport (an end that reports findings or hands back a result"
                        + " without report)");
        assertThat(broken).as("the broken roles' reference set").hasSize(12);
        assertThat(Arrays.stream(Role.Broken.values()).map(Role.Broken::word))
                .as("every rule a role can break is among them")
                .allMatch(word -> broken.values().stream().anyMatch(fault -> fault.contains(word)));
        broken.forEach(WorkReaderTest::refusedTester);
    }

    // Prevents one role that did not load from hiding the rules and roles that did.
    @Test
    void aRuleWhoseRoleDidNotLoadIsDroppedAndTheOthersStillLoad() {
        final Work work =
                WorkReader.read(
                        offering("author", "{end: findings, workspace: foreign, rights: [push]}")
                                + """
                                  Testing:
                                    from: [Review]
                                    done: Finished
                                    per: story
                                    modes: [UNATTENDED]
                                    order: "Test {story}."
                                    models: {codex: gpt-6.1-sol}
                                    role: tester
                                """);
        assertThat(work.rules().keySet())
                .as("only the rule whose role loaded")
                .containsExactly("Building");
        assertThat(work.rules().get("Building").role()).as("its role").isEqualTo(AUTHOR);
        assertThat(work.roles().keySet()).as("only the role that loaded").containsExactly("author");
        assertThat(work.faults())
                .as("the role's fault, then the rule's")
                .containsExactly(
                        "stories/work.yml: role tester: breaks foreignWorkLeadsOut (another task's"
                                + " work with the project's services or push), pushWithoutOwnWork"
                                + " (push outside own work), pushNeedsEnd (push with an end that"
                                + " pushes no work), findingsNeedThreads (findings at another task's"
                                + " work without openThreads), endNeedsReport (an end that reports"
                                + " findings or hands back a result without report)",
                        "stories/work.yml: Testing: role 'tester' did not load");
    }

    // Prevents a role from loading with a part Porelo does not know, read as some other part.
    @Test
    void anUnknownEndWorkspaceWriteAsOrRightFailsTheRoleNamingTheWordAndTheKnownOnes() {
        refusedTester(
                "{end: judge, workspace: foreign, writeAs: review, rights: [report]}",
                "end 'judge' is none of push, findings, points and result");
        refusedTester(
                "{end: findings, workspace: shared, writeAs: review, rights: [report]}",
                "workspace 'shared' is none of own, foreign, readOnly, allRepositories and none");
        refusedTester(
                "{end: findings, workspace: foreign, writeAs: bot, rights: [report]}",
                "writeAs 'bot' is neither author nor review");
        refusedTester(
                "{end: findings, workspace: foreign, writeAs: review, rights: [report, approve]}",
                "right 'approve' is none of push, reply, openThreads, resolveThreads, start, stop,"
                        + " done, report and reject");
        refusedTester(
                "{end: findings, workspace: foreign, writeAs: review, rights: [Report]}",
                "right 'Report' is none of push, reply, openThreads, resolveThreads, start, stop,"
                        + " done, report and reject");
    }

    // Prevents a role from carrying credentials, a clearance or an upstream into a task, which a
    // role does not grant, or the old place from being read as a role's.
    @Test
    void anUnknownKeyFailsTheRole() {
        for (final String key :
                List.of(
                        "credentials: [github]",
                        "clearance: high",
                        "upstream: true",
                        "place: work")) {
            refusedTester(
                    "{end: findings, workspace: foreign, writeAs: review, rights: [report], "
                            + key
                            + "}",
                    "unknown key '" + key.substring(0, key.indexOf(':')) + "'");
        }
    }

    // Prevents a role that leaves out how it ends, where it works or what it may do from being
    // filled in by a guess.
    @Test
    void aRoleWithoutItsEndWorkspaceOrRightsFails() {
        refusedTester("{workspace: foreign, rights: [report]}", "end is missing");
        refusedTester("{end: findings, rights: [report]}", "workspace is missing");
        refusedTester("{end: findings, workspace: foreign}", "rights is missing");
        refusedTester(
                "{end: findings, workspace: foreign, rights: report}",
                "rights is no list of rights");
        refusedTester(
                "{end: findings, workspace: foreign, readLanded: often, rights: [report]}",
                "readLanded is neither true nor false");
        refusedTester("[end, findings]", "is no mapping");
    }

    // Prevents a task role from taking the name of the person at the machine.
    @Test
    void aRoleNamedOperatorFails() {
        for (final String name : List.of("operator", "Operator")) {
            final Work work =
                    WorkReader.read(
                            offering(name, TESTER)
                                    .replace("  tester: ", "  " + name + ": ")
                                    .replace("    role: " + name + "\n", "    role: author\n"));
            assertThat(work.faults())
                    .as("the role named %s", name)
                    .containsExactly(
                            "stories/work.yml: role "
                                    + name
                                    + ": the name operator is taken by the person at the machine");
            assertThat(work.roles().keySet()).as("only the author loads").containsExactly("author");
            assertThat(work.rules().keySet())
                    .as("the rule offering another role still loads")
                    .containsExactly("Building");
        }
    }

    // Prevents a roles section that is no mapping from being read as no roles without a word.
    @Test
    void aRolesSectionThatIsNoMappingIsAFault() {
        final Work work =
                WorkReader.read(
                        offering("author", TESTER)
                                .replace(
                                        "roles:\n  author: {end: push, workspace: own, rights: [push,"
                                                + " reply]}\n  tester: "
                                                + TESTER
                                                + "\n",
                                        "roles: [author]\n"));
        assertThat(work.faults())
                .as("the section's fault, then the rule's")
                .containsExactly(
                        "stories/work.yml: roles is no mapping of name to role",
                        "stories/work.yml: Building: role 'author' is not defined under roles");
        assertThat(work.roles()).as("no roles").isEmpty();
    }

    // Prevents the old reviewer pair from being read, so a rule names its role by the roles alone.
    @Test
    void theOldPlaceOfARuleIsAnUnknownKey() {
        final Work work =
                WorkReader.read(
                        offering("tester", TESTER)
                                .replace(
                                        "    role: tester\n",
                                        "    role: tester\n    place: work\n"));
        assertThat(work.faults())
                .as("the old key named")
                .containsExactly("stories/work.yml: Building: unknown key 'place'");
    }

    // Prevents a result asked of a role that pushes, or a result never taken from one that hands
    // one back.
    @Test
    void aResultThatDoesNotFitTheRoleIsAFaultOfTheRule() {
        final String analyst = "{end: result, workspace: readOnly, rights: [report]}";
        final Work named =
                WorkReader.read(
                        offering("author", analyst)
                                .replace(
                                        "    role: author\n",
                                        "    role: author\n    result: ANALYSIS\n"));
        assertThat(named.faults())
                .as("a result named for a role that pushes work")
                .containsExactly(
                        "stories/work.yml: Building: result is named, and role 'author' pushes"
                                + " work");
        assertThat(named.rules()).as("its rule dropped").isEmpty();
        assertThat(WorkReader.read(offering("tester", analyst)).faults())
                .as("a role that hands back a result, with none named")
                .containsExactly(
                        "stories/work.yml: Building: role 'tester' hands back a result, and result"
                                + " names none");
    }

    // Prevents a stage that should hand back its result from being started as work that pushes.
    @Test
    void aRulesResultIsReadAndAnUnknownOneDropsTheRule() {
        final Work work =
                WorkReader.read(
                        """
                        roles:
                          analyst: {end: result, workspace: readOnly, rights: [report]}
                        work:
                          In analysis:
                            from: [Accepted]
                            done: Analyzed
                            per: repository
                            modes: [UNATTENDED]
                            order: "Analyze {issues}."
                            models: {claude-code: claude-opus-5-5}
                            result: ANALYSIS
                            role: analyst
                          Under estimation:
                            from: [Analyzed]
                            done: Estimated
                            per: repository
                            modes: [UNATTENDED]
                            order: "Estimate {issues}."
                            models: {claude-code: claude-opus-5-5}
                            result: GUESS
                            role: analyst
                        """);
        assertThat(work.rules().get("In analysis").result())
                .as("the analysis hands back its result")
                .isEqualTo(WorkRule.Result.ANALYSIS);
        assertThat(work.rules()).as("the rule with an unknown result is dropped").hasSize(1);
        assertThat(work.faults())
                .as("its fault named")
                .containsExactly(
                        "stories/work.yml: Under estimation: result 'GUESS' is none of ANALYSIS,"
                                + " ESTIMATE and READINESS");
        assertThat(WorkReader.read(SOUND).rules().get("Implementing").result())
                .as("a rule without result pushes its work")
                .isNull();
    }

    // Prevents a readiness check from being dropped as an unknown result, or one in other case
    // from being read as it.
    @Test
    void aReadinessResultIsReadAndOneInLowerCaseDropsItsRule() {
        final String rule =
                """
                roles:
                  analyst: {end: result, workspace: readOnly, rights: [report]}
                work:
                  Preparing for Backlog:
                    from: [Estimated]
                    done: Backlog
                    per: repository
                    modes: [UNATTENDED]
                    order: "Check {issues}."
                    models: {claude-code: claude-opus-5-5}
                    role: analyst
                    result: %s
                """;

        assertThat(
                        WorkReader.read(rule.formatted("READINESS"))
                                .rules()
                                .get("Preparing for Backlog"))
                .as("the readiness check")
                .extracting(WorkRule::result)
                .isEqualTo(WorkRule.Result.READINESS);
        assertThat(WorkReader.read(rule.formatted("readiness")).rules())
                .as("a lower-case kind is none")
                .isEmpty();
    }

    @Test
    void aMissingFileIsAFaultNotAFailure() {
        // Without work rules the board is still read; only work cannot start.
        final Work work = WorkReader.read(null);
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml is missing, so no work can be started");
    }

    @Test
    void aFileThatIsNoYamlIsOneFault() {
        final Work work = WorkReader.read("work: [unclosed\n");
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0)).as("the fault").startsWith("stories/work.yml: not YAML: ");
    }

    @Test
    void aFileWithoutAWorkMappingIsOneFault() {
        assertThat(WorkReader.read("states: [Idea]\n").faults())
                .as("no work mapping")
                .containsExactly("stories/work.yml: no mapping 'work'");
        assertThat(WorkReader.read("").faults())
                .as("an empty file")
                .containsExactly("stories/work.yml: no mapping 'work'");
    }

    @Test
    void aBrokenRuleIsDroppedWithItsFaultAndTheOthersAreKept() {
        // One wrong line must not stop every other work from starting.
        final String broken =
                SOUND.replace("    per: story\n", "    per: issue\n")
                        .replace("    then: Reading\n", "    then: Reading\n    next: Review\n");
        final Work work = WorkReader.read(broken);
        assertThat(work.faults())
                .as("each fault named with its state")
                .containsExactly(
                        "stories/work.yml: Implementing: unknown key 'next'",
                        "stories/work.yml: Reading: per 'issue' is neither story nor repository");
        assertThat(work.rules()).as("both dropped").isEmpty();
    }

    @Test
    void aMissingOrMistypedFieldIsAFault() {
        final String broken =
                SOUND.replace("    from: [Backlog, Rework]\n", "    from: Backlog\n")
                        .replace("    order: \"Read {tasks}.\"\n", "");
        final Work work = WorkReader.read(broken);
        assertThat(work.faults())
                .as("each fault named")
                .containsExactly(
                        "stories/work.yml: Implementing: from is no list of states",
                        "stories/work.yml: Reading: order is missing");
        assertThat(work.rules()).as("both dropped").isEmpty();
    }

    @Test
    void aStateTwiceIsAFaultNotASilentReplacement() {
        // The second rule would replace the first unseen, and work would start by rules nobody
        // wrote last.
        final Work work = WorkReader.read(SOUND + "  Implementing:\n    from: [Backlog]\n");
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0))
                .as("the fault names the key twice")
                .startsWith("stories/work.yml: not YAML: ")
                .contains("Implementing");
    }

    @Test
    void deepNestingIsAFault() {
        // The parser goes down one level per bracket; without a bound a small file takes the stack.
        final Work work = WorkReader.read("work: " + "[".repeat(200) + "]".repeat(200) + "\n");
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0))
                .as("refused as YAML")
                .startsWith("stories/work.yml: not YAML: ");
    }

    @Test
    void aRulesRisksAreReadWithTheirModelsInOrderAndTheirEffort() {
        // A risk's choice not read would work an issue that can do harm with the rule's lighter
        // one.
        final Work work =
                WorkReader.read(
                        SOUND.replace(
                                "    models: {agy: gemini-4-argon-high}\n",
                                """
                                    models: {agy: gemini-4-argon-high}
                                    risks:
                                      harm: {models: {codex: gpt-6.1-sol, agy: gemini-4-argon-max}, effort: max}
                                      small: {effort: low}
                                """));
        assertThat(work.faults()).as("no fault").isEmpty();
        final WorkRule reading = work.rules().get("Reading");
        assertThat(reading.risks())
                .as("the risks the rule names, and only those")
                .containsOnlyKeys(Risk.HARM, Risk.SMALL);
        final WorkRule.ForRisk harm = reading.risks().get(Risk.HARM);
        assertThat(harm.models().keySet())
                .as("the harm entry's agents in the file's order, the first the default")
                .containsExactly("codex", "agy");
        assertThat(harm.models().get("agy"))
                .as("each agent's model")
                .isEqualTo("gemini-4-argon-max");
        assertThat(harm.effort()).as("the harm entry's effort").isEqualTo("max");
        assertThat(reading.risks().get(Risk.SMALL))
                .as("an entry naming only an effort takes its models from elsewhere")
                .isEqualTo(new WorkRule.ForRisk(Map.of(), "low"));
        assertThat(work.rules().get("Implementing").risks()).as("a rule naming no risks").isEmpty();
    }

    @Test
    void aWrongRiskDropsOnlyItsRuleWithItsWords() {
        // A wrong entry read as none would quietly work harm as ordinary; one dropping every rule
        // would stop all work.
        final Map<String, String> wrong = new LinkedHashMap<>();
        wrong.put("risks: [harm]", "risks is no mapping of risk to models and effort");
        wrong.put(
                "risks: {Harm: {effort: max}}",
                "risks: risk 'Harm' is none of harm, ordinary and small");
        wrong.put(
                "risks: {high: {effort: max}}",
                "risks: risk 'high' is none of harm, ordinary and small");
        wrong.put("risks: {harm: max}", "risks: harm: is no mapping of models and effort");
        wrong.put("risks: {harm: null}", "risks: harm: is no mapping of models and effort");
        wrong.put("risks: {harm: {}}", "risks: harm: names neither models nor effort");
        wrong.put("risks: {harm: {models: {}}}", "risks: harm: names neither models nor effort");
        wrong.put("risks: {harm: {model: x}}", "risks: harm: unknown key 'model'");
        wrong.put("risks: {harm: {effort: 3}}", "risks: harm: effort is no text");
        wrong.put(
                "risks: {harm: {models: [codex]}}",
                "risks: harm: models is no mapping of agent to model");
        wrong.put(
                "risks: {harm: {effort: \"\"}}",
                "risks: harm: names an effort that is blank, longer than 128 characters or holds a"
                        + " control character");
        wrong.put(
                "risks: {harm: {effort: \"max\\nrm -rf /\"}}",
                "risks: harm: names an effort that is blank, longer than 128 characters or holds a"
                        + " control character");
        wrong.put(
                "risks: {harm: {effort: " + "e".repeat(129) + "}}",
                "risks: harm: names an effort that is blank, longer than 128 characters or holds a"
                        + " control character");
        wrong.put(
                "risks: {harm: {models: {codex: \"gpt\\u2028x\"}}}",
                "risks: harm: names a model that is blank, longer than 128 characters or holds a"
                        + " control character");
        wrong.put(
                "risks: {harm: {models: {\" \": gpt}}}",
                "risks: harm: names an agent that is blank, longer than 128 characters or holds a"
                        + " control character");
        assertThat(wrong).as("the faulty reference set").hasSize(15);
        for (final Map.Entry<String, String> entry : wrong.entrySet()) {
            final Work work =
                    WorkReader.read(
                            SOUND.replace(
                                    "    models: {agy: gemini-4-argon-high}\n",
                                    "    models: {agy: gemini-4-argon-high}\n    "
                                            + entry.getKey()
                                            + "\n"));
            assertThat(work.faults())
                    .as("the fault of %s named with its state", entry.getKey())
                    .containsExactly("stories/work.yml: Reading: " + entry.getValue());
            assertThat(work.rules().keySet())
                    .as("only the rule of %s dropped", entry.getKey())
                    .containsExactly("Implementing");
        }
    }

    @Test
    void anEmptyListIsAFault() {
        final Work work = WorkReader.read(SOUND.replace("[UNATTENDED, ATTENDED]", "[]"));
        assertThat(work.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml: Implementing: modes names no mode");
        assertThat(work.rules().keySet()).as("the other kept").containsExactly("Reading");
    }
}
