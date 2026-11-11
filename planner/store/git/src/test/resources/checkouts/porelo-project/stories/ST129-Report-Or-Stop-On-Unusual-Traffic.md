# ST129 — Report or stop on unusual traffic

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** Unusual traffic, such as odd body sizes, clearance attempts or DNS names, is reported or stops the task on chosen alarms.

**The test.** Record normal traffic, then generate unusual body size, clearance attempts and DNS names. In alert-only mode an alert appears; in stop mode the task pauses or stops and its token becomes invalid.

**Issues:**

- [porelo#B139](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b139) (Estimated)

## Notes

- Establish Traffic Baselines; Report Traffic Anomalies; Stop Tasks On Selected Alerts. Notifications from release 0.9 must work first.

## Data

```json
{
  "id": "ST129",
  "title": "Report or stop on unusual traffic",
  "state": "Estimated",
  "gain": "Unusual traffic, such as odd body sizes, clearance attempts or DNS names, is reported or stops the task on chosen alarms.",
  "test": "Record normal traffic, then generate unusual body size, clearance attempts and DNS names. In alert-only mode an alert appears; in stop mode the task pauses or stops and its token becomes invalid.",
  "priority": "Low",
  "issues": [
    "porelo#B139"
  ],
  "tested": null
}
```
