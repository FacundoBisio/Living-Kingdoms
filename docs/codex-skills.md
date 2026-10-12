# Codex development skills

Prepared on 2026-10-11 using installed Codex `0.162.0-alpha.17.2`.
The six requested skills are local to this repository in `.agents/skills/`;
no global installations or other-agent configurations were added. There was
no existing project `AGENTS.md`, so a concise one was created at the root.

## Installed sources

| Skill | Repository | Reviewed source directory | Pinned commit |
|---|---|---|---|
| `minecraft-modding` | [minecraft-agent-skills](https://github.com/Jahrome907/minecraft-agent-skills) | `.agents/skills/minecraft-modding` | `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0` |
| `minecraft-testing` | Same Minecraft repository | `.agents/skills/minecraft-testing` | Same Minecraft commit |
| `minecraft-resource-pack` | Same Minecraft repository | `.agents/skills/minecraft-resource-pack` | Same Minecraft commit |
| `minecraft-world-generation` | Same Minecraft repository | `.agents/skills/minecraft-world-generation` | Same Minecraft commit |
| `token-optimization` | [agent-skills](https://github.com/bm629/agent-skills) | `skills/token-optimization` | `4e007907d97baaa35099b50cd6bc1a332c386c98` |
| `context-optimization` | [Agent-Skills-for-Context-Engineering](https://github.com/muratcankoylan/Agent-Skills-for-Context-Engineering) | `skills/context-optimization` | `58b55a8921758d13453b440704fb1b5b208c0b0e` |

The bundled Codex `skill-installer` installed these pinned revisions using
its download method. All 26 upstream files, including references and seven
scripts, are preserved byte-for-byte. `.agents/skills-lock.json` records their
source paths, revisions, and SHA-256 hashes. Each folder additionally contains
Codex-only `agents/openai.yaml` metadata with explicit invocation policy.

## Demand-based use and compatibility

Invoke the relevant skill by name, for example:

```text
$minecraft-modding Fix the requested NeoForge 1.21.1 behavior.
$minecraft-testing Add a regression test for that behavior.
$minecraft-resource-pack Review this Minecraft 1.21.1 texture/model change.
$minecraft-world-generation Review the requested 1.21.1 structure generation.
$token-optimization Diagnose excessive token use in this task.
$context-optimization Reduce irrelevant context reads for this task.
```

`policy.allow_implicit_invocation: false` prevents automatic injection.
References are loaded only when the invoked task needs them. Upstream mentions
of other skills are optional routing suggestions; those skills were not installed.

The Minecraft sources also cover 26.x, later 1.21 patches, Fabric, Forge and
Paper. They are guidance, not a replacement for this project's exact dependency
APIs. `AGENTS.md` pins Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21 and requires
checking existing sources and version-matched schemas. Keep this project's
annotation-based GameTests; do not copy newer test registration or 1.21.4+
item-definition JSON into it.

## Script review and Windows requirements

All six `SKILL.md` files, all seven external scripts, and the installer were
read before installation. The validators read local inputs and report checks;
the Node shims use built-in file/JSON APIs. No downloaded script fetches code,
changes agent settings, or installs dependencies. `check-build.sh` invokes the
project Gradle wrapper and can therefore download normal build dependencies;
it was syntax-checked, not executed. `compaction.py` is an illustrative,
in-memory helper, not an integration with Codex's live context or cache.

Bash is available at `C:/Program Files/Git/bin/bash.exe`, although not on the
PowerShell PATH. Call `.sh` helpers with that executable. Resource/worldgen
validators use `jq` if available, otherwise their included Node.js shims;
Node.js and Python are already available. No extra runtime was installed.

The test-layout validator assumes mod metadata is a static file under
`src/main/resources`; this project generates it from `src/main/templates`
and has a separate test mod. Its findings require interpretation. Resource
and worldgen validators check syntax/references, not exact Minecraft codecs
or actual runtime behavior. Use project builds and GameTests for future code changes.

## Verification and refresh

The installed Codex app-server's read-only `skills/list`, with this repository's
working directory and `forceReload`, recognizes all six once, with repository
scope, enabled state, UI metadata, and no skill parser errors. File hashes,
referenced files, Bash/Node/Python syntax, three helper help commands, both
Node JSON shims, and the isolated compaction demonstration were checked.

Temporary audit scripts and reports are under ignored `build/skill-audit/`.
Minecraft unit tests, GameTests and Gradle build were not rerun for this
instructions-only change. Gameplay, dependencies, worlds and existing tests
were not edited. No commit, merge or push was performed.

[Codex discovers repository `.agents/skills` and detects skill changes](https://learn.chatgpt.com/docs/build-skills).
Try explicit invocation on the next turn; restart Codex only if the current
desktop session does not refresh its skill list.
