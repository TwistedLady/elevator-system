# Dev: many branches, one IntelliJ window

Run several branches at once as git worktrees, all open in one IntelliJ window with correct,
distinct module names.

## Layout

```
/home/twist/repo/elevator/          # base workspace (NOT a git repo)
  elevator-system/                  # the git repo — branch: main
  elevator-<task>/                  # git worktree — branch: <task>
  kanban.md  .knowledge/  .skill/   # workspace-level, shared across worktrees, not in git
```

One worktree + branch per task, **siblings** of `elevator-system/`. `main` is trunk — never
developed on directly.

```bash
git -C elevator-system worktree add ../elevator-<task> -b <task> main
```

## The module-name problem

Every worktree is the same repo → identical Maven coordinates (`pl.feelcodes.elevator:elevator`).
IntelliJ names modules from `<artifactId>`, so it shows `elevator (1)/(2)/(3)` and collides.
**A "Reload All Maven Projects" regenerates names from the pom and wipes any manual rename.** So
we never edit poms and never reload; we fix it on the IntelliJ side, kept in the project (not the
IDE cache).

## The fix — with IntelliJ **CLOSED**

Two scripts live in the base workspace `.skill/` dir (sibling of the repo; machine-specific,
kept out of git):

1. Confirm IntelliJ is not running: `ps -eo pid,args | grep '/bin/idea' | grep -v grep`
2. `.skill/rename_modules.sh` — copies IDE-cache module content into the project as renamed
   `.iml`, rewrites `.idea/modules.xml`, sets external storage off, disables cache config.
   Edit the `rename_tag`/`fix_refs` maps at the top to map each worktree's `(1)/(2)/(3)` suffix
   to a readable tag.
3. `.skill/split_libs.py` — migrates the 200+ Maven libraries from the cache into
   `.idea/libraries/`.
4. Open IntelliJ, **do not reload Maven**, verify the names.

## Hard rules

- **IntelliJ closed** for every `.idea` edit — a running IDE overwrites the files.
- **Never** "Reload All Maven Projects" — it wipes the names. If a reload is unavoidable
  (e.g. dependency change), re-run the two scripts after.
- **Never** edit `pom.xml` to fix names — the module id must stay `elevator`.

> Cache dir: `~/.cache/JetBrains/IntelliJIdea<ver>/projects/elevator.<hash>/external_build_system`.
