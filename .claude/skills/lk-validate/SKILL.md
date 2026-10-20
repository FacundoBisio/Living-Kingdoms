---
name: lk-validate
description: Run the Living Kingdoms validation suite and report results briefly.
disable-model-invocation: true
---

Run in order, stop at the first failure and inspect it before retrying:
1. `.\gradlew.bat test`
2. `.\gradlew.bat -PgameTestDirectory=runs/validate-<timestamp> runGameTestServer` (fresh dir; a reused world exhausts the encounter cap and gives false results)
3. `.\gradlew.bat build`
4. `git diff --check`

Report: what passed, what failed with the key log line, and what was NOT run.
Graphical checks (client, Save & Quit/reopen) stay manual; never claim them as done.
