# Issues

What is open in this repository, ordered by what to do next rather than by number. The number is
identity, not sequence.

**Grouped by state**, each group's heading naming it, as the shared rules set them out; within a group
the order is the priority. Only the operator sets Accepted, Rejected, Estimated, Backlog, Rework and
Finished; this repository's agent sets Analyzed, Implementing and Review, and proposes the rest in the
channel.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="sl38"></a>[SL38](SL38-Mutation-Testing-With-PIT.md) | M | — | A `pit` profile run by hand, with no threshold and pinned tools, and a report sorting the survivors into real gaps and harmless ones; the harm-guarding classes sorted into eight gap issues. |
| <a id="sl36"></a>[SL36](SL36-Snapshots-Resolved-With-A-Read-Only-Token.md) | S | — | No workflow holds a token that can write to a forge's registry: snapshots resolved with a read-only token, and a Build on a forge other than GitHub without `MAVEN_SNAPSHOTS_URL` or that token fails at once. |
| <a id="sl37"></a>[SL37](SL37-A-Task-Of-Another-Project-Named-As-A-Peer.md) | S | — | A peer named `task:<project>:<task>`, a task of another project by its certificate's principal, passes the envelope check; a `:` anywhere else in a peer name is still refused. |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="sl41"></a>[SL41](SL41-A-Refusal-Never-Shows-What-It-Matched.md) | S | — | A finding's explanation that quotes its match is refused, and keys are hidden when the detectors did not run: both held by no test today (PIT). |
| <a id="sl49"></a>[SL49](SL49-A-Credential-Context-Is-Read-On-Both-Sides.md) | XS | — | A credential whose context word follows it is refused under a test; PIT shows the window after the match held by none. |
| <a id="sl42"></a>[SL42](SL42-Every-Part-Of-A-Task-Is-Walked.md) | S | — | A task's artifacts, their parts, metadata and extensions, a part's URL and a nested A2A 0.3 shape each reach the detectors and limits under a test; today no test fails without them (PIT). |
| <a id="sl47"></a>[SL47](SL47-No-Message-Is-Lost-Or-Filed-Twice.md) | M | — | A free name is never a taken one, a signature that went ahead is not filed apart, a file over the limit is answered, a swapped directory noticed, the stability delay's path tested (PIT). |
| <a id="sl43"></a>[SL43](SL43-Masking-Hides-No-Payload.md) | S | — | An impossible host, a UUID chain, many short hashes, a long path segment and a URL's user part stay unmasked under a test (PIT). |
| <a id="sl44"></a>[SL44](SL44-Every-Encoding-Signature-Is-Held.md) | M | — | uuencode, ascii85, hex bytes, dense escapes and the alphabet runs each refused by a test of their own, the prose exemptions held on both sides (PIT). |
| <a id="sl45"></a>[SL45](SL45-Invisible-Characters-And-Look-Alikes-Are-Held.md) | S | — | Every kind of invisible character, a joiner outside an emoji and a mixed-script word each found by a test (PIT). |
| <a id="sl46"></a>[SL46](SL46-The-Statistical-Detectors-Are-Held-To-Their-Rules.md) | M | — | Each rule of the token-shape, entropy and letter-distribution detectors held just inside and just outside its limit (PIT). |
| <a id="sl48"></a>[SL48](SL48-The-Remaining-Classes-Sorted.md) | S | — | The 120 mutants left in eleven classes not sorted first, sorted the same way, `SshSignature`, `DirectoryLock` and `MessageArchive` first. |

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="sl40"></a>[SL40](SL40-A-Workflow-Only-Change-Is-Checked-On-A-Forge.md) | S | — | 0 | A pull request changing only `.github/` is checked on a host's forge too: Forgejo ignored a job `check` for its `permissions` field, most likely the light Workflow check; measured there first, GitHub's read-only token kept. |
| <a id="sl24"></a>[SL24](SL24-A-Code-Comment-The-Filters-Side.md) | L | — | 0 | A review comment as SARIF 2.1.0 passes only in the closed profile: anchors checked by shape, prose judged as prose, one bounded fix judged by detectors measured on code, any other field or claimed authority refused both ways. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="sl39"></a>[SL39](SL39-An-Organization-Proved-Against-Real-GitHub.md) | — | — | A short test by a person, against real GitHub: `trust-host`, `org create`, `setup` and `project open` at a `git@github.com:` address, with the read back; waits until after the next release |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="sl04"></a>[SL04](SL04-An-External-Detector-Measured.md) | L | — | Somebody else's detector catalog behind the interface, measured before anything is adopted. |
| <a id="sl23"></a>[SL23](SL23-The-Narrowed-Message-Schema-The-Filters-Side.md) | M | — | Porelo's narrowed message schema enforced in the envelope: four `metadata` keys, six kinds with their fixed data parts, one text part, one extension URI. |
| <a id="sl08"></a>[SL08](SL08-Refuse-A-Payload-Spread-Over-Several-Messages.md) | XL | — | A payload cut into pieces and sent over several messages, caught by correlating against what the task already sent. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="sl35"></a>[SL35](README.md#sl35) | The Sluice's Agent Works In A Porelo Task | The sluice's agent worked in a Porelo task on a rented host's forge and came back: the gate's push, the pull request, the Shared rules check and a Build started by hand all green there, the skills fetched in the task, and the work brought back to this checkout. |
| <a id="sl33"></a>[SL33](README.md#sl33) | The Workflows Run On The Local Forge As On GitHub | The Build runs unchanged on the local Forgejo and on GitHub, switched only by variables: missing package tools installed, Publish switched by `PORELO_PUBLISH`, snapshots read from `MAVEN_SNAPSHOTS_URL`. |
| <a id="sl32"></a>[SL32](README.md#sl32) | Refuse personal and financial data | Payment cards, IBANs and national ids are refused, e-mail addresses, phone numbers, IP addresses and dates of birth reported suspicious, cards and IBANs told by their checksums, matched values redacted in answers, and false positives under 1 % on clean English. |
| <a id="sl07"></a>[SL07](README.md#sl07) | Refuse credentials | A catalog of pattern, validation and context catches ten credential categories in text and metadata, with matched values redacted in answers, rules switchable, and false positives under 1 % on clean English. |
| <a id="sl12"></a>[SL12](README.md#sl12) | Refuse a description that stopped being true | A documents test refuses a mismatch between the modules named in `AGENTS.md`, the root pom's reactor, and the directories on disk, with each failure naming both sides and each module having its page in `doc/`. |
| <a id="sl31"></a>[SL31](README.md#sl31) | Publish without the JFrog action | The Publish job reaches Artifactory with `curl` and the token on standard input, removing `jfrog/setup-jfrog-cli` so no third-party action handles the publishing token. |
| <a id="sl30"></a>[SL30](README.md#sl30) | Old runs deleted on a named runner | *Delete old runs* and the *Shared rules check* run on `ubuntu-26.04` instead of `ubuntu-latest`, so runner version changes require an explicit commit here. |
| <a id="sl29"></a>[SL29](README.md#sl29) | Built in a Porelo task | `porelo.yml` names the tools of the whole local check, and that check, native images and both packages included, ran green inside a Porelo task built from it. |
| <a id="sl28"></a>[SL28](README.md#sl28) | Modes that cannot go together are refused | A command line asking for two of the four modes, or for `--file` or `--dir` without its mode, runs nothing and exits 2 naming both. |
| <a id="sl25"></a>[SL25](README.md#sl25) | The code changed since the skills reading | The code changed since 2026-09-27 read against the seven skills and `shellcheck`, its sixteen findings fixed (each that guards a harm with a test seen red), published as `0.5.1~snapshot.51`. |
| <a id="sl27"></a>[SL27](README.md#sl27) | Reformatted to the shared style | Spotless with the shared Java, YAML and shell formats, one reformatting commit that `git blame` skips, the shared `.editorconfig`, and checkouts without a kept token. |
| <a id="sl26"></a>[SL26](README.md#sl26) | The rename to Porelo here | The filter is published as `org.fuin.porelo` 0.5.0 and installed where Porelo looks for it, accepts only signatures in the namespace `porelo-message`, and a documents test refuses the old name in any tracked file. |

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="sl34"></a>[SL34](README.md#sl34) | A Dependabot Pull Request Gets The Variables | Measured harmless: an empty `MAVEN_SNAPSHOTS_URL` activates `settings.xml`'s `standard` profile as an unset one does, so Maven reads Sonatype's snapshots on GitHub and no empty URL. |

**Open questions**, in *Analyzed* only, counts what waits on the operator; what this repository's
agent finds out itself stands under each issue's *To be checked* and is not counted.

**Size** is how big it is, read in the code: XS under an hour, S a few hours, M about a day, L several
days, XL too big to build as one, split in its plan.

**Blocked by** names what an issue waits for: an issue here by its number, a Porelo requirement as
`Porelo B<n>`.

## One module, one job

`filter/` decides what may pass and knows no transport. The transports that carry what passed live
in repositories of their own; what the filter shares with them is the directory contract and the
message format, both written down rather than compiled against.

## Where Porelo comes in

This is a program that reads and writes directories. **Porelo** is the other half - the mailbox mounted into a task, who feeds this filter, which
transport a peer uses, and what may be in a message in the first place.
