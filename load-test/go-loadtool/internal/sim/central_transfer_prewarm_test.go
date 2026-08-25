package sim

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPrewarmCallsHealthExactlyOnceForEveryPSP(t *testing.T) {
	var callsMu sync.Mutex
	calls := map[string]int{}
	clients := map[string]*http.Client{}
	for _, ispb := range []string{"10000001", "20000001"} {
		identity := ispb
		clients[identity] = &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
			if request.Method != http.MethodGet || request.URL.Path != "/health" {
				t.Errorf("%s request = %s %s", identity, request.Method, request.URL.Path)
			}
			callsMu.Lock()
			calls[identity]++
			callsMu.Unlock()
			return http2Response(http.StatusOK), nil
		})}
	}

	if err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients); err != nil {
		t.Fatal(err)
	}
	for ispb := range clients {
		callsMu.Lock()
		count := calls[ispb]
		callsMu.Unlock()
		if count != 1 {
			t.Fatalf("PSP %s health calls = %d, want 1", ispb, count)
		}
	}
}

func TestPrewarmReturnsPSPAndStatusForUnsuccessfulHealth(t *testing.T) {
	clients := map[string]*http.Client{
		"10000001": {Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			return http2Response(http.StatusInternalServerError), nil
		})},
	}

	err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients)
	if err == nil || !strings.Contains(err.Error(), "10000001") || !strings.Contains(err.Error(), "status 500") {
		t.Fatalf("prewarm error = %v, want ISPB and status 500", err)
	}
}

func TestPrewarmRejectsHTTP11Response(t *testing.T) {
	clients := map[string]*http.Client{
		"10000001": {Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			return &http.Response{
				StatusCode: http.StatusOK,
				Proto:      "HTTP/1.1",
				ProtoMajor: 1,
				Body:       http.NoBody,
			}, nil
		})},
	}

	err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients)
	if err == nil || !strings.Contains(err.Error(), "used HTTP/1, want HTTP/2") {
		t.Fatalf("prewarm error = %v, want HTTP/1 protocol violation", err)
	}
}

func TestPrewarmReturnsPSPAndCallsTransportOnceOnRoundTripFailure(t *testing.T) {
	var callsMu sync.Mutex
	calls := 0
	clients := map[string]*http.Client{
		"10000001": {Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			callsMu.Lock()
			calls++
			callsMu.Unlock()
			return nil, errors.New("connection failed")
		})},
	}

	err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients)
	if err == nil || !strings.Contains(err.Error(), "10000001") {
		t.Fatalf("prewarm error = %v, want ISPB", err)
	}
	callsMu.Lock()
	defer callsMu.Unlock()
	if calls != 1 {
		t.Fatalf("transport calls = %d, want 1", calls)
	}
}

func TestPrewarmCallsEveryOtherPSPAfterAClientFails(t *testing.T) {
	failureReturned := make(chan struct{})
	var callsMu sync.Mutex
	calls := map[string]int{}
	clients := map[string]*http.Client{
		"10000001": {Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			callsMu.Lock()
			calls["10000001"]++
			callsMu.Unlock()
			close(failureReturned)
			return nil, errors.New("connection failed")
		})},
		"20000001": {Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
			<-failureReturned
			if err := request.Context().Err(); err != nil {
				t.Errorf("healthy PSP request context = %v, want active context", err)
			}
			callsMu.Lock()
			calls["20000001"]++
			callsMu.Unlock()
			return http2Response(http.StatusOK), nil
		})},
	}

	if err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients); err == nil {
		t.Fatal("prewarm error = nil, want failing PSP error")
	}
	for ispb := range clients {
		callsMu.Lock()
		count := calls[ispb]
		callsMu.Unlock()
		if count != 1 {
			t.Fatalf("PSP %s health calls = %d, want 1", ispb, count)
		}
	}
}

