package sim

import (
	"context"
	"crypto/tls"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHTTP11TransportBoundsPerPSPPool(t *testing.T) {
	transport := newHTTP11Transport(&tls.Config{MinVersion: tls.VersionTLS12})
	defer transport.CloseIdleConnections()

	if maxHTTP11ConnectionsPerPSP != 32 {
		t.Fatalf("maxHTTP11ConnectionsPerPSP = %d, want 32", maxHTTP11ConnectionsPerPSP)
	}
	if transport.MaxConnsPerHost != maxHTTP11ConnectionsPerPSP {
		t.Fatalf("MaxConnsPerHost = %d, want %d", transport.MaxConnsPerHost, maxHTTP11ConnectionsPerPSP)
	}
	if transport.MaxIdleConnsPerHost != maxHTTP11ConnectionsPerPSP {
		t.Fatalf("MaxIdleConnsPerHost = %d, want %d", transport.MaxIdleConnsPerHost, maxHTTP11ConnectionsPerPSP)
	}
	if transport.MaxIdleConns != maxHTTP11ConnectionsPerPSP {
		t.Fatalf("MaxIdleConns = %d, want %d", transport.MaxIdleConns, maxHTTP11ConnectionsPerPSP)
	}
	if transport.DisableKeepAlives {
		t.Fatal("DisableKeepAlives = true, want keep-alive enabled")
	}
	if transport.IdleConnTimeout != 90*time.Second {
		t.Fatalf("IdleConnTimeout = %s, want 90s", transport.IdleConnTimeout)
	}
}

func TestPostObservesNewThenReusedHTTP11Connection(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		_, _ = io.Copy(io.Discard, request.Body)
		_ = request.Body.Close()
		response.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	transport := newHTTP11Transport(nil)
	defer transport.CloseIdleConnections()
	s := &simulator{httpClients: map[string]*http.Client{
		"10000001": {
			Transport: transport,
			Timeout:   time.Second,
		},
	}}

	first := s.post(context.Background(), "10000001", server.URL, []byte("first"))
	second := s.post(context.Background(), "10000001", server.URL, []byte("second"))

	for index, attempt := range []httpAttemptResult{first, second} {
		if attempt.HTTPStatus != http.StatusOK {
			t.Fatalf("attempt %d HTTPStatus = %d, want 200", index+1, attempt.HTTPStatus)
		}
		if attempt.ConnectionAcquiredAtNS == 0 {
			t.Fatalf("attempt %d did not record connection acquisition", index+1)
		}
		if attempt.RequestWrittenAtNS == 0 {
			t.Fatalf("attempt %d did not record request write", index+1)
		}
	}
	if first.ConnectionReused {
		t.Fatal("first request unexpectedly reused a connection")
	}
	if !second.ConnectionReused {
		t.Fatal("second request did not reuse the first HTTP/1.1 connection")
	}
}
