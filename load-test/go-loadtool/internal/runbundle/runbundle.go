package runbundle

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

type Layout struct {
	Root          string
	Profile       string
	ExecutionPlan string
	RunWindow     string
	Report        string
	ToolOutputDir string
	Starts        string
	Events        string
	StatusStarts  string
	Replays       string
}

func Resolve(root string) (Layout, error) {
	if root == "" {
		return Layout{}, fmt.Errorf("run directory is required")
	}
	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		return Layout{}, fmt.Errorf("resolve run directory: %w", err)
	}
	toolOutputDir := filepath.Join(absoluteRoot, "go-loadtool")
	return Layout{
		Root:          absoluteRoot,
		Profile:       filepath.Join(absoluteRoot, "profile.json"),
		ExecutionPlan: filepath.Join(absoluteRoot, "execution-plan.json"),
		RunWindow:     filepath.Join(absoluteRoot, "run-window.json"),
		Report:        filepath.Join(absoluteRoot, "sla-report.json"),
		ToolOutputDir: toolOutputDir,
		Starts:        filepath.Join(toolOutputDir, "starts.csv"),
		Events:        filepath.Join(toolOutputDir, "events.csv"),
		StatusStarts:  filepath.Join(toolOutputDir, "status-starts.csv"),
		Replays:       filepath.Join(toolOutputDir, "replays.csv"),
	}, nil
}

func (layout Layout) ValidatePrepared() error {
	rootInfo, err := os.Stat(layout.Root)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("run directory %q does not exist", layout.Root)
		}
		return fmt.Errorf("inspect run directory %q: %w", layout.Root, err)
	}
	if !rootInfo.IsDir() {
		return fmt.Errorf("run directory %q is not a directory", layout.Root)
	}

	if err := requireRegularFile(layout.Profile, "profile.json"); err != nil {
		return err
	}

	for _, output := range []struct {
		name string
		path string
	}{
		{name: "run-window.json", path: layout.RunWindow},
		{name: "sla-report.json", path: layout.Report},
		{name: "go-loadtool", path: layout.ToolOutputDir},
	} {
		if err := requireAbsent(output.path, output.name); err != nil {
			return err
		}
	}
	return nil
}

func (layout Layout) PrepareOutputs() error {
	if err := layout.ValidatePrepared(); err != nil {
		return err
	}
	if err := os.Mkdir(layout.ToolOutputDir, 0o755); err != nil {
		return fmt.Errorf("create generated output directory go-loadtool at %q: %w", layout.ToolOutputDir, err)
	}
	return nil
}

func (layout Layout) WriteReportAtomically(content []byte) error {
	temporary, err := os.CreateTemp(layout.Root, ".sla-report.json.tmp-*")
	if err != nil {
		return fmt.Errorf("create temporary sla-report.json in %q: %w", layout.Root, err)
	}
	temporaryPath := temporary.Name()
	closed := false
	defer func() {
		if !closed {
			_ = temporary.Close()
		}
		_ = os.Remove(temporaryPath)
	}()

	if err := temporary.Chmod(0o644); err != nil {
		return fmt.Errorf("set temporary sla-report.json permissions: %w", err)
	}
	written, err := temporary.Write(content)
	if err != nil {
		return fmt.Errorf("write temporary sla-report.json: %w", err)
	}
	if written != len(content) {
		return fmt.Errorf("write temporary sla-report.json: wrote %d of %d bytes", written, len(content))
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync temporary sla-report.json: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close temporary sla-report.json: %w", err)
	}
	closed = true

	if err := os.Link(temporaryPath, layout.Report); err != nil {
		return fmt.Errorf("publish sla-report.json at %q without overwrite: %w", layout.Report, err)
	}
	if err := os.Remove(temporaryPath); err != nil {
		return fmt.Errorf("remove temporary sla-report.json after publish: %w", err)
	}
	return nil
}

func requireRegularFile(path, name string) error {
	info, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("required %s is missing at %q", name, path)
		}
		return fmt.Errorf("inspect required %s at %q: %w", name, path, err)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("required %s is not a regular file at %q", name, path)
	}
	return nil
}

func requireAbsent(path, name string) error {
	_, err := os.Lstat(path)
	if err == nil {
		return fmt.Errorf("generated output %s already exists at %q", name, path)
	}
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return fmt.Errorf("inspect generated output %s at %q: %w", name, path, err)
}
