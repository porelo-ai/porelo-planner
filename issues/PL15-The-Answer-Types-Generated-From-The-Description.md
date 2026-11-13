# PL15 — The Answer Types Generated From The Description

```json
{
  "$schema": "issue-1.json",
  "id": "PL15",
  "title": "The Answer Types Generated From The Description",
  "state": "Implementing",
  "size": "M",
  "priority": "Low",
  "rank": 3,
  "blockedBy": [],
  "covers": "The planner's answer records generated from openapi/planner.yaml instead of written by hand; the controllers stay hand-written"
}
```

**Size:** M - one module's build and its about thirty answer records, an enum mapping in the controllers and the
contract test reworked; the controllers stay hand-written.

**Estimate:** 67 min, the measured median of M. Uncertain: how generator 7.27.0 reads the description's OpenAPI 3.1
null forms is not yet measured; a shape the templates do not cover fails under NullAway and costs a detour.

**What must be true.** The planner's Java answer types are generated from `openapi/planner.yaml` at build time, so a
change of the description changes the code with it and the compiler, not only a test, holds the controllers to it;
they stay records with `@Nullable` where the description allows null, and run in the native executable. Only the
answer records are generated; the controllers stay hand-written, held to the paths and statuses by
`ApiMatchesDescriptionTest`.

## Plan

Today every answer is a hand-written record, held to the description only by
`ApiMatchesDescriptionTest` (app tests): `Answers` (17 records: `Workflow`, `Board`, `Story`, `Issue`, ...),
`WorkAnswers` (14: `Proposal`, `PlanWork`, `StartTask`, `Finding`, ...) and `BoardController.ProjectSource`, used
about 80 times in `BoardController`, `EventsController`, `WorkRecord` and `StoreConfiguration`; the mock mode's
`MockStore` keeps records of its own for its board file and stays as it is.
Enum fields (`State.kind`, `WorkRule.per`, `PlanWorkTask.role`, ...) are plain `String`s filled by hand, for example
`BoardController` writes `"WORKING"` or `"WAITING"`.

- **Build** (`planner/app/pom.xml`): `openapi-generator-maven-plugin` 7.27.0, execution `answers` in
  `generate-sources`, reading `src/main/resources/openapi/planner.yaml`, models only, into the package
  `org.fuin.porelo.planner.app.answers`. Its plugin dependency is
  `org.fuin.porelo:porelo-forge-client-openapi-templates`, pinned at `0.1.0-SNAPSHOT` from Sonatype's snapshots and
  moved to the templates' first release when there is one, with `templateResourcePath`
  `org/fuin/porelo/forge/openapi/templates` (no leading or trailing slash). The options are
  copied whole from that artifact's documentation: `generatorName=spring`, `library=spring-boot`,
  `generateApis=false`, `generateModelTests=false`, `generateModelDocumentation=false`,
  `supportingFilesToGenerate=package-info.java`, `importMapping DateTimeFormat=java.time.OffsetDateTime`,
  `openapiNormalizer REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY=true`, and configOptions `useRecords=true`,
  `useJspecify=true`, `useSpringBoot4=true`, `useJakartaEe=true`, `openApiNullable=false`, `useBeanValidation=false`,
  `annotationLibrary=none`, `documentationProvider=none`, `hideGenerationTimestamp=true`. The plugin version goes into
  the root's properties.
- **Only the answers are generated.** No controller interface is generated: the templates artifact holds models only,
  and the events stream and the bodies read by hand do not fit a generated interface. The request bodies stay as
  they are (`WorkRequests`, `BoardController.MovesBody` and `MoveBody`): they are read by hand, bounded, strictly and
  with refusals that never repeat what was sent, which a generated record's constructor does not give. Where a
  request carries an answer's schema (`StoryWorkRequest.repositories` is a `PlanRepositoryWork`), it takes the
  generated record.
- **Steps:** add the execution and generate once; read every generated record against the hand one it replaces
  (fields, `@Nullable`, which fields are left out when null); replace `Answers` and the answer records of `WorkAnswers`
  by the generated ones, keeping `WorkAnswers`' factory methods (`knownWork`, `starts`, `issue`); map each core value to
  the generated enum (`fromValue`) where a `String` stood; point `@RegisterReflectionForBinding` in `EventsController`,
  and `StoreConfiguration` at the generated types; delete the hand records.
