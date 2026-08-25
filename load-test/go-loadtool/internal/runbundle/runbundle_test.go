package runbundle

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveReturnsFixedAbsoluteLayout(t *testing.T) {
	root := filepath.Join(t.TempDir(), "run")

	layout, err := Resolve(root)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}

	want := Layout{
		Root:           root,
		Profile:        filepath.Join(root, "inputs", "profile.json"),
		ExecutionPlan:  filepath.Join(root, "inputs", "execution-plan.json"),
		EventsDir:      filepath.Join(root, "events"),
		DiagnosticsDir: filepath.Join(root, "diagnostics"),
		Pacs008Starts:  filepath.Join(root, "events", "pacs008-starts.csv"),
		Pacs002Starts:  filepath.Join(root, "events", "pacs002-starts.csv"),
		Notifications:  filepath.Join(root, "events", "notifications.csv"),
		Replays:        filepath.Join(root, "events", "replays.csv"),
		RunWindow:      filepath.Join(root, "run-window.json"),
		Report:         filepath.Join(root, "sla-report.json"),
	}
	if layout != want {
		t.Fatalf("Resolve() = %#v, want %#v", layout, want)
	}
}

func TestResolveMakesRelativeRootAbsolute(t *testing.T) {
	t.Chdir(t.TempDir())

	layout, err := Resolve(filepath.Join("results", "run"))
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}

	wantRoot, err := filepath.Abs(filepath.Join("results", "run"))
	if err != nil {
		t.Fatalf("filepath.Abs() error = %v", err)
	}
	if layout.Root != wantRoot {
		t.Fatalf("Root = %q, want %q", layout.Root, wantRoot)
	}
}

func TestResolveRejectsEmptyRoot(t *testing.T) {
	if _, err := Resolve(""); err == nil {
		t.Fatal("Resolve() error = nil, want empty-root error")
	}
}

func TestValidatePreparedAcceptsRequiredInputsAndAdditionalPreparation(t *testing.T) {
	layout := newPreparedLayout(t)
	if err := os.Mkdir(filepath.Join(layout.Root, "certs"), 0o755); err != nil {
		t.Fatalf("Mkdir(certs) error = %v", err)
	}

	if err := layout.ValidatePrepared(); err != nil {
		t.Fatalf("ValidatePrepared() error = %v", err)
	}
}

func TestValidatePreparedRejectsMissingExecutionPlan(t *testing.T) {
	layout := newPreparedLayout(t)
	if err := os.Remove(layout.ExecutionPlan); err != nil {
		t.Fatalf("Remove(ExecutionPlan) error = %v", err)
	}

	assertErrorContains(t, layout.ValidatePrepared(), "execution-plan.json")
}

func TestValidatePreparedRejectsMissingOrInvalidRoot(t *testing.T) {
	t.Run("missing", func(t *testing.T) {
		layout, err := Resolve(filepath.Join(t.TempDir(), "missing"))
		if err != nil {
			t.Fatalf("Resolve() error = %v", err)
		}
		assertErrorContains(t, layout.ValidatePrepared(), "run directory")
	})

	t.Run("not a directory", func(t *testing.T) {
		root := filepath.Join(t.TempDir(), "run")
		writeFile(t, root, []byte("not a directory"))
		layout, err := Resolve(root)
		if err != nil {
			t.Fatalf("Resolve() error = %v", err)
		}
		assertErrorContains(t, layout.ValidatePrepared(), "not a directory")
	})
}

func TestValidatePreparedRejectsMissingOrNonRegularProfile(t *testing.T) {
	t.Run("missing", func(t *testing.T) {
		layout := newPreparedLayout(t)
		if err := os.Remove(layout.Profile); err != nil {
			t.Fatalf("Remove() error = %v", err)
		}
		assertErrorContains(t, layout.ValidatePrepared(), "profile.json")
	})

	t.Run("not regular", func(t *testing.T) {
		layout := newPreparedLayout(t)
		if err := os.Remove(layout.Profile); err != nil {
			t.Fatalf("Remove() error = %v", err)
		}
		if err := os.Mkdir(layout.Profile, 0o755); err != nil {
			t.Fatalf("Mkdir() error = %v", err)
		}
		assertErrorContains(t, layout.ValidatePrepared(), "profile.json")
	})
}

func TestValidatePreparedRejectsEachGeneratedOutput(t *testing.T) {
	tests := []struct {
		name string
		path func(Layout) string
	}{
		{name: "run-window.json", path: func(layout Layout) string { return layout.RunWindow }},
		{name: "sla-report.json", path: func(layout Layout) string { return layout.Report }},
		{name: "events", path: func(layout Layout) string { return layout.EventsDir }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			layout := newPreparedLayout(t)
			writeFile(t, test.path(layout), []byte("already exists"))
			assertErrorContains(t, layout.ValidatePrepared(), test.name)
		})
	}
}

