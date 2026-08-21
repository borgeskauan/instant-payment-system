package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestValidateProfileDefaultsToUniformSmoke(t *testing.T) {
	var loadedProfile string
	_, err := parseValidateProfile(nil, func(name string) (config.Runtime, error) {
		loadedProfile = name
		return commandTestRuntime(), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if loadedProfile != config.DefaultProfile {
		t.Fatalf("loaded profile = %q, want %q", loadedProfile, config.DefaultProfile)
	}
}

func TestValidateProfilePropagatesEmbeddedProfileIdentity(t *testing.T) {
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = "embedded-profile"
	loader := func(string) (config.Runtime, error) { return runtimeCfg, nil }

	validation, err := parseValidateProfile([]string{"--profile", "selected-profile"}, loader)
	if err != nil {
		t.Fatalf("parseValidateProfile() error = %v", err)
	}
	if validation.Profile != "embedded-profile" {
		t.Fatalf("validation Profile = %q", validation.Profile)
	}
}

func TestValidateProfileReturnsNormalizedRunnerMetadata(t *testing.T) {
	var loadedProfile string
	validation, err := parseValidateProfile([]string{"--profile", "custom-validation"}, func(name string) (config.Runtime, error) {
		loadedProfile = name
		return commandTestRuntimeFor(name), nil
	})
	if err != nil {
		t.Fatal(err)
	}

	if loadedProfile != "custom-validation" || validation.Profile != "custom-validation" {
		t.Fatalf("loaded/output profile = %q/%q", loadedProfile, validation.Profile)
	}
	if validation.SchemaVersion != 3 || validation.WarmupSeconds != 12 || validation.ActiveSeconds != 34 || validation.DrainSeconds != 9 {
		t.Fatalf("validation window = %#v", validation)
	}
	if validation.Replay.Pacs008 == nil || validation.Replay.Pacs008.Share != 0.25 || validation.Replay.Pacs008.DelaySeconds != 7 {
		t.Fatalf("validation replay = %#v", validation.Replay)
	}
	if validation.Replay.Pacs002 == nil || validation.Replay.Pacs002.Share != 0.20 || validation.Replay.Pacs002.DelaySeconds != 11 {
		t.Fatalf("validation PACS.002 replay = %#v", validation.Replay)
	}
	if len(validation.Scenarios) != 1 {
		t.Fatalf("validation scenarios = %#v", validation.Scenarios)
	}
	scenario := validation.Scenarios[0]
	if scenario.Name != "happy-path" || scenario.Share != 1 {
		t.Fatalf("validation scenario = %#v", scenario)
	}
	if scenario.Participants.PairNumberStart != 101 || scenario.Participants.HotPairCount != 7 || scenario.Participants.ColdPairCount != 13 {
		t.Fatalf("validation participants = %#v", scenario.Participants)
	}
	if scenario.Amount.Minimum != 100 || scenario.Amount.Maximum != 100098 {
		t.Fatalf("validation amount = %#v", scenario.Amount)
	}
	if scenario.Funding.Payer.Mode != config.FundingCoverGeneratedDebits || scenario.Funding.Payer.Balance != "" || scenario.Funding.Receiver.Balance != "0.00" || !scenario.Funding.ResetIfExists {
		t.Fatalf("validation funding = %#v", scenario.Funding)
	}
	if scenario.Provisioning.PayerBalance == "0.00" || scenario.Provisioning.ReceiverBalance != "0.00" || !scenario.Provisioning.ResetIfExists {
		t.Fatalf("validation provisioning = %#v", scenario.Provisioning)
	}
	if scenario.Expectations.HTTPStatus != config.ExpectedHTTP2xx || scenario.Expectations.PayerNotification.DeliverySemantics != config.DeliveryAtLeastOnce || scenario.Expectations.PayerNotification.Status != "ACSC" || len(scenario.Expectations.PayerNotification.ReasonCodes) != 0 {
		t.Fatalf("validation expectations = %#v", scenario.Expectations)
	}
	encoded, err := json.Marshal(validation)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), `"pairNumberStart"`) {
		t.Fatalf("normalized execution plan omits pairNumberStart: %s", encoded)
	}
	if strings.Contains(string(encoded), `"notificationPull"`) {
		t.Fatalf("normalized execution plan exposes fixed pull protocol details: %s", encoded)
	}
}

