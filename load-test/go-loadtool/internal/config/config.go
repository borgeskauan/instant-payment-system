package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

const (
	SchemaVersion        = 1
	DefaultProfile       = "uniform-smoke"
	ScenarioHappyPath    = "happy-path"
	ExpectedHTTP2xx      = "2xx"
	ConfirmationRequired = "required"
	profilesDir          = "profiles"
	DefaultOutputDir     = "results/go-loadtool/manual"
	maxPairSuffix        = 999999
)

var profileNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

type Runtime struct {
	SchemaVersion int
	Connections   Connections
	Load          Load
	Seed          int64
	Scenarios     []Scenario
	Reporting     Reporting
}

type Connections struct {
	CentralTransfer     CentralTransferConnection
	NotificationGateway NotificationGatewayConnection
}

type CentralTransferConnection struct {
	BaseURL        string
	CACert         string
	ClientCertRoot string
	ServerName     string
}

type NotificationGatewayConnection struct {
	Address        string
	CACert         string
	ClientCertRoot string
	ServerName     string
}

type Load struct {
	TargetTxRate int
	Warmup       time.Duration
	Duration     time.Duration
	Drain        time.Duration
}

type HotColdPairDistribution struct {
	FirstPair       int
	HotPairCount    int
	ColdPairCount   int
	HotTrafficShare float64
}

type Scenario struct {
	Type      string
	Share     float64
	HappyPath *HappyPathScenario
}

type HappyPathScenario struct {
	Participants HotColdPairDistribution
	Funding      Funding
	Amount       SequentialRangeAmount
	Expectations HappyPathExpectations
}

type SequentialRangeAmount struct {
	Minimum int64
	Maximum int64
}

type HappyPathExpectations struct {
	HTTPStatus        string
	PayerConfirmation string
}

type Funding struct {
	Balance       int64
	ResetIfExists bool
}

type Reporting struct {
	SLAThresholdMs int64
}

type fileConfig struct {
	SchemaVersion int               `json:"schemaVersion"`
	Connections   fileConnections   `json:"connections"`
	Load          fileLoad          `json:"load"`
	Seed          *int64            `json:"seed"`
	Scenarios     []json.RawMessage `json:"scenarios"`
	Reporting     fileReporting     `json:"reporting"`
}

type fileConnections struct {
	CentralTransfer     fileCentralTransferConnection     `json:"centralTransfer"`
	NotificationGateway fileNotificationGatewayConnection `json:"notificationGateway"`
}

type fileCentralTransferConnection struct {
	BaseURL        string `json:"baseUrl"`
	CACert         string `json:"caCert"`
	ClientCertRoot string `json:"clientCertRoot"`
	ServerName     string `json:"serverName"`
}

type fileNotificationGatewayConnection struct {
	Address        string `json:"address"`
	CACert         string `json:"caCert"`
	ClientCertRoot string `json:"clientCertRoot"`
	ServerName     string `json:"serverName"`
}

type fileLoad struct {
	TargetTxRate int    `json:"targetTxRate"`
	Warmup       string `json:"warmup"`
	Duration     string `json:"duration"`
	Drain        string `json:"drain"`
}

type fileHotColdPairDistribution struct {
	FirstPair       int     `json:"firstPair"`
	HotPairCount    int     `json:"hotPairCount"`
	ColdPairCount   int     `json:"coldPairCount"`
	HotTrafficShare float64 `json:"hotTrafficShare"`
}

type scenarioEnvelope struct {
	Type string `json:"type"`
}

type fileHappyPathScenario struct {
	Type         string                      `json:"type"`
	Share        float64                     `json:"share"`
	Participants fileHotColdPairDistribution `json:"participants"`
	Funding      fileFunding                 `json:"funding"`
	Amount       fileSequentialRangeAmount   `json:"amount"`
	Expectations fileHappyPathExpectations   `json:"expectations"`
}

type fileSequentialRangeAmount struct {
	Minimum int64 `json:"minimum"`
	Maximum int64 `json:"maximum"`
}

type fileHappyPathExpectations struct {
	HTTPStatus        string `json:"httpStatus"`
	PayerConfirmation string `json:"payerConfirmation"`
}

type fileFunding struct {
	Balance       int64 `json:"balance"`
	ResetIfExists *bool `json:"resetIfExists"`
}

type fileReporting struct {
	SLAThresholdMs int64 `json:"slaThresholdMs"`
}

