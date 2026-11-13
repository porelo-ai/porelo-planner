# PL15 — The Answer Types Generated From The Description

```json
{
  "$schema": "issue-1.json",
  "id": "PL15",
  "title": "The Answer Types Generated From The Description",
  "state": "Review",
  "size": "M",
  "priority": "Low",
  "rank": 3,
  "blockedBy": [],
  "covers": "The planner's answer records generated from openapi/planner.yaml instead of written by hand; the controllers stay hand-written"
}
```

**Size:** M - one module's build and its about thirty answer records, an enum mapping in the controllers and the
contract test reworked; the controllers stay hand-written.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The planner's Java answer types are generated from `openapi/planner.yaml` at build time, so a
change of the description changes the code with it and the compiler, not only a test, holds the controllers to it;
they stay records with `@Nullable` where the description allows null, and run in the native executable. Only the
answer records are generated; the controllers stay hand-written, held to the paths and statuses by
`ApiMatchesDescriptionTest`.

## Plan

Every schema an operation answers with success, the events stream's `Board` and every schema nested in them, is a
record generated into `org.fuin.porelo.planner.app.answers`; no hand-written answer record is left in
`planner/app/src/main`. `Answers` is deleted; `WorkAnswers` keeps only its factories (`knownWork`, `starts`, `issue`,
`proposal`, `order`, `discussion`); `HealthController`, `ProofController` and `LogLevelController` answer with the
generated `Health`, `Proof` and `Level`.

- **Build** (`planner/app/pom.xml`): `openapi-generator-maven-plugin` at `openapi-generator.version` 7.27.0 (root
  property), execution `answers` in `generate-sources`, reading `src/main/resources/openapi/planner.yaml`, models
  only, into `org.fuin.porelo.planner.app.answers` under `target/generated-sources/openapi`. Its plugin dependency is
  `org.fuin.porelo:porelo-forge-client-openapi-templates` at `porelo.openapi.templates.version` `0.1.0-SNAPSHOT`
  (root property) from Sonatype's snapshots, moved to the templates' first release when there is one, with
  `templateResourcePath` `org/fuin/porelo/forge/openapi/templates`. Options: `generatorName=spring`,
  `library=spring-boot`, `generateApis=false`, `generateModelTests=false`, `generateModelDocumentation=false`,
  `supportingFilesToGenerate=package-info.java`, `importMapping DateTimeFormat=java.time.OffsetDateTime`,
  `typeMappings OffsetDateTime=String` and `UUID=String`, `openapiNormalizer
  REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY=true`, and configOptions `useRecords`, `useJspecify`, `useSpringBoot4`,
  `useJakartaEe`, `containerDefaultToNull` true, `openApiNullable`, `useBeanValidation` false, `annotationLibrary`,
  `documentationProvider` none, `hideGenerationTimestamp` true. The generated records carry
  `com.fasterxml.jackson.annotation.JsonProperty`, `@Nullable` where the schema allows null, a constructor refusing
  null in a required, not nullable field with the field's name, inline enums with `@JsonValue` and a throwing
  `fromValue`, and the package `@NullMarked`; they compile against Spring Boot 4's Jackson 3 and NullAway.
- **What would change the wire, and how it is kept:**
  - An optional field that is null would be written `null`: each optional answer property carries
    `x-field-extra-annotation` with a fully qualified `@JsonInclude(NON_NULL)` in `planner.yaml` (`Issue.rounds`,
    `roundsMost`, `findings`, `IssueDetail.whatMustBeTrue`, `Finding.file`, `line`, `PlanWorker.effort`, `chosenBy`,
    `PlanAssignment.effort`, `chosenBy`, `PlanningStartTask.repository`, `effort`, `chosenBy`, `result`,
    `TaskEnded.landed`, `outcome`); it adds no field and changes no schema the window reads.
  - An optional list would default to `[]`: `containerDefaultToNull=true` keeps it left out.
  - `TaskEnded.at` (`date-time`) and the offers (`uuid`, `PlanningStartTask.offer`, `MoveWarning.offers`) would become
    `OffsetDateTime` and `UUID`, re-spelled or refused while an answer is built: both type mappings keep the strings
    the core holds.
  - Fields are written in the schema's order: `Board.repositories` second instead of sixth, `IssueDetail`'s
    `text`, `subIssues` and `whatMustBeTrue` after `Issue`'s fields. JSON objects are unordered and the window parses
    them, so this is taken; the screen's recorded mock answers (`screen/test/served/mock/board-event.json`,
    `issue-B541.json`) are recorded again, equal as JSON to the copies before.
