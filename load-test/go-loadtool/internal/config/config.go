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
	"strconv"
	"strings"
	"time"
)

const (
	SchemaVersion               = 3
	DefaultProfile              = "uniform-smoke"
	ExpectedHTTP2xx             = "2xx"
	DeliveryAtLeastOnce         = "at-least-once"
	FundingFixed                = "fixed"
	FundingCoverGeneratedDebits = "cover-generated-debits"
	ScenarioSelectionBlockSize  = 100
	profilesDir                 = "../profiles"
	maxPairSuffix               = 999999
)

var contractNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)
var pacsCodePattern = regexp.MustCompile(`^[A-Z0-9]{4}$`)

type Runtime struct {
	Name          string
	SchemaVersion int
	Connections   Connections
	Load          Load
	Replay        Replay
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

type Replay struct {
	Pacs008 *Pacs008Replay
	Pacs002 *Pacs002Replay
}

type Pacs008Replay struct {
	Share float64
	Delay time.Duration
}

type Pacs002Replay struct {
	Share float64
	Delay time.Duration
}

type HotColdPairDistribution struct {
	PairNumberStart int
	HotPairCount    int
	ColdPairCount   int
	HotTrafficShare float64
}

type Scenario struct {
	Name         string
	Share        float64
	Participants HotColdPairDistribution
	Amount       SequentialRangeAmount
	Funding      ScenarioFunding
	Expectations ScenarioExpectations
}

type ScenarioFunding struct {
	Payer         FundingAccount
	Receiver      FundingAccount
	ResetIfExists bool
}

type FundingAccount struct {
	Mode         string
	Balance      string
	BalanceCents int64
}

type SequentialRangeAmount struct {
	Minimum int64
	Maximum int64
}

type ScenarioExpectations struct {
	HTTPStatus        string
	PayerNotification PayerNotificationExpectation
}

type PayerNotificationExpectation struct {
	DeliverySemantics string
	Status            string
	ReasonCodes       []string
}

type Reporting struct {
	SLAThresholdMs int64
}

type fileConfig struct {
	Name          string          `json:"name"`
	SchemaVersion int             `json:"schemaVersion"`
	Connections   fileConnections `json:"connections"`
	Load          fileLoad        `json:"load"`
	Replay        fileReplay      `json:"replay"`
	Scenarios     []fileScenario  `json:"scenarios"`
	Reporting     fileReporting   `json:"reporting"`
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

type fileReplay struct {
	Pacs008 *filePacs008Replay `json:"pacs008,omitempty"`
	Pacs002 *filePacs002Replay `json:"pacs002,omitempty"`
}

type filePacs008Replay struct {
	Share float64 `json:"share"`
	Delay string  `json:"delay"`
}

type filePacs002Replay struct {
	Share float64 `json:"share"`
	Delay string  `json:"delay"`
}

type fileHotColdPairDistribution struct {
	HotPairCount    int     `json:"hotPairCount"`
	ColdPairCount   int     `json:"coldPairCount"`
	HotTrafficShare float64 `json:"hotTrafficShare"`
}

type fileScenario struct {
	Name         string                      `json:"name"`
	Share        float64                     `json:"share"`
	Participants fileHotColdPairDistribution `json:"participants"`
	Amount       fileSequentialRangeAmount   `json:"amount"`
	Funding      fileScenarioFunding         `json:"funding"`
	Expectations fileScenarioExpectations    `json:"expectations"`
}

type fileScenarioFunding struct {
	Payer         fileFundingAccount `json:"payer"`
	Receiver      fileFundingAccount `json:"receiver"`
	ResetIfExists *bool              `json:"resetIfExists"`
}

type fileFundingAccount struct {
	Mode    string  `json:"mode"`
	Balance *string `json:"balance,omitempty"`
}

type fileSequentialRangeAmount struct {
	Minimum int64 `json:"minimum"`
	Maximum int64 `json:"maximum"`
}

type fileScenarioExpectations struct {
	HTTPStatus        string                           `json:"httpStatus"`
	PayerNotification filePayerNotificationExpectation `json:"payerNotification"`
}

type filePayerNotificationExpectation struct {
	DeliverySemantics string   `json:"deliverySemantics"`
	Status            string   `json:"status"`
	ReasonCodes       []string `json:"reasonCodes"`
}

type fileReporting struct {
	SLAThresholdMs int64 `json:"slaThresholdMs"`
}

func LoadProfile(name string) (Runtime, error) {
	return loadProfileFromDir(profilesDir, name)
}

func LoadRunProfile(path string) (Runtime, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Runtime{}, fmt.Errorf("run profile not found at %q", path)
		}
		return Runtime{}, fmt.Errorf("read run profile at %q: %w", path, err)
	}

	var file fileConfig
	if err := decodeStrict(data, &file); err != nil {
		return Runtime{}, fmt.Errorf("run profile at %q is malformed: invalid JSON contract: %w", path, err)
	}
	runtimeCfg, err := buildRuntime(file.Name, file)
	if err != nil {
		return Runtime{}, fmt.Errorf("run profile at %q: %w", path, err)
	}
	return runtimeCfg, nil
}

