# Visible Load-Test Profile Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the canonical load-test profiles to `load-test/profiles/` so operators can discover them without entering the Go implementation directory.

**Architecture:** `load-test/profiles/` becomes the only editable profile catalog. The shell runner resolves names there, while the internal Go validator reaches the sibling catalog from the `go-loadtool` module root; prepared runs remain isolated because `go-loadtool run` continues to consume only `<run-dir>/profile.json`.

**Tech Stack:** Bash, Go 1.24, JSON, Git.

## Global Constraints

- Preserve the public command `./run-load-test.sh [--profile NAME] <run-tag>` unchanged.
- Move the JSON files byte-for-byte; do not retain a copy, symlink, fallback, `--config`, or arbitrary profile path.
- Keep `uniform-smoke` as the default profile.
- Do not change profile schemas, workload values, bundle layout, simulation, or reporting.
- Do not run a service-backed smoke; this refactor ends before snapshot consumption.

---

### Task 1: Move the canonical profile catalog

**Files:**
- Move: `load-test/go-loadtool/profiles/uniform-smoke.json` → `load-test/profiles/uniform-smoke.json`
- Move: `load-test/go-loadtool/profiles/mixed-outcomes-smoke.json` → `load-test/profiles/mixed-outcomes-smoke.json`
- Modify: `load-test/run-load-test.sh:7`
- Modify: `load-test/go-loadtool/internal/config/config.go:27`
- Modify: `load-test/go-loadtool/internal/config/config_test.go`
- Test: `load-test/tests/profile-selection-test.sh`
- Test: `load-test/tests/profile-contract-test.sh`

**Interfaces:**
- Consumes: profile names matching the existing lowercase/digit/hyphen contract.
- Produces: shell resolution at `load-test/profiles/<name>.json`; Go `LoadProfile(name)` resolution at `../profiles/<name>.json` when invoked from the `go-loadtool` module root.

- [ ] **Step 1: Add a failing Go test for the sibling catalog boundary**

Add this test next to the other `LoadProfile` tests in `internal/config/config_test.go`:

```go
func TestLoadProfileReadsSiblingCatalogFromModuleRoot(t *testing.T) {
	root := t.TempDir()
	profiles := filepath.Join(root, "profiles")
	moduleRoot := filepath.Join(root, "go-loadtool")
	if err := os.Mkdir(profiles, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(moduleRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	writeProfile(t, profiles, "explicit-profile", testProfile)
	t.Chdir(moduleRoot)

	cfg, err := LoadProfile("explicit-profile")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Name != "explicit-profile" {
		t.Fatalf("Name = %q, want explicit-profile", cfg.Name)
	}
}
```

- [ ] **Step 2: Update shell expectations to the visible catalog**

In `profile-selection-test.sh`, change both expected resolved paths to:

```bash
"${ROOT_DIR}/profiles/uniform-smoke.json"
```

In `profile-contract-test.sh`, set the snapshot source to:

```bash
PROFILE_PATH="$ROOT_DIR/profiles/mixed-outcomes-smoke.json"
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
cd load-test/go-loadtool
go test -count=1 ./internal/config -run TestLoadProfileReadsSiblingCatalogFromModuleRoot
cd ..
bash tests/profile-selection-test.sh
```

Expected: the Go test fails with `profile "explicit-profile" not found`, and the shell test fails because resolution still returns `go-loadtool/profiles/uniform-smoke.json`.

- [ ] **Step 4: Move the JSON files without editing them**

Run:

```bash
mkdir -p load-test/profiles
git mv load-test/go-loadtool/profiles/uniform-smoke.json load-test/profiles/uniform-smoke.json
git mv load-test/go-loadtool/profiles/mixed-outcomes-smoke.json load-test/profiles/mixed-outcomes-smoke.json
```

- [ ] **Step 5: Point both resolvers at the canonical directory**

Change the runner constant to:

```bash
readonly GO_LOADTOOL_PROFILES_DIR="${LOAD_TEST_DIR}/profiles"
```

Change the Go internal catalog root to:

```go
profilesDir = "../profiles"
```

Update the two real-profile directory paths in `config_test.go` from:

```go
filepath.Join("..", "..", "profiles")
```

to:

```go
filepath.Join("..", "..", "..", "profiles")
```

Apply the same extra `".."` component to the two paths that append `"mixed-outcomes-smoke.json"`.

- [ ] **Step 6: Verify profile contents are byte-identical to HEAD**

Run:

```bash
cmp <(git show HEAD:load-test/go-loadtool/profiles/uniform-smoke.json) load-test/profiles/uniform-smoke.json
cmp <(git show HEAD:load-test/go-loadtool/profiles/mixed-outcomes-smoke.json) load-test/profiles/mixed-outcomes-smoke.json
```

Expected: both commands exit `0` with no output.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run:

```bash
cd load-test/go-loadtool
go test -count=1 ./internal/config
cd ..
bash tests/profile-selection-test.sh
bash tests/profile-contract-test.sh
```

Expected: all commands exit `0`.

- [ ] **Step 8: Commit the catalog move**

```bash
git add -A load-test/profiles load-test/go-loadtool/profiles load-test/run-load-test.sh load-test/go-loadtool/internal/config/config.go load-test/go-loadtool/internal/config/config_test.go load-test/tests/profile-selection-test.sh load-test/tests/profile-contract-test.sh
git commit -m "refactor(load-test): expose profile catalog"
```

---

### Task 2: Record and verify the new operational layout

**Files:**
- Modify: `docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md`

**Interfaces:**
- Consumes: the catalog created by Task 1.
- Produces: current-state documentation and verification evidence for both named profiles.

- [ ] **Step 1: Update the active task state**

Add this fact to `## Estado atual`:

```markdown
- os perfis selecionáveis ficam no catálogo canônico `load-test/profiles/`, separado da implementação interna em Go;
```

- [ ] **Step 2: Validate both profiles through the real Go command**

Run from the repository root:

```bash
validation_dir="$(mktemp -d)"
validation_bin="${validation_dir}/go-loadtool"
(
  cd load-test/go-loadtool
  go build -o "$validation_bin" ./cmd/go-loadtool
  "$validation_bin" validate-profile --profile uniform-smoke
  "$validation_bin" validate-profile --profile mixed-outcomes-smoke
)
rm -rf "$validation_dir"
```

Expected: both commands emit normalized JSON and exit `0`; the first reports `"profile": "uniform-smoke"` and the second reports `"profile": "mixed-outcomes-smoke"`.

- [ ] **Step 3: Run the complete automated verification**

Run:

```bash
cd load-test/go-loadtool
go test -count=1 ./...
go test -race -count=1 ./cmd/go-loadtool ./internal/config ./internal/runbundle
go vet ./...
cd ..
for test_script in tests/*.sh; do bash "$test_script"; done
bash -n run-load-test.sh tests/*.sh
cd ..
test -z "$(gofmt -d load-test/go-loadtool/internal/config/config.go load-test/go-loadtool/internal/config/config_test.go)"
git diff --check
```

Expected: every command exits `0`; no service-backed smoke is run.

- [ ] **Step 4: Confirm there is one catalog and no stale reference**

Run:

```bash
test ! -e load-test/go-loadtool/profiles
find load-test/profiles -maxdepth 1 -type f -name '*.json' -printf '%f\n' | sort
! rg -n 'go-loadtool/profiles' load-test docs --glob '!load-test/results/**' --glob '!docs/superpowers/**'
```

Expected: `find` prints exactly `mixed-outcomes-smoke.json` and `uniform-smoke.json`; `test` succeeds; `rg` returns no matches.

- [ ] **Step 5: Commit the documentation update**

```bash
git add docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md
git commit -m "docs: record visible load-test profiles"
```