func TestValidateProfileReturnsMixedScenarioProvisioning(t *testing.T) {
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Load = config.Load{TargetTxRate: 100, Warmup: 5 * time.Second, Duration: 10 * time.Second, Drain: 10 * time.Second}
	runtimeCfg.Scenarios[0].Share = 0.8
	runtimeCfg.Scenarios = append(runtimeCfg.Scenarios, config.Scenario{
		Name:         "insufficient-funds",
		Share:        0.2,
		Participants: config.HotColdPairDistribution{PairNumberStart: 121, HotPairCount: 2, ColdPairCount: 8, HotTrafficShare: 0.8},
		Amount:       config.SequentialRangeAmount{Minimum: 100, Maximum: 100098},
		Funding: config.ScenarioFunding{
			Payer:         config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
			Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
			ResetIfExists: true,
		},
		Expectations: config.ScenarioExpectations{HTTPStatus: config.ExpectedHTTP2xx, PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "RJCT", ReasonCodes: []string{"AM04"}}},
	})
	validation, err := parseValidateProfile([]string{"--profile", "mixed"}, func(string) (config.Runtime, error) {
		return runtimeCfg, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(validation.Scenarios) != 2 {
		t.Fatalf("scenarios = %#v", validation.Scenarios)
	}
	if validation.Scenarios[0].Provisioning.PayerBalance == "0.00" || validation.Scenarios[0].Provisioning.ReceiverBalance != "0.00" {
		t.Fatalf("happy provisioning = %#v", validation.Scenarios[0].Provisioning)
	}
	if validation.Scenarios[1].Name != "insufficient-funds" || validation.Scenarios[1].Provisioning.PayerBalance != "0.00" || validation.Scenarios[1].Provisioning.ReceiverBalance != "0.00" {
		t.Fatalf("insufficient provisioning = %#v", validation.Scenarios[1])
	}
}

func TestValidateProfileRejectsPositionalArguments(t *testing.T) {
	_, err := parseValidateProfile([]string{"unexpected"}, func(string) (config.Runtime, error) {
		return commandTestRuntime(), nil
	})
	if err == nil {
		t.Fatal("validate-profile accepted positional argument")
	}
}

func TestValidateProfileReturnsSelectedProfileLoadError(t *testing.T) {
	loader := func(name string) (config.Runtime, error) {
		return config.Runtime{}, fmt.Errorf("profile %q not found", name)
	}

	if _, err := parseValidateProfile([]string{"--profile", "missing"}, loader); err == nil {
		t.Fatal("validate-profile accepted missing profile")
	}
}

func commandTestRuntime() config.Runtime {
	return config.Runtime{
		Name:          "test-profile",
		SchemaVersion: 3,
		Connections: config.Connections{
			CentralTransfer: config.CentralTransferConnection{
				BaseURL:        "https://profile-central:8001",
				CACert:         "/profile/central-ca.crt",
				ClientCertRoot: "/profile/central-clients",
				ServerName:     "profile-central",
			},
			NotificationGateway: config.NotificationGatewayConnection{
				Address:        "profile-gateway:9090",
				CACert:         "/profile/gateway-ca.crt",
				ClientCertRoot: "/profile/gateway-clients",
				ServerName:     "profile-gateway",
			},
		},
		Load: config.Load{
			TargetTxRate: 321,
			Warmup:       12 * time.Second,
			Duration:     34 * time.Second,
			Drain:        9 * time.Second,
		},
		Replay: config.Replay{
			Pacs008: &config.Pacs008Replay{Share: 0.25, Delay: 7 * time.Second},
			Pacs002: &config.Pacs002Replay{Share: 0.20, Delay: 11 * time.Second},
		},
		Scenarios: []config.Scenario{{
			Name:  "happy-path",
			Share: 1,
			Participants: config.HotColdPairDistribution{
				PairNumberStart: 101,
				HotPairCount:    7,
				ColdPairCount:   13,
				HotTrafficShare: 0.75,
			},
			Amount: config.SequentialRangeAmount{
				Minimum: 100,
				Maximum: 100098,
			},
			Funding: config.ScenarioFunding{
				Payer:         config.FundingAccount{Mode: config.FundingCoverGeneratedDebits},
				Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
				ResetIfExists: true,
			},
			Expectations: config.ScenarioExpectations{
				HTTPStatus:        config.ExpectedHTTP2xx,
				PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "ACSC", ReasonCodes: []string{}},
			},
		}},
		Reporting: config.Reporting{SLAThresholdMs: 987},
	}
}

func commandTestRuntimeFor(name string) config.Runtime {
	runtimeCfg := commandTestRuntime()
	runtimeCfg.Name = name
	return runtimeCfg
}
