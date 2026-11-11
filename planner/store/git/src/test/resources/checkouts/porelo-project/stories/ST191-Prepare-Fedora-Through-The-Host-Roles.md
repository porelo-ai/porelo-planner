# ST191 — Prepare Fedora through the host roles

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person can prepare a Fedora host with the host roles, with SELinux, firewalld and dnf set up and forbidden service access refused.

**The test.** Set up a Fedora test host and check SELinux, firewalld and dnf. Forbidden service access remains blocked. Run setup again; it makes no unjustified change.

**Issues:**

- [porelo-host#HO15](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho15) (Idea)
- [porelo-frontend#F138](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f138) (Idea)

## Notes

- Configure Fedora Host Roles; Verify Fedora Host Provisioning. These parents also cover sprint 8; the unattended setup and reach-boundary releases must pass first.

## Data

```json
{
  "id": "ST191",
  "title": "Prepare Fedora through the host roles",
  "state": "Idea",
  "gain": "A person can prepare a Fedora host with the host roles, with SELinux, firewalld and dnf set up and forbidden service access refused.",
  "test": "Set up a Fedora test host and check SELinux, firewalld and dnf. Forbidden service access remains blocked. Run setup again; it makes no unjustified change.",
  "priority": "Low",
  "issues": [
    "porelo-host#HO15",
    "porelo-frontend#F138"
  ],
  "tested": null
}
```