- **Each core value goes through its generated enum** (`fromValue`) where a `String` stood: `State.kind`,
  `WorkRule.per`, `Issue.worker` and `attention`, `PlanWorkTask.role`, `PlanWorkIssue.reason`, `Finding.severity`,
  `TaskEnded.outcome`, `PlanWorker.role`, `PlanAssignment.choice`, `PlanningStartTask.role`,
  `StoryWorkProposal.coordinator`, `MoveWarning.kind`, `Health.status`, `Level.level`; the core's `MovePreview`
  records and `NextStep.Landing` are mapped to the generated ones. `@RegisterReflectionForBinding` in
  `EventsController` and `StoreConfiguration` names the generated `Board`, `PlanWork`, `TaskEventReply` and
  `ReadingReply`.
- **Stays hand-written besides the controllers:** the bodies, read by hand, bounded, strictly and with refusals that
  never repeat what was sent: `WorkRequests` (its `Repository`, `Main` and `Assignment` now hold
  `StoryWorkRequest.repositories`, so an unknown `role` or `choice` is still refused by the core after the revision
  check, and its `TaskEnded` is the report's), `BoardController.MovesBody`, `MoveBody` and `ProjectSource` (a missing
  `repository` has a refusal of its own), and `LogLevelController.Chosen` (an unknown level is an `InvalidLevel`).
  The mock mode's `MockStore` keeps its records for its board file.
- **Tests (unit level, app and test-support, JVM):**
  - `ApiMatchesDescriptionTest`: `SCHEMAS` maps each answer schema to its generated record and each body's schema to
    its hand record; `READ_BY_HAND` names the bodies' records of schemas an answer carries too. It keeps the paths,
    statuses and refusals and the check that a record writes exactly the fields its schema requires; it adds that
    every schema an operation answers, nested ones and the stream's `Board` too, is a record of the generated
    package, seen red by mapping `Proof` to a hand record. The wire-type check runs only over the records read by
    hand. Harm prevented: a hand-written answer record slipping back in and drifting from the description.
  - `AnswerRecordsTest`, over every generated answer record and the mapper Spring Boot's `JacksonAutoConfiguration`
    sets up: an answer built with null in a required, not nullable field is refused naming the field (seen red
    against a hand `Health`); each optional field null is left out, each required nullable one written `null`, each
    empty list written `[]` (seen red by dropping the extension from `Issue.rounds`); each enum is written as exactly
    the values its schema names (seen red against a hand `State` spelling `closed`). Harm prevented: the window
    reading `null` or `[]` where a field meant "not known" or "none recorded", or a value it does not recognize.
  - `NullMarkedPackagesTest` (test-support) also asserts the generated package's `package-info.java` exists and is
    `@NullMarked`, seen red by generating without supporting files. Harm prevented: NullAway (`OnlyNullMarked=true`)
    silently checking nothing in the generated records.
  - The HTTP tests (`BoardReadsTest`, `StoryWorkHttpTest`, `WorkRunHttpTest`, `MockModeTest`, `EventsStreamTest`,
    the access and proof tests) hold the JSON on the wire unchanged; `BoardReadsTest`, `WorkRunHttpTest` and
    `WorkAnswersTest` name the generated types where they called a factory directly.
- **Native:** `NativeStartIT` with `-Pnative verify` starts the executable and answers through the generated records,
  among them the workflow's `"per":"repository"`, an enum whose value is not its constant's name, so `@JsonValue`
  reaches the image through Spring's binding hints. A work run with findings and landings is not answered natively;
  `Finding` and `Landing` reach the image as nested records of `PlanWorkIssue` and `TaskEnded` by the same hints.
- **Reading by another model.** A value the core gives through a generated enum's `fromValue` throws when the
  schema lacks it, where a hand record passed the string on: `WorkAnswersTest` maps every constant of the core's
  enums the answers write (the run's reason, the issue's attention, a finding's severity, a declared outcome, the
  worker), so a constant added to the core fails there first. Declined notes: the test of the generated package's
  `@NullMarked` reads the app's generated sources, which the reactor builds before `test-support`, and a run of
  `test-support` alone fails loud, not silent; a role read back from the local record outside the schema's list
  would fail a read, but every role the core writes is listed, and the record itself is not rewritten.

## Acceptance

- No hand-written answer record is left in `planner/app/src/main`; a change of a schema in `planner.yaml` changes the
  generated record, and a controller that no longer fits fails to compile.
- The JSON every operation answers is unchanged, shown by the existing HTTP tests, and the native executable starts
  and answers.
- The Build workflow's steps pass locally, the native build included.
- A release of the planner waits for a released templates artifact, since a release depends only on releases.
