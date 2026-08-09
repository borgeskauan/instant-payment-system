package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

const (
	SchemaVersion              = 1
	DefaultProfile             = "uniform-smoke"
	ScenarioHappyPath          = "happy-path"
	ScenarioInsufficientFunds  = "insufficient-funds"
	ExpectedHTTP2xx            = "2xx"
	ConfirmationRequired       = "required"
	ConfirmationForbidden      = "forbidden"
	ScenarioSelectionBlockSize = 100
	profilesDir                = "profiles"
	DefaultOutputDir           = "results/go-loadtool/manual"
	maxPairSuffix              = 999999
)

var profileNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

type Runtime struct {
	SchemaVersion int
	Connections   Connections
	Load          Load
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
	PairNumberStart int
	HotPairCount    int
	ColdPairCount   int
	HotTrafficShare float64
}

type Scenario struct {
	Type              string
	Share             float64
	HappyPath         *HappyPathScenario
	InsufficientFunds *InsufficientFundsScenario
}

type HappyPathScenario struct {
	Participants HotColdPairDistribution
	Amount       SequentialRangeAmount
	Expectations HappyPathExpectations
}

type InsufficientFundsScenario struct {
	Participants HotColdPairDistribution
	Amount       SequentialRangeAmount
	Expectations InsufficientFundsExpectations
}

type SequentialRangeAmount struct {
	Minimum int64
	Maximum int64
}

type HappyPathExpectations struct {
	HTTPStatus        string
	PayerConfirmation string
}

type InsufficientFundsExpectations struct {
	HTTPStatus        string
	PayerConfirmation string
}

type Reporting struct {
	SLAThresholdMs int64
}

type fileConfig struct {
	SchemaVersion int               `json:"schemaVersion"`
	Connections   fileConnections   `json:"connections"`
	Load          fileLoad          `json:"load"`
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
	Amount       fileSequentialRangeAmount   `json:"amount"`
	Expectations fileHappyPathExpectations   `json:"expectations"`
}

type fileInsufficientFundsScenario struct {
	Type         string                            `json:"type"`
	Share        float64                           `json:"share"`
	Participants fileHotColdPairDistribution       `json:"participants"`
	Amount       fileSequentialRangeAmount         `json:"amount"`
	Expectations fileInsufficientFundsExpectations `json:"expectations"`
}

type fileSequentialRangeAmount struct {
	Minimum int64 `json:"minimum"`
	Maximum int64 `json:"maximum"`
}

type fileHappyPathExpectations struct {
	HTTPStatus        string `json:"httpStatus"`
	PayerConfirmation string `json:"payerConfirmation"`
}

type fileInsufficientFundsExpectations struct {
	HTTPStatus        string `json:"httpStatus"`
	PayerConfirmation string `json:"payerConfirmation"`
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

	if len(file.Scenarios) == 0 {
		return Runtime{}, malformedProfile(name, "scenarios", errors.New("must contain at least one scenario"))
	}
	if len(file.Scenarios) > 2 {
		return Runtime{}, malformedProfile(name, "scenarios", errors.New("supports at most happy-path and insufficient-funds"))
	}
	scenarios := make([]Scenario, 0, len(file.Scenarios))
	seenTypes := make(map[string]struct{}, len(file.Scenarios))
	totalQuota := 0
	nextPairNumber := 1
	for index, rawScenario := range file.Scenarios {
		scenario, err := decodeScenario(name, index, rawScenario)
		if err != nil {
			return Runtime{}, err
		}
		if _, exists := seenTypes[scenario.Type]; exists {
			return Runtime{}, malformedProfile(name, fmt.Sprintf("scenarios[%d].type", index), fmt.Errorf("duplicate scenario type %q", scenario.Type))
		}
		seenTypes[scenario.Type] = struct{}{}
		quota := scenario.Share * ScenarioSelectionBlockSize
		roundedQuota := math.Round(quota)
		if scenario.Share <= 0 || math.Abs(quota-roundedQuota) > 1e-9 {
			return Runtime{}, malformedProfile(name, fmt.Sprintf("scenarios[%d].share", index), fmt.Errorf("must be positive and select a whole number of entries in a %d-entry block", ScenarioSelectionBlockSize))
		}
		totalQuota += int(roundedQuota)
		participants, _ := scenario.Participants()
		remainingPairs := maxPairSuffix - nextPairNumber + 1
		if participants.HotPairCount > remainingPairs || participants.ColdPairCount > remainingPairs-participants.HotPairCount {
			return Runtime{}, malformedProfile(name, "scenarios.participants", fmt.Errorf("allocated pair range exceeds the maximum pair number %d", maxPairSuffix))
		}
		pairCount := participants.HotPairCount + participants.ColdPairCount
		scenario.setPairNumberStart(nextPairNumber)
		nextPairNumber += pairCount
		scenarios = append(scenarios, scenario)
	}
	if totalQuota != ScenarioSelectionBlockSize {
		return Runtime{}, malformedProfile(name, "scenarios.share", errors.New("shares must sum to 1.0"))
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
		Scenarios: scenarios,
		Reporting: Reporting{SLAThresholdMs: file.Reporting.SLAThresholdMs},
	}, nil
}