func TestPrepareOutputsCreatesOnlyEventsDirectory(t *testing.T) {
	layout := newPreparedLayout(t)

	if err := layout.PrepareOutputs(); err != nil {
		t.Fatalf("PrepareOutputs() error = %v", err)
	}

	info, err := os.Stat(layout.EventsDir)
	if err != nil {
		t.Fatalf("Stat(EventsDir) error = %v", err)
	}
	if !info.IsDir() {
		t.Fatalf("EventsDir mode = %v, want directory", info.Mode())
	}
	for _, path := range []string{layout.RunWindow, layout.Report, layout.Pacs008Starts, layout.Notifications, layout.Pacs002Starts, layout.Replays, filepath.Join(layout.Root, "diagnostics")} {
		if _, err := os.Lstat(path); !os.IsNotExist(err) {
			t.Fatalf("generated path %q exists or cannot be inspected: %v", path, err)
		}
	}
}

func TestPrepareOutputsRejectsSecondPreparation(t *testing.T) {
	layout := newPreparedLayout(t)
	if err := layout.PrepareOutputs(); err != nil {
		t.Fatalf("first PrepareOutputs() error = %v", err)
	}

	assertErrorContains(t, layout.PrepareOutputs(), "events")
}

func TestPrepareOutputsDoesNotCreateDirectoryWhenValidationFails(t *testing.T) {
	layout := newPreparedLayout(t)
	writeFile(t, layout.RunWindow, []byte("already executed"))

	assertErrorContains(t, layout.PrepareOutputs(), "run-window.json")
	if _, err := os.Lstat(layout.EventsDir); !os.IsNotExist(err) {
		t.Fatalf("EventsDir exists or cannot be inspected after failure: %v", err)
	}
}

func TestWriteReportAtomicallyPublishesExactContent(t *testing.T) {
	layout := newPreparedLayout(t)
	if err := layout.PrepareOutputs(); err != nil {
		t.Fatalf("PrepareOutputs() error = %v", err)
	}
	want := []byte("{\n  \"valid\": true\n}\n")

	if err := layout.WriteReportAtomically(want); err != nil {
		t.Fatalf("WriteReportAtomically() error = %v", err)
	}

	got, err := os.ReadFile(layout.Report)
	if err != nil {
		t.Fatalf("ReadFile(Report) error = %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("Report content = %q, want %q", got, want)
	}
	assertNoReportTemporaryFiles(t, layout)
}

func TestWriteReportAtomicallyDoesNotOverwriteExistingReport(t *testing.T) {
	layout := newPreparedLayout(t)
	want := []byte("existing report\n")
	writeFile(t, layout.Report, want)

	assertErrorContains(t, layout.WriteReportAtomically([]byte("replacement\n")), "sla-report.json")
	got, err := os.ReadFile(layout.Report)
	if err != nil {
		t.Fatalf("ReadFile(Report) error = %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("Report content = %q, want unchanged %q", got, want)
	}
	assertNoReportTemporaryFiles(t, layout)
}

func assertNoReportTemporaryFiles(t *testing.T, layout Layout) {
	t.Helper()
	matches, err := filepath.Glob(filepath.Join(layout.Root, ".sla-report.json.tmp-*"))
	if err != nil {
		t.Fatalf("Glob(report temporary files) error = %v", err)
	}
	if len(matches) != 0 {
		t.Fatalf("report temporary files remain: %v", matches)
	}
}

func newPreparedLayout(t *testing.T) Layout {
	t.Helper()
	layout, err := Resolve(t.TempDir())
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if err := os.Mkdir(filepath.Dir(layout.Profile), 0o755); err != nil {
		t.Fatalf("Mkdir(inputs) error = %v", err)
	}
	writeFile(t, layout.Profile, []byte("{}\n"))
	writeFile(t, layout.ExecutionPlan, []byte("{}\n"))
	return layout
}

func writeFile(t *testing.T, path string, content []byte) {
	t.Helper()
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatalf("WriteFile(%q) error = %v", path, err)
	}
}

func assertErrorContains(t *testing.T, err error, want string) {
	t.Helper()
	if err == nil {
		t.Fatalf("error = nil, want error containing %q", want)
	}
	if !strings.Contains(err.Error(), want) {
		t.Fatalf("error = %q, want it to contain %q", err, want)
	}
}
