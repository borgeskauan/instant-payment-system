# Document Stack Volume Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document how to stop the local stack and remove its persistent volumes.

**Architecture:** Extend the existing `README.md` shutdown section. Preserve the non-destructive command as the default and present the volume-removing command as an explicitly destructive reset alternative.

**Tech Stack:** Markdown, Docker Compose.

## Global Constraints

- Do not commit the change; the worktree must remain available for diff review.
- Keep `docker compose down` as the normal shutdown path.
- Warn that the reset permanently removes PostgreSQL and Kafka data.

---

### Task 1: Document the destructive reset command

**Files:**
- Modify: `README.md`
- Test: Markdown inspection and `git diff --check`

**Interfaces:**
- Consumes: the existing `Encerrar o ambiente` section.
- Produces: a visible reset command for developers operating the local stack.

- [x] **Step 1: Extend the shutdown documentation**

Keep the existing command and add this alternative immediately afterward:

```bash
docker compose -f infra/docker-compose.yml down -v --remove-orphans
```

State explicitly that it permanently removes PostgreSQL and Kafka persisted data while leaving Docker images intact.

- [x] **Step 2: Verify the documentation diff**

Run:

```bash
git diff --check
```

Expected: exit code `0` with no output.