func decodeScenario(profileName string, index int, data []byte) (Scenario, error) {
	var envelope scenarioEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d]", index), fmt.Errorf("invalid scenario: %w", err))
	}
	switch envelope.Type {
	case ScenarioHappyPath:
		return decodeHappyPathScenario(profileName, index, data)
	case ScenarioInsufficientFunds:
		return decodeInsufficientFundsScenario(profileName, index, data)
	default:
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].type", index), fmt.Errorf("unsupported scenario type %q", envelope.Type))
	}
}

func decodeHappyPathScenario(profileName string, index int, data []byte) (Scenario, error) {
	var file fileHappyPathScenario
	if err := decodeStrict(data, &file); err != nil {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d]", index), fmt.Errorf("invalid happy-path scenario: %w", err))
	}
	if err := validateParticipants(profileName, index, file.Participants); err != nil {
		return Scenario{}, err
	}
	if err := validateAmount(profileName, index, file.Amount); err != nil {
		return Scenario{}, err
	}
	if file.Expectations.HTTPStatus != ExpectedHTTP2xx {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].expectations.httpStatus", index), fmt.Errorf("must be %q", ExpectedHTTP2xx))
	}
	if file.Expectations.PayerConfirmation != ConfirmationRequired {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].expectations.payerConfirmation", index), fmt.Errorf("must be %q", ConfirmationRequired))
	}
	return Scenario{
		Type:  file.Type,
		Share: file.Share,
		HappyPath: &HappyPathScenario{
			Participants: runtimeParticipants(file.Participants),
			Amount:       runtimeAmount(file.Amount),
			Expectations: HappyPathExpectations{
				HTTPStatus:        file.Expectations.HTTPStatus,
				PayerConfirmation: file.Expectations.PayerConfirmation,
			},
		},
	}, nil
}

func decodeInsufficientFundsScenario(profileName string, index int, data []byte) (Scenario, error) {
	var file fileInsufficientFundsScenario
	if err := decodeStrict(data, &file); err != nil {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d]", index), fmt.Errorf("invalid insufficient-funds scenario: %w", err))
	}
	if err := validateParticipants(profileName, index, file.Participants); err != nil {
		return Scenario{}, err
	}
	if err := validateAmount(profileName, index, file.Amount); err != nil {
		return Scenario{}, err
	}
	if file.Expectations.HTTPStatus != ExpectedHTTP2xx {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].expectations.httpStatus", index), fmt.Errorf("must be %q", ExpectedHTTP2xx))
	}
	if file.Expectations.PayerConfirmation != ConfirmationForbidden {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].expectations.payerConfirmation", index), fmt.Errorf("must be %q", ConfirmationForbidden))
	}
	return Scenario{
		Type:  file.Type,
		Share: file.Share,
		InsufficientFunds: &InsufficientFundsScenario{
			Participants: runtimeParticipants(file.Participants),
			Amount:       runtimeAmount(file.Amount),
			Expectations: InsufficientFundsExpectations{
				HTTPStatus:        file.Expectations.HTTPStatus,
				PayerConfirmation: file.Expectations.PayerConfirmation,
			},
		},
	}, nil
}

