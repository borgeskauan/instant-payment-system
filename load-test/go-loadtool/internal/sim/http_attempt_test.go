package sim

import (
	"context"
	"crypto/tls"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestHTTP2TransportIsExclusiveAndBounded(t *testing.T) {
	transport := newHTTP2Transport(&tls.Config{MinVersion: tls.VersionTLS12})
	defer transport.CloseIdleConnections()

	if maxConnectionsPerPSP != 32 {
		t.Fatalf("maxConnectionsPerPSP = %d, want 32", maxConnectionsPerPSP)
	}
	if transport.Protocols == nil || !transport.Protocols.HTTP2() {
		t.Fatal("HTTP/2 is not enabled")
	}
	if transport.Protocols.HTTP1() {
		t.Fatal("HTTP/1 fallback is enabled")
	}
	if transport.MaxConnsPerHost != 32 || transport.MaxIdleConnsPerHost != 32 || transport.MaxIdleConns != 32 {
		t.Fatalf("connection bounds = %d/%d/%d, want 32/32/32",
			transport.MaxConnsPerHost, transport.MaxIdleConnsPerHost, transport.MaxIdleConns)
	}
	if transport.IdleConnTimeout != 90*time.Second {
		t.Fatalf("IdleConnTimeout = %s, want 90s", transport.IdleConnTimeout)
	}
}

func TestHTTP2TransportRejectsHTTP11Server(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()

	serverTransport := server.Client().Transport.(*http.Transport)
	transport := newHTTP2Transport(serverTransport.TLSClientConfig.Clone())
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}

	_, err := client.Do(&http.Request{Method: http.MethodPost, URL: mustParseURL(t, server.URL), Body: http.NoBody})
	if err == nil {
		t.Fatal("HTTP/1.1 server returned a response, want HTTP/2 negotiation error")
	}
}

func TestPostRecordsProtocolViolation(t *testing.T) {
	s := &simulator{httpClients: map[string]*http.Client{
		"10000001": {
			Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{
					StatusCode: http.StatusOK,
					Proto:      "HTTP/1.1",
					ProtoMajor: 1,
					Body:       http.NoBody,
				}, nil
			}),
		},
	}}

	attempt := s.post(context.Background(), "10000001", "https://localhost:8001/transfer", []byte("pacs008"))

	if attempt.HTTPStatus != 0 {
		t.Fatalf("HTTPStatus = %d, want 0", attempt.HTTPStatus)
	}
	if err := s.currentRunError(); err == nil || !strings.Contains(err.Error(), "used HTTP/1, want HTTP/2") {
		t.Fatalf("run error = %v, want HTTP/1 protocol violation", err)
	}
}

func TestPostDoesNotRetryTransportFailure(t *testing.T) {
	var calls atomic.Int64
	s := &simulator{httpClients: map[string]*http.Client{
		"10000001": {
			Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
				calls.Add(1)
				return nil, errors.New("connection failed")
			}),
		},
	}}

	attempt := s.post(context.Background(), "10000001", "https://localhost:8001/transfer", []byte("pacs008"))

	if attempt.HTTPStatus != 0 {
		t.Fatalf("HTTPStatus = %d, want 0", attempt.HTTPStatus)
	}
	if calls.Load() != 1 {
		t.Fatalf("transport calls = %d, want 1", calls.Load())
	}
	if err := s.currentRunError(); err != nil {
		t.Fatalf("run error = %v, want nil", err)
	}
}

func mustParseURL(t *testing.T, rawURL string) *url.URL {
	t.Helper()
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		t.Fatal(err)
	}
	return parsedURL
}
