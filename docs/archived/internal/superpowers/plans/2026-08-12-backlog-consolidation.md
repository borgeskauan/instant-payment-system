# Backlog Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the project backlog under `docs/board/Atividades/Backlog/`, with category directories and one Markdown file per task.

**Architecture:** Replace each legacy aggregate file with a directory named after its category. Preserve every task body and checkbox in an individual file, then update internal links to the new canonical path.

**Tech Stack:** Markdown, Git, shell validation with `find`, `rg`, `diff`, and `git diff --check`.

## Global Constraints

- Preserve task content and checkbox state.
- Promote each task section heading from `##` to `#`.
- Group the three existing root-level tasks by responsibility without changing their contents.
- Do not create category indexes or `README` files.
- Preserve the existing uncommitted move of `auditoria-rejeicoes-entrada.md`.
- Do not create commits or stage changes.

---

### Task 1: Consolidate all backlog tasks into category directories

**Files:**
- Remove: `docs/board/Backlog/infra-documentacao.md`
- Remove: `docs/board/Backlog/operacao-testes.md`
- Remove: `docs/board/Backlog/produto-dominio.md`
- Create: the eleven task files listed in the approved design under `docs/board/Atividades/Backlog/<category>/`
- Move: `auditoria-rejeicoes-entrada.md` and `engenharia-caos-resiliencia-operacional.md` to `operacao-testes/`
- Move: `retentativa-liquidacao-pagamentos-em-processamento.md` to `produto-dominio/`

**Interfaces:**
- Consumes: the eleven `##` task sections in the three legacy aggregate files.
- Produces: fourteen categorized Markdown files, one per task, with aggregate section titles promoted to `#`.

- [ ] **Step 1: Record the source section inventory and checkbox counts**

Run:

```bash
rg -n '^(## |[-*] \[[ xX]\])' docs/board/Backlog/*.md
```

Expected: eleven `##` task headings and all current task checkboxes are visible.

- [ ] **Step 2: Create and categorize the individual task files**

Use `apply_patch` to copy each complete task section into the destination named in the approved design. Change only the first heading from `##` to `#`; omit the three aggregate-file introductions.

Move the three previously individual task files to their approved category without changing their contents.

- [ ] **Step 3: Remove the aggregate files**

Use `apply_patch` to delete the three legacy Markdown files. The now-empty `docs/board/Backlog/` directory disappears from Git automatically.

- [ ] **Step 4: Compare task and checkbox inventories**

Run:

```bash
rg -n '^(# |[-*] \[[ xX]\])' docs/board/Atividades/Backlog/infra-documentacao docs/board/Atividades/Backlog/operacao-testes docs/board/Atividades/Backlog/produto-dominio
```

Expected: fourteen top-level task titles; the eleven split tasks preserve the source checkbox texts and states, and the three moved tasks remain byte-identical.

### Task 2: Update references to migrated tasks

**Files:**
- Modify: `docs/board/Atividades/concluidas/auditoria-transacoes-spi.md`
- Modify: `docs/board/Atividades/concluidas/cenarios-realistas-reprocessamento-load-tool.md`

**Interfaces:**
- Consumes: the new canonical files for rejection auditing, chaos engineering, and load-test stabilization.
- Produces: valid relative links from the completed audit and workload-matrix tasks.

- [ ] **Step 1: Replace legacy links**

Change both links from `../../Backlog/operacao-testes.md` to `../Backlog/operacao-testes/estabilizar-teste-carga-budget-cpu.md`, preserving their surrounding prose.

Change the rejection-audit link from `../agora/auditoria-rejeicoes-entrada.md` to `../Backlog/operacao-testes/auditoria-rejeicoes-entrada.md`, and the chaos link from `../Backlog/engenharia-caos-resiliencia-operacional.md` to `../Backlog/operacao-testes/engenharia-caos-resiliencia-operacional.md`.

- [ ] **Step 2: Check for legacy backlog references**

Run:

```bash
rg -n 'docs/board/Backlog|\.\./\.\./Backlog/(infra-documentacao|operacao-testes|produto-dominio)\.md' docs
```

Expected: no references to the deleted aggregate paths, except historical path descriptions inside the design and implementation-plan documents.

### Task 3: Verify the final documentation tree

**Files:**
- Verify: `docs/board/Atividades/Backlog/**`
- Verify: `docs/board/Atividades/concluidas/cenarios-realistas-reprocessamento-load-tool.md`

**Interfaces:**
- Consumes: all migrated task files and updated links.
- Produces: evidence that the backlog is consolidated without content loss or formatting errors.

- [ ] **Step 1: List the canonical backlog**

Run:

```bash
find docs/board/Atividades/Backlog -type f -print | sort
```

Expected: fourteen categorized task files and no root-level task files.

- [ ] **Step 2: Confirm the legacy backlog contains no files**

Run:

```bash
find docs/board/Backlog -type f -print 2>/dev/null
```

Expected: no output, whether the directory is absent or empty.

- [ ] **Step 3: Validate Markdown whitespace and review the diff**

Run:

```bash
git diff --check
git status --short
git diff -- docs/board
```

Expected: `git diff --check` succeeds; all changes remain unstaged and uncommitted; the diff shows structural splitting and link updates only.