func TestPrewarmRetainsClientForTransfer(t *testing.T) {
	var pathsMu sync.Mutex
	var paths []string
	client := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		pathsMu.Lock()
		paths = append(paths, request.URL.Path)
		pathsMu.Unlock()
		return http2Response(http.StatusOK), nil
	})}
	clients := map[string]*http.Client{"10000001": client}

	if err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001/", clients); err != nil {
		t.Fatal(err)
	}
	s := &simulator{httpClients: clients}
	if result := s.post(context.Background(), "10000001", "https://localhost:8001/transfer", []byte("pacs008"), defaultRequestTimeout); result.HTTPStatus != http.StatusOK {
		t.Fatalf("transfer status = %d, want 200", result.HTTPStatus)
	}

	pathsMu.Lock()
	defer pathsMu.Unlock()
	if strings.Join(paths, ",") != "/health,/transfer" {
		t.Fatalf("request paths = %v, want [/health /transfer]", paths)
	}
}

func TestPrewarmReusesConfiguredHTTP2ClientForConcurrentTransfers(t *testing.T) {
	var healthRequests atomic.Int64
	var transferRequests atomic.Int64
	var activeTransfers atomic.Int64
	var maximumConcurrentTransfers atomic.Int64
	var connections atomic.Int64
	var wrongProtocol atomic.Bool
	transferStarted := make(chan struct{}, 2)
	releaseTransfers := make(chan struct{})
	var releaseOnce sync.Once
	release := func() {
		releaseOnce.Do(func() { close(releaseTransfers) })
	}
	defer release()

	server := httptest.NewUnstartedServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.ProtoMajor != 2 {
			wrongProtocol.Store(true)
		}
		_, _ = io.Copy(io.Discard, request.Body)
		_ = request.Body.Close()
		switch request.URL.Path {
		case "/health":
			healthRequests.Add(1)
			response.WriteHeader(http.StatusOK)
		case "/transfer":
			transferRequests.Add(1)
			active := activeTransfers.Add(1)
			for {
				maximum := maximumConcurrentTransfers.Load()
				if active <= maximum || maximumConcurrentTransfers.CompareAndSwap(maximum, active) {
					break
				}
			}
			transferStarted <- struct{}{}
			<-releaseTransfers
			activeTransfers.Add(-1)
			response.WriteHeader(http.StatusOK)
		default:
			response.WriteHeader(http.StatusNotFound)
		}
	}))
	server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			connections.Add(1)
		}
	}
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()

	serverTransport := server.Client().Transport.(*http.Transport)
	transport := newHTTP2Transport(serverTransport.TLSClientConfig.Clone())
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	clients := map[string]*http.Client{"10000001": client}

	if err := prewarmHTTP2Clients(context.Background(), server.URL, clients); err != nil {
		t.Fatal(err)
	}
	if clients["10000001"] != client {
		t.Fatal("prewarm replaced the configured PSP client")
	}

	s := &simulator{httpClients: clients}
	results := make(chan httpAttemptResult, 2)
	for range 2 {
		go func() {
			results <- s.post(context.Background(), "10000001", server.URL+"/transfer", []byte("pacs008"), defaultRequestTimeout)
		}()
	}
	for range 2 {
		select {
		case <-transferStarted:
		case <-time.After(5 * time.Second):
			t.Fatal("concurrent HTTP/2 transfers did not reach the server")
		}
	}
	release()

	for range 2 {
		result := <-results
		if result.HTTPStatus != http.StatusOK {
			t.Fatalf("transfer status = %d, want 200", result.HTTPStatus)
		}
	}
	if healthRequests.Load() != 1 || transferRequests.Load() != 2 {
		t.Fatalf("requests = health:%d transfer:%d, want 1/2", healthRequests.Load(), transferRequests.Load())
	}
	if wrongProtocol.Load() {
		t.Fatal("server observed a non-HTTP/2 request")
	}
	if connections.Load() != 1 {
		t.Fatalf("HTTP/2 connections = %d, want the single prewarmed connection", connections.Load())
	}
	if maximumConcurrentTransfers.Load() < 2 {
		t.Fatalf("maximum concurrent transfers = %d, want at least 2", maximumConcurrentTransfers.Load())
	}
}

func http2Response(status int) *http.Response {
	return &http.Response{
		StatusCode: status,
		Proto:      "HTTP/2.0",
		ProtoMajor: 2,
		Body:       http.NoBody,
	}
}