- **Tests (all unit level, app module, JVM):**
  - `ApiMatchesDescriptionTest` keeps the paths, statuses and refusals, and the check that a record writes exactly the
    fields its schema requires (the generator's own choice of `JsonInclude`); its `SCHEMAS` map points at the generated
    classes and asserts each answer schema the operations name has one, so a hand record that slips back in goes red
    first. The field-by-field and wire-type checks of generated records are deleted, since the generator writes them
    from the same schema. Harm prevented: an answer the window reads with a field missing or always written null.
  - `NullMarkedPackagesTest` (test-support) walks only `src/main/java`; it also asserts the generated package's
    `package-info.java` under `target/generated-sources` is `@NullMarked`, seen red by turning off
    `supportingFilesToGenerate`. Harm prevented: NullAway (`OnlyNullMarked=true`) silently checking nothing in the
    generated records.
  - A test that an answer built with null in a required, not nullable field is refused naming the field, seen red
    against a hand record. Harm prevented: a null the window does not expect reaching it.
  - The existing HTTP tests (`BoardReadsTest`, `StoryWorkHttpTest`, `WorkRunHttpTest`, `MockModeTest`,
    `EventsStreamTest`) hold the JSON on the wire unchanged.
- **Native:** `NativeStartIT` with `-Pnative verify` once, since the generated records reach the image through the
  same AOT binding hints as today's.
- **Measured with 7.27.0 and the templates, offline, on `planner.yaml` as it stands** (57 files): records with
  `com.fasterxml.jackson.annotation.JsonProperty`, `@Nullable` where the schema allows null, a constructor refusing
  null in a required, not nullable field, inline enums with `@JsonValue` and a throwing `fromValue`, and the package
  `@NullMarked`. Four shapes change the wire unless the plan forbids them:
  - No record carries `@JsonInclude`: an optional field that is null is written as `null` instead of left out
    (`Issue.rounds`, `roundsMost`, `findings`, `IssueDetail.whatMustBeTrue`, `Finding.file`, `line`,
    `PlanningStartTask.repository`, `result`, `TaskEnded.landed`). Each such property gets
    `x-field-extra-annotation` with a fully qualified `@JsonInclude(NON_NULL)` in `planner.yaml`, which the record
    template prints; it adds no field and changes no schema the window reads.
  - An optional array defaults to `new ArrayList<>()`, so a null `findings` would be written `[]`, losing "no record"
    against "recorded, none": `containerDefaultToNull=true` stops it (measured; only schema `default:` values stay).
  - `TaskEnded.at` (`format: date-time`) becomes `OffsetDateTime`, which may change the reported spelling or refuse
    more than nine fractional digits the core accepts: `typeMappings OffsetDateTime=String` keeps it the reported
    string (measured).
  - `Board` writes its fields in the schema's order, `repositories` second instead of sixth; JSON objects are
    unordered and the window parses them, so this is taken as is.
- **Stays hand-written besides the controllers:** `BoardController.ProjectSource` is a request body whose missing
  `repository` has a refusal of its own, and `StoryWorkRequest.repositories` keeps its hand record, so an unknown
  `role` or `choice` is still refused by the core after the revision check, not by a generated `fromValue` while
  parsing.
- **Added test (unit, app):** each answer with its optional fields null, empty and set, through the app's own
  mapper: an optional null is left out, a required nullable one written as null, an empty list written `[]`, and each
  enum written as its schema value. Harm prevented: the window reading `null` or `[]` where the field meant "not
  known" or "none recorded".

## Acceptance

- No hand-written answer record is left in `planner/app/src/main`; a change of a schema in `planner.yaml` changes the
  generated record, and a controller that no longer fits fails to compile.
- The JSON every operation answers is unchanged, shown by the existing HTTP tests, and the native executable starts
  and answers.
- The Build workflow's steps pass locally, the native build included.
- A release of the planner waits for a released templates artifact, since a release depends only on releases.

## To be checked

- That the native image's binding hints reach the generated inline enums' `@JsonValue` and `fromValue`, through a
  native answer that carries a work run with findings and landings.
- That the generated records compile against Spring Boot 4's Jackson 3 with `com.fasterxml.jackson.annotation`, as
  today's records do.