func validateParticipants(profileName string, index int, participants fileHotColdPairDistribution) error {
	prefix := fmt.Sprintf("scenarios[%d].participants", index)
	if participants.HotPairCount <= 0 {
		return malformedProfile(profileName, prefix+".hotPairCount", errors.New("must be positive"))
	}
	if participants.ColdPairCount <= 0 {
		return malformedProfile(profileName, prefix+".coldPairCount", errors.New("must be positive"))
	}
	if participants.HotTrafficShare <= 0 || participants.HotTrafficShare >= 1 {
		return malformedProfile(profileName, prefix+".hotTrafficShare", errors.New("must be greater than 0 and less than 1"))
	}
	return nil
}

func validateAmount(profileName string, index int, amount fileSequentialRangeAmount) error {
	prefix := fmt.Sprintf("scenarios[%d].amount", index)
	if amount.Minimum <= 0 {
		return malformedProfile(profileName, prefix+".minimum", errors.New("must be positive"))
	}
	if amount.Maximum < amount.Minimum {
		return malformedProfile(profileName, prefix+".maximum", errors.New("must be greater than or equal to minimum"))
	}
	return nil
}

func runtimeParticipants(file fileHotColdPairDistribution) HotColdPairDistribution {
	return HotColdPairDistribution{
		HotPairCount:    file.HotPairCount,
		ColdPairCount:   file.ColdPairCount,
		HotTrafficShare: file.HotTrafficShare,
	}
}

func runtimeAmount(file fileSequentialRangeAmount) SequentialRangeAmount {
	return SequentialRangeAmount{Minimum: file.Minimum, Maximum: file.Maximum}
}

func (scenario Scenario) Participants() (HotColdPairDistribution, bool) {
	switch scenario.Type {
	case ScenarioHappyPath:
		if scenario.HappyPath != nil {
			return scenario.HappyPath.Participants, true
		}
	case ScenarioInsufficientFunds:
		if scenario.InsufficientFunds != nil {
			return scenario.InsufficientFunds.Participants, true
		}
	}
	return HotColdPairDistribution{}, false
}

func (scenario *Scenario) setPairNumberStart(start int) {
	switch scenario.Type {
	case ScenarioHappyPath:
		scenario.HappyPath.Participants.PairNumberStart = start
	case ScenarioInsufficientFunds:
		scenario.InsufficientFunds.Participants.PairNumberStart = start
	}
}

func (scenario Scenario) Amount() (SequentialRangeAmount, bool) {
	switch scenario.Type {
	case ScenarioHappyPath:
		if scenario.HappyPath != nil {
			return scenario.HappyPath.Amount, true
		}
	case ScenarioInsufficientFunds:
		if scenario.InsufficientFunds != nil {
			return scenario.InsufficientFunds.Amount, true
		}
	}
	return SequentialRangeAmount{}, false
}

func (scenario Scenario) Expectations() (string, string, bool) {
	switch scenario.Type {
	case ScenarioHappyPath:
		if scenario.HappyPath != nil {
			return scenario.HappyPath.Expectations.HTTPStatus, scenario.HappyPath.Expectations.PayerConfirmation, true
		}
	case ScenarioInsufficientFunds:
		if scenario.InsufficientFunds != nil {
			return scenario.InsufficientFunds.Expectations.HTTPStatus, scenario.InsufficientFunds.Expectations.PayerConfirmation, true
		}
	}
	return "", "", false
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