func LoadDefault() (Runtime, error) {
	return LoadProfile(DefaultProfile)
}

func LoadProfile(name string) (Runtime, error) {
	return loadProfileFromDir(profilesDir, name)
}

func loadProfileFromDir(dir string, name string) (Runtime, error) {
	if !profileNamePattern.MatchString(name) {
		return Runtime{}, fmt.Errorf("invalid profile name %q: use only lowercase letters, digits, and hyphens, beginning with a letter or digit", name)
	}

	path := filepath.Join(dir, name+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Runtime{}, fmt.Errorf("profile %q not found", name)
		}
		return Runtime{}, fmt.Errorf("read profile %q: %w", name, err)
	}

	var file fileConfig
	if err := decodeStrict(data, &file); err != nil {
		return Runtime{}, fmt.Errorf("profile %q is malformed: invalid JSON contract: %w", name, err)
	}
	return buildRuntime(name, file)
}

func buildRuntime(name string, file fileConfig) (Runtime, error) {
	if file.SchemaVersion != SchemaVersion {
		return Runtime{}, malformedProfile(name, "schemaVersion", fmt.Errorf("must be %d", SchemaVersion))
	}
	for _, required := range []struct {
		field string
		value string
	}{
		{field: "connections.centralTransfer.baseUrl", value: file.Connections.CentralTransfer.BaseURL},
		{field: "connections.centralTransfer.caCert", value: file.Connections.CentralTransfer.CACert},
		{field: "connections.centralTransfer.clientCertRoot", value: file.Connections.CentralTransfer.ClientCertRoot},
		{field: "connections.notificationGateway.address", value: file.Connections.NotificationGateway.Address},
		{field: "connections.notificationGateway.caCert", value: file.Connections.NotificationGateway.CACert},
		{field: "connections.notificationGateway.clientCertRoot", value: file.Connections.NotificationGateway.ClientCertRoot},
	} {
		if required.value == "" {
			return Runtime{}, malformedProfile(name, required.field, errors.New("must be a non-empty string"))
		}
	}

	warmup, err := parseWholeSecondDuration(name, "load.warmup", file.Load.Warmup, true)
	if err != nil {
		return Runtime{}, err
	}
	duration, err := parseWholeSecondDuration(name, "load.duration", file.Load.Duration, false)
	if err != nil {
		return Runtime{}, err
	}
	drain, err := parseWholeSecondDuration(name, "load.drain", file.Load.Drain, true)
	if err != nil {
		return Runtime{}, err
	}
	if file.Load.TargetTxRate <= 0 {
		return Runtime{}, malformedProfile(name, "load.targetTxRate", errors.New("must be positive"))
	}

	if file.Seed == nil {
		return Runtime{}, malformedProfile(name, "seed", errors.New("is required"))
	}
	if *file.Seed < 0 {
		return Runtime{}, malformedProfile(name, "seed", errors.New("must not be negative"))
	}
	if len(file.Scenarios) != 1 {
		return Runtime{}, malformedProfile(name, "scenarios", errors.New("must contain exactly one happy-path scenario"))
	}
	scenario, err := decodeScenario(name, file.Scenarios[0])
	if err != nil {
		return Runtime{}, err
	}
	if file.Reporting.SLAThresholdMs <= 0 {
		return Runtime{}, malformedProfile(name, "reporting.slaThresholdMs", errors.New("must be positive"))
	}

	return Runtime{
		SchemaVersion: file.SchemaVersion,
		Connections: Connections{
			CentralTransfer: CentralTransferConnection{
				BaseURL:        file.Connections.CentralTransfer.BaseURL,
				CACert:         file.Connections.CentralTransfer.CACert,
				ClientCertRoot: file.Connections.CentralTransfer.ClientCertRoot,
				ServerName:     serverName(file.Connections.CentralTransfer.ServerName),
			},
			NotificationGateway: NotificationGatewayConnection{
				Address:        file.Connections.NotificationGateway.Address,
				CACert:         file.Connections.NotificationGateway.CACert,
				ClientCertRoot: file.Connections.NotificationGateway.ClientCertRoot,
				ServerName:     serverName(file.Connections.NotificationGateway.ServerName),
			},
		},
		Load: Load{
			TargetTxRate: file.Load.TargetTxRate,
			Warmup:       warmup,
			Duration:     duration,
			Drain:        drain,
		},
		Seed:      *file.Seed,
		Scenarios: []Scenario{scenario},
		Reporting: Reporting{SLAThresholdMs: file.Reporting.SLAThresholdMs},
	}, nil
}

