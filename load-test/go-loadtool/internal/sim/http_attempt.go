package sim

import (
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"time"
)

const maxConnectionsPerPSP = 32

type httpAttemptResult struct {
	HTTPStatus int
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
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return httpAttemptResult{}
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	client, exists := s.httpClients[ispb]
	if !exists {
		return httpAttemptResult{}
	}
	resp, err := client.Do(req)
	if err != nil {
		return httpAttemptResult{}
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.ProtoMajor != 2 {
		s.recordRunError(fmt.Errorf(
			"central transfer response for ISPB %s used HTTP/%d, want HTTP/2",
			ispb, resp.ProtoMajor))
		return httpAttemptResult{}
	}
	return httpAttemptResult{HTTPStatus: resp.StatusCode}
}
