# ST267 — Developers added and dropped through the access file

**Priority:** ⏫ Highest · **State:** Finished (2026-10-30)

**What a user gains.** Developers are added to or dropped from the signed access file, so a new developer joins from their own laptop with their own key.

**The test.** A developer added to the signed `access.yml` joins from their own laptop with their own key and works; one dropped is locked at once at the next `sync`, unless another project or the host admin still holds them; an unsigned or altered access file changes nothing; a project taken off locks whoever has no membership left, and `down` refuses while anyone holds one, naming them.

**Issues:**

- [porelo-frontend#F215](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f215) (Finished)
- [porelo-frontend#F216](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f216) (Finished)
- [porelo-frontend#F232](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f232) (Finished)

## Covers

- porelo-project#PJ78

## Data

```json
{
  "id": "ST267",
  "title": "Developers added and dropped through the access file",
  "state": "Finished",
  "gain": "Developers are added to or dropped from the signed access file, so a new developer joins from their own laptop with their own key.",
  "test": "A developer added to the signed `access.yml` joins from their own laptop with their own key and works; one dropped is locked at once at the next `sync`, unless another project or the host admin still holds them; an unsigned or altered access file changes nothing; a project taken off locks whoever has no membership left, and `down` refuses while anyone holds one, naming them.",
  "priority": "Highest",
  "issues": [
    "porelo-frontend#F215",
    "porelo-frontend#F216",
    "porelo-frontend#F232"
  ],
  "tested": "2026-10-30"
}
```