func loadProfileFromDir(dir string, name string) (Runtime, error) {
	if !contractNamePattern.MatchString(name) {
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
	if !contractNamePattern.MatchString(file.Name) {
		return Runtime{}, malformedProfile(name, "name", errors.New("must use only lowercase letters, digits, and hyphens, beginning with a letter or digit"))
	}
	if file.Name != name {
		return Runtime{}, malformedProfile(name, "name", fmt.Errorf("name %q does not match selected profile %q", file.Name, name))
	}
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
	replay, err := decodeReplay(name, file.Replay)
	if err != nil {
		return Runtime{}, err
	}
	if drain < maximumReplayDelay(replay) {
		return Runtime{}, malformedProfile(name, "load.drain", errors.New("must be at least the largest replay delay"))
	}

	if len(file.Scenarios) == 0 {
		return Runtime{}, malformedProfile(name, "scenarios", errors.New("must contain at least one scenario"))
	}
	scenarios := make([]Scenario, 0, len(file.Scenarios))
	seenNames := make(map[string]struct{}, len(file.Scenarios))
	totalQuota := 0
	nextPairNumber := 1
	for index, fileScenario := range file.Scenarios {
		scenario, err := decodeScenario(name, index, fileScenario)
		if err != nil {
			return Runtime{}, err
		}
		if _, exists := seenNames[scenario.Name]; exists {
			return Runtime{}, malformedProfile(name, fmt.Sprintf("scenarios[%d].name", index), fmt.Errorf("duplicate scenario name %q", scenario.Name))
		}
		seenNames[scenario.Name] = struct{}{}
		quota := scenario.Share * ScenarioSelectionBlockSize
		roundedQuota := math.Round(quota)
		if scenario.Share <= 0 || math.Abs(quota-roundedQuota) > 1e-9 {
			return Runtime{}, malformedProfile(name, fmt.Sprintf("scenarios[%d].share", index), fmt.Errorf("must be positive and select a whole number of entries in a %d-entry block", ScenarioSelectionBlockSize))
		}
		totalQuota += int(roundedQuota)
		participants := scenario.Participants
		remainingPairs := maxPairSuffix - nextPairNumber + 1
		if participants.HotPairCount > remainingPairs || participants.ColdPairCount > remainingPairs-participants.HotPairCount {
			return Runtime{}, malformedProfile(name, "scenarios.participants", fmt.Errorf("allocated pair range exceeds the maximum pair number %d", maxPairSuffix))
		}
		pairCount := participants.HotPairCount + participants.ColdPairCount
		scenario.Participants.PairNumberStart = nextPairNumber
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
		Name:          file.Name,
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
		Replay:    replay,
		Scenarios: scenarios,
		Reporting: Reporting{SLAThresholdMs: file.Reporting.SLAThresholdMs},
	}, nil
}

func decodeReplay(profileName string, file fileReplay) (Replay, error) {
	var replay Replay
	if file.Pacs008 != nil {
		share, delay, err := decodeReplayRule(profileName, "replay.pacs008", file.Pacs008.Share, file.Pacs008.Delay)
		if err != nil {
			return Replay{}, err
		}
		replay.Pacs008 = &Pacs008Replay{Share: share, Delay: delay}
	}
	if file.Pacs002 != nil {
		share, delay, err := decodeReplayRule(profileName, "replay.pacs002", file.Pacs002.Share, file.Pacs002.Delay)
		if err != nil {
			return Replay{}, err
		}
		replay.Pacs002 = &Pacs002Replay{Share: share, Delay: delay}
	}
	return replay, nil
}

func decodeReplayRule(profileName string, field string, share float64, delayText string) (float64, time.Duration, error) {
	quota := share * ScenarioSelectionBlockSize
	roundedQuota := math.Round(quota)
	if share <= 0 || share > 1 || math.Abs(quota-roundedQuota) > 1e-9 {
		return 0, 0, malformedProfile(profileName, field+".share", fmt.Errorf("must be greater than 0, at most 1, and select a whole number of entries in a %d-entry block", ScenarioSelectionBlockSize))
	}
	delay, err := parseWholeSecondDuration(profileName, field+".delay", delayText, false)
	if err != nil {
		return 0, 0, err
	}
	return share, delay, nil
}

func maximumReplayDelay(replay Replay) time.Duration {
	var delay time.Duration
	if replay.Pacs008 != nil && replay.Pacs008.Delay > delay {
		delay = replay.Pacs008.Delay
	}
	if replay.Pacs002 != nil && replay.Pacs002.Delay > delay {
		delay = replay.Pacs002.Delay
	}
	return delay
}

func decodeScenario(profileName string, index int, file fileScenario) (Scenario, error) {
	prefix := fmt.Sprintf("scenarios[%d]", index)
	if !contractNamePattern.MatchString(file.Name) {
		return Scenario{}, malformedProfile(profileName, prefix+".name", errors.New("scenario name must use only lowercase letters, digits, and hyphens, beginning with a letter or digit"))
	}
	if err := validateParticipants(profileName, index, file.Participants); err != nil {
		return Scenario{}, err
	}
	if err := validateAmount(profileName, index, file.Amount); err != nil {
		return Scenario{}, err
	}
	funding, err := validateFunding(profileName, index, file.Funding)
	if err != nil {
		return Scenario{}, err
	}
	if file.Expectations.HTTPStatus != ExpectedHTTP2xx {
		return Scenario{}, malformedProfile(profileName, fmt.Sprintf("scenarios[%d].expectations.httpStatus", index), fmt.Errorf("must be %q", ExpectedHTTP2xx))
	}
	payerNotification, err := validatePayerNotification(profileName, index, file.Expectations.PayerNotification)
	if err != nil {
		return Scenario{}, err
	}
	return Scenario{
		Name:         file.Name,
		Share:        file.Share,
		Participants: runtimeParticipants(file.Participants),
		Amount:       runtimeAmount(file.Amount),
		Funding:      funding,
		Expectations: ScenarioExpectations{
			HTTPStatus:        file.Expectations.HTTPStatus,
			PayerNotification: payerNotification,
		},
	}, nil
}

func validatePayerNotification(profileName string, index int, notification filePayerNotificationExpectation) (PayerNotificationExpectation, error) {
	prefix := fmt.Sprintf("scenarios[%d].expectations.payerNotification", index)
	if notification.DeliverySemantics != DeliveryAtLeastOnce {
		return PayerNotificationExpectation{}, malformedProfile(profileName, prefix+".deliverySemantics", fmt.Errorf("must be %q", DeliveryAtLeastOnce))
	}
	if !pacsCodePattern.MatchString(notification.Status) {
		return PayerNotificationExpectation{}, malformedProfile(profileName, prefix+".status", errors.New("must be a four-character uppercase alphanumeric PACS status code"))
	}
	if notification.ReasonCodes == nil {
		return PayerNotificationExpectation{}, malformedProfile(profileName, prefix+".reasonCodes", errors.New("must be specified as an array"))
	}
	seenReasons := make(map[string]struct{}, len(notification.ReasonCodes))
	for _, reasonCode := range notification.ReasonCodes {
		if !pacsCodePattern.MatchString(reasonCode) {
			return PayerNotificationExpectation{}, malformedProfile(profileName, prefix+".reasonCodes", fmt.Errorf("reason code %q must be four uppercase alphanumeric characters", reasonCode))
		}
		if _, exists := seenReasons[reasonCode]; exists {
			return PayerNotificationExpectation{}, malformedProfile(profileName, prefix+".reasonCodes", fmt.Errorf("duplicate reason code %q", reasonCode))
		}
		seenReasons[reasonCode] = struct{}{}
	}
	reasonCodes := make([]string, len(notification.ReasonCodes))
	copy(reasonCodes, notification.ReasonCodes)
	return PayerNotificationExpectation{
		DeliverySemantics: notification.DeliverySemantics,
		Status:            notification.Status,
		ReasonCodes:       reasonCodes,
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

func validateFunding(profileName string, index int, file fileScenarioFunding) (ScenarioFunding, error) {
	prefix := fmt.Sprintf("scenarios[%d].funding", index)
	payer, err := validateFundingAccount(profileName, prefix+".payer", file.Payer, true)
	if err != nil {
		return ScenarioFunding{}, err
	}
	receiver, err := validateFundingAccount(profileName, prefix+".receiver", file.Receiver, false)
	if err != nil {
		return ScenarioFunding{}, err
	}
	if file.ResetIfExists == nil {
		return ScenarioFunding{}, malformedProfile(profileName, prefix+".resetIfExists", errors.New("must be specified"))
	}
	return ScenarioFunding{Payer: payer, Receiver: receiver, ResetIfExists: *file.ResetIfExists}, nil
}

func validateFundingAccount(profileName string, field string, file fileFundingAccount, allowCover bool) (FundingAccount, error) {
	switch file.Mode {
	case FundingFixed:
		if file.Balance == nil {
			return FundingAccount{}, malformedProfile(profileName, field+".balance", errors.New("must be specified for fixed funding"))
		}
		balance, cents, err := parseBalance(*file.Balance)
		if err != nil {
			return FundingAccount{}, malformedProfile(profileName, field+".balance", err)
		}
		return FundingAccount{Mode: FundingFixed, Balance: balance, BalanceCents: cents}, nil
	case FundingCoverGeneratedDebits:
		if !allowCover {
			return FundingAccount{}, malformedProfile(profileName, field+".mode", fmt.Errorf("must be %q", FundingFixed))
		}
		if file.Balance != nil {
			return FundingAccount{}, malformedProfile(profileName, field+".balance", fmt.Errorf("must be omitted for %q funding", FundingCoverGeneratedDebits))
		}
		return FundingAccount{Mode: FundingCoverGeneratedDebits}, nil
	default:
		return FundingAccount{}, malformedProfile(profileName, field+".mode", fmt.Errorf("must be %q or %q", FundingFixed, FundingCoverGeneratedDebits))
	}
}

func parseBalance(value string) (string, int64, error) {
	parts := strings.Split(value, ".")
	if len(parts) > 2 || len(parts) == 0 || parts[0] == "" || !digitsOnly(parts[0]) {
		return "", 0, errors.New("must be a non-negative decimal string")
	}
	fraction := ""
	if len(parts) == 2 {
		fraction = parts[1]
		if fraction == "" || !digitsOnly(fraction) {
			return "", 0, errors.New("must be a non-negative decimal string")
		}
		if len(fraction) > 2 {
			return "", 0, errors.New("must have at most two fractional digits")
		}
	}
	fraction += strings.Repeat("0", 2-len(fraction))
	combined := strings.TrimLeft(parts[0]+fraction, "0")
	if combined == "" {
		combined = "0"
	}
	cents, err := strconv.ParseInt(combined, 10, 64)
	if err != nil {
		return "", 0, errors.New("overflows the supported balance range")
	}
	return FormatBalance(cents), cents, nil
}

func digitsOnly(value string) bool {
	for _, character := range value {
		if character < '0' || character > '9' {
			return false
		}
	}
	return value != ""
}

func FormatBalance(cents int64) string {
	return fmt.Sprintf("%d.%02d", cents/100, cents%100)
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
