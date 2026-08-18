package sim

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
)

func prewarmHTTP2Clients(ctx context.Context, baseURL string, clients map[string]*http.Client) error {
	healthURL := strings.TrimRight(baseURL, "/") + "/health"

	errors := make(chan error, len(clients))
	var workers sync.WaitGroup
	for ispb, client := range clients {
		workers.Add(1)
		go func(ispb string, client *http.Client) {
			defer workers.Done()
			if err := prewarmHTTP2Client(ctx, healthURL, ispb, client); err != nil {
				errors <- err
			}
		}(ispb, client)
	}
	workers.Wait()
	close(errors)

	if err, exists := <-errors; exists {
		return err
	}
	return nil
}

func prewarmHTTP2Client(ctx context.Context, healthURL, ispb string, client *http.Client) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, healthURL, nil)
	if err != nil {
		return fmt.Errorf("create central transfer health request for ISPB %s: %w", ispb, err)
	}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("call central transfer health for ISPB %s: %w", ispb, err)
	}
	_, copyErr := io.Copy(io.Discard, response.Body)
	closeErr := response.Body.Close()
	if copyErr != nil {
		return fmt.Errorf("read central transfer health for ISPB %s: %w", ispb, copyErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close central transfer health for ISPB %s: %w", ispb, closeErr)
	}
	if response.ProtoMajor != 2 {
		return fmt.Errorf("central transfer health for ISPB %s used HTTP/%d, want HTTP/2", ispb, response.ProtoMajor)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("central transfer health for ISPB %s returned status %d", ispb, response.StatusCode)
	}
	return nil
}
