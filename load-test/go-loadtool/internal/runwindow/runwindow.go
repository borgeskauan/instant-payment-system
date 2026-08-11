package runwindow

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

const SchemaVersion = 2

type Document struct {
	SchemaVersion int               `json:"schema_version"`
	Tag           string            `json:"tag,omitempty"`
	ResultDir     string            `json:"result_dir,omitempty"`
	Profile       Profile           `json:"profile"`
	Artifacts     map[string]string `json:"artifacts,omitempty"`
	Window        Window            `json:"window"`
	Grafana       *Grafana          `json:"grafana,omitempty"`
}

type Profile struct {
	Name          string `json:"name"`
	Snapshot      string `json:"snapshot,omitempty"`
	ExecutionPlan string `json:"execution_plan,omitempty"`
}

type Window struct {
	GenerationStartedAt time.Time  `json:"generation_started_at"`
	ActiveStartedAt     time.Time  `json:"active_started_at"`
	GenerationEndedAt   time.Time  `json:"generation_ended_at"`
	ReplayDeadlineAt    time.Time  `json:"replay_deadline_at"`
	RunStartedAt        *time.Time `json:"run_started_at,omitempty"`
	DrainFinishedAt     *time.Time `json:"drain_finished_at,omitempty"`
	LegacyActiveEndedAt *time.Time `json:"active_finished_at,omitempty"`
}

type Grafana struct {
	AvailableAtRunStart bool   `json:"available_at_run_start"`
	BaseURL             string `json:"base_url"`
	FullRunURL          string `json:"full_run_url,omitempty"`
	ActiveWindowURL     string `json:"active_window_url,omitempty"`
}

func New(profileName string, started time.Time, warmup, duration, drain time.Duration, replay config.Replay) Document {
	activeStarted := started.Add(warmup)
	generationEnded := activeStarted.Add(duration)
	return Document{
		SchemaVersion: SchemaVersion,
		Profile:       Profile{Name: profileName},
		Window: Window{
			GenerationStartedAt: started,
			ActiveStartedAt:     activeStarted,
			GenerationEndedAt:   generationEnded,
			ReplayDeadlineAt:    generationEnded.Add(maxReplayDelay(replay)).Add(drain),
		},
	}
}

func Write(path string, document Document) error {
	file, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create run window: %w", err)
	}
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(document); err != nil {
		_ = file.Close()
		return fmt.Errorf("write run window: %w", err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close run window: %w", err)
	}
	return nil
}

func Read(path string) (Document, error) {
	file, err := os.Open(path)
	if err != nil {
		return Document{}, fmt.Errorf("open run window: %w", err)
	}
	defer file.Close()
	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	var document Document
	if err := decoder.Decode(&document); err != nil {
		return Document{}, fmt.Errorf("decode run window: %w", err)
	}
	return document, nil
}

func Validate(document Document, profileName string, warmup, duration, drain time.Duration, replay config.Replay) error {
	if document.SchemaVersion != SchemaVersion {
		return fmt.Errorf("run window schema_version must be %d", SchemaVersion)
	}
	if document.Profile.Name != profileName {
		return fmt.Errorf("run window profile is %q, want %q", document.Profile.Name, profileName)
	}
	w := document.Window
	if w.GenerationStartedAt.IsZero() || !w.ActiveStartedAt.Equal(w.GenerationStartedAt.Add(warmup)) {
		return fmt.Errorf("run window active_started_at is inconsistent with warmup")
	}
	if !w.GenerationEndedAt.Equal(w.ActiveStartedAt.Add(duration)) {
		return fmt.Errorf("run window generation_ended_at is inconsistent with duration")
	}
	if !w.ReplayDeadlineAt.Equal(w.GenerationEndedAt.Add(maxReplayDelay(replay)).Add(drain)) {
		return fmt.Errorf("run window replay_deadline_at is inconsistent with replay delay and drain")
	}
	return nil
}

func Resolve(document Document, profileName string, warmup, duration, drain time.Duration, replay config.Replay) (Window, error) {
	if document.SchemaVersion == SchemaVersion {
		if err := Validate(document, profileName, warmup, duration, drain, replay); err != nil {
			return Window{}, err
		}
		return document.Window, nil
	}
	if document.SchemaVersion != 0 {
		return Window{}, fmt.Errorf("unsupported run window schema_version %d", document.SchemaVersion)
	}
	if document.Profile.Name != profileName {
		return Window{}, fmt.Errorf("run window profile is %q, want %q", document.Profile.Name, profileName)
	}
	w := document.Window
	if w.ActiveStartedAt.IsZero() || w.LegacyActiveEndedAt == nil || w.DrainFinishedAt == nil {
		return Window{}, fmt.Errorf("legacy run window is missing stored active or drain timestamps")
	}
	if !w.LegacyActiveEndedAt.Equal(w.ActiveStartedAt.Add(duration)) || w.DrainFinishedAt.Before(*w.LegacyActiveEndedAt) {
		return Window{}, fmt.Errorf("legacy run window is inconsistent with the selected profile")
	}
	return Window{
		GenerationStartedAt: w.ActiveStartedAt.Add(-warmup),
		ActiveStartedAt:     w.ActiveStartedAt,
		GenerationEndedAt:   *w.LegacyActiveEndedAt,
		ReplayDeadlineAt:    *w.DrainFinishedAt,
		RunStartedAt:        w.RunStartedAt,
		DrainFinishedAt:     w.DrainFinishedAt,
	}, nil
}

func maxReplayDelay(replay config.Replay) time.Duration {
	var delay time.Duration
	if replay.Pacs008 != nil && replay.Pacs008.Delay > delay {
		delay = replay.Pacs008.Delay
	}
	if replay.Pacs002 != nil && replay.Pacs002.Delay > delay {
		delay = replay.Pacs002.Delay
	}
	return delay
}
