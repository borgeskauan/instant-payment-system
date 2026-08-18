package sim

import (
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"sync/atomic"
	"time"
)

const maxConnectionsPerPSP = 32

type httpAttemptResult struct {
	HTTPStatus             int
	ConnectionAcquiredAtNS int64
	RequestWrittenAtNS     int64
	ConnectionReused       bool
}

func newHTTP2Transport(tlsConfig *tls.Config) *http.Transport {
	protocols := new(http.Protocols)
	protocols.SetHTTP2(true)
	return &http.Transport{
		MaxIdleConns:        maxConnectionsPerPSP,
		MaxIdleConnsPerHost: maxConnectionsPerPSP,
		MaxConnsPerHost:     maxConnectionsPerPSP,
		IdleConnTimeout:     90 * time.Second,
		TLSClientConfig:     tlsConfig,
		Protocols:           protocols,
	}
}

func (s *simulator) post(ctx context.Context, ispb string, url string, body []byte) httpAttemptResult {
	var connectionAcquiredAt atomic.Int64
	var requestWrittenAt atomic.Int64
	var connectionReused atomic.Bool

	trace := &httptrace.ClientTrace{
		GotConn: func(info httptrace.GotConnInfo) {
			connectionAcquiredAt.Store(time.Now().UnixNano())
			connectionReused.Store(info.Reused)
		},
		WroteRequest: func(info httptrace.WroteRequestInfo) {
			if info.Err == nil {
				requestWrittenAt.Store(time.Now().UnixNano())
			}
		},
	}
	result := func(status int) httpAttemptResult {
		return httpAttemptResult{
			HTTPStatus:             status,
			ConnectionAcquiredAtNS: connectionAcquiredAt.Load(),
			RequestWrittenAtNS:     requestWrittenAt.Load(),
			ConnectionReused:       connectionReused.Load(),
		}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return result(0)
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))
	req.Header.Set("Content-Type", "application/octet-stream")
	client, exists := s.httpClients[ispb]
	if !exists {
		return result(0)
	}
	resp, err := client.Do(req)
	if err != nil {
		return result(0)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.ProtoMajor != 2 {
		s.recordRunError(fmt.Errorf(
			"central transfer response for ISPB %s used HTTP/%d, want HTTP/2",
			ispb, resp.ProtoMajor))
		return result(0)
	}
	return result(resp.StatusCode)
}
