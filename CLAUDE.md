@AGENTS.md

## Claude Code
- This file imports AGENTS.md. Where AGENTS.md says to use Codex, use Claude Code instead; project skills live in .claude/skills/.
- Reply to the user in Spanish (Argentina); keep code, comments, docs and commits in English.
- Never add Claude attribution: no `Co-Authored-By: Claude` trailers in commits and no "Generated with Claude Code" lines in PRs or issues.
- Never create docs or commits unless asked. Run the `lk-validate` skill before reporting runtime changes as done.
- Use plan mode for any feature that touches persistence, networking or settlement/quest/encounter systems.