func decodeScenario(profileName string, data []byte) (Scenario, error) {
	var envelope scenarioEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return Scenario{}, malformedProfile(profileName, "scenarios", fmt.Errorf("invalid scenario: %w", err))
	}
	if envelope.Type != ScenarioHappyPath {
		return Scenario{}, malformedProfile(profileName, "scenarios.type", fmt.Errorf("unsupported scenario type %q", envelope.Type))
	}

	var file fileHappyPathScenario
	if err := decodeStrict(data, &file); err != nil {
		return Scenario{}, malformedProfile(profileName, "scenarios", fmt.Errorf("invalid happy-path scenario: %w", err))
	}
	if file.Share != 1 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].share", errors.New("must be 1.0 while happy-path is the only supported scenario"))
	}
	if file.Participants.FirstPair <= 0 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].participants.firstPair", errors.New("must be positive"))
	}
	if file.Participants.HotPairCount <= 0 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].participants.hotPairCount", errors.New("must be positive"))
	}
	if file.Participants.ColdPairCount <= 0 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].participants.coldPairCount", errors.New("must be positive"))
	}
	lastPair := int64(file.Participants.FirstPair) + int64(file.Participants.HotPairCount) + int64(file.Participants.ColdPairCount) - 1
	if lastPair > maxPairSuffix {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].participants", fmt.Errorf("pair range ends at %d, maximum is %d", lastPair, maxPairSuffix))
	}
	if file.Participants.HotTrafficShare <= 0 || file.Participants.HotTrafficShare >= 1 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].participants.hotTrafficShare", errors.New("must be greater than 0 and less than 1"))
	}
	if file.Funding.Balance <= 0 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].funding.balance", errors.New("must be positive"))
	}
	if file.Funding.ResetIfExists == nil {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].funding.resetIfExists", errors.New("is required"))
	}
	if file.Amount.Minimum <= 0 {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].amount.minimum", errors.New("must be positive"))
	}
	if file.Amount.Maximum < file.Amount.Minimum {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].amount.maximum", errors.New("must be greater than or equal to minimum"))
	}
	if file.Expectations.HTTPStatus != ExpectedHTTP2xx {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].expectations.httpStatus", fmt.Errorf("must be %q", ExpectedHTTP2xx))
	}
	if file.Expectations.PayerConfirmation != ConfirmationRequired {
		return Scenario{}, malformedProfile(profileName, "scenarios[0].expectations.payerConfirmation", fmt.Errorf("must be %q", ConfirmationRequired))
	}

	return Scenario{
		Type:  file.Type,
		Share: file.Share,
		HappyPath: &HappyPathScenario{
			Participants: HotColdPairDistribution{
				FirstPair:       file.Participants.FirstPair,
				HotPairCount:    file.Participants.HotPairCount,
				ColdPairCount:   file.Participants.ColdPairCount,
				HotTrafficShare: file.Participants.HotTrafficShare,
			},
			Funding: Funding{
				Balance:       file.Funding.Balance,
				ResetIfExists: *file.Funding.ResetIfExists,
			},
			Amount: SequentialRangeAmount{
				Minimum: file.Amount.Minimum,
				Maximum: file.Amount.Maximum,
			},
			Expectations: HappyPathExpectations{
				HTTPStatus:        file.Expectations.HTTPStatus,
				PayerConfirmation: file.Expectations.PayerConfirmation,
			},
		},
	}, nil
}

func parseWholeSecondDuration(profileName string, field string, value string, allowZero bool) (time.Duration, error) {
	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, malformedProfile(profileName, field, err)
	}
	if (allowZero && duration < 0) || (!allowZero && duration <= 0) {
		qualifier := "must not be negative"
		if !allowZero {
			qualifier = "must be positive"
		}
		return 0, malformedProfile(profileName, field, errors.New(qualifier))
	}
	if duration%time.Second != 0 {
		return 0, malformedProfile(profileName, field, errors.New("must resolve to a whole number of seconds"))
	}
	return duration, nil
}

func decodeStrict(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if err == nil {
			return errors.New("multiple JSON values are not allowed")
		}
		return err
	}
	return nil
}

func malformedProfile(name string, field string, err error) error {
	return fmt.Errorf("profile %q is malformed: invalid %s: %w", name, field, err)
}

func serverName(value string) string {
	if value == "" {
		return "localhost"
	}
	return value
}
