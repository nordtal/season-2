package gamehealth

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig/caddyfile"
)

func TestPingStatusAnswers(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		answerStatus(conn)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if !pingStatus(ctx, ln.Addr().String()) {
		t.Fatal("expected status ping to succeed")
	}
}

func TestPingStatusAcceptAndCloseIsDown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, err := ln.Accept()
		if err == nil {
			_ = conn.Close()
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if pingStatus(ctx, ln.Addr().String()) {
		t.Fatal("expected accept-and-close port to count as down")
	}
}

func TestPingStatusTruncatedFrameIsDown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = conn.Write([]byte{0x05, 0x00, 0x03, '{'})
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if pingStatus(ctx, ln.Addr().String()) {
		t.Fatal("expected truncated status frame to count as down")
	}
}

func TestPingStatusRefusedIsDown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if pingStatus(ctx, addr) {
		t.Fatal("expected closed port to count as down")
	}
}

func TestPingStatusRespectsTimeout(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	release := make(chan struct{})
	defer close(release)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		<-release
	}()

	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if pingStatus(ctx, ln.Addr().String()) {
		t.Fatal("expected hanging server to time out as down")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("ping took too long to time out: %s", elapsed)
	}
}

func TestHandlerCachesAndExpires(t *testing.T) {
	now := time.Date(2026, 11, 23, 12, 0, 0, 0, time.UTC)
	var up atomic.Bool
	up.Store(true)
	var calls atomic.Int32

	h := &Handler{
		Timeout:  caddy.Duration(7 * time.Second),
		CacheTTL: caddy.Duration(2 * time.Second),
		clock:    func() time.Time { return now },
		ping: func(_ context.Context) bool {
			calls.Add(1)
			return up.Load()
		},
	}
	if err := h.Provision(caddy.Context{}); err != nil {
		t.Fatal(err)
	}

	assertResponse(t, h, http.MethodGet, http.StatusOK, `{"game":true}`)
	if calls.Load() != 1 {
		t.Fatalf("expected 1 ping, got %d", calls.Load())
	}

	// Flip backend state while cache is still fresh.
	up.Store(false)
	now = now.Add(1500 * time.Millisecond)
	assertResponse(t, h, http.MethodGet, http.StatusOK, `{"game":true}`)
	if calls.Load() != 1 {
		t.Fatalf("expected cached answer without second ping, got %d", calls.Load())
	}

	// Advance past the two-second cache TTL.
	now = now.Add(600 * time.Millisecond)
	assertResponse(t, h, http.MethodGet, http.StatusServiceUnavailable, `{"game":false}`)
	if calls.Load() != 2 {
		t.Fatalf("expected second ping after cache expiry, got %d", calls.Load())
	}
}

func TestHandlerCoalescesConcurrentRequests(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	var calls atomic.Int32

	h := &Handler{
		Timeout:  caddy.Duration(7 * time.Second),
		CacheTTL: caddy.Duration(2 * time.Second),
		ping: func(_ context.Context) bool {
			if calls.Add(1) == 1 {
				close(started)
			}
			<-release
			return true
		},
	}
	if err := h.Provision(caddy.Context{}); err != nil {
		t.Fatal(err)
	}

	const probes = 4
	var wg sync.WaitGroup
	wg.Add(probes)
	results := make([]int, probes)

	for i := range probes {
		go func(idx int) {
			defer wg.Done()
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, "/api/health/game", nil)
			if err := h.ServeHTTP(rec, req, nil); err != nil {
				t.Errorf("ServeHTTP failed: %v", err)
			}
			results[idx] = rec.Code
		}(i)
	}

	<-started
	time.Sleep(30 * time.Millisecond)
	close(release)
	wg.Wait()

	if calls.Load() != 1 {
		t.Fatalf("expected 1 coalesced ping for %d concurrent probes, got %d", probes, calls.Load())
	}
	for i, code := range results {
		if code != http.StatusOK {
			t.Fatalf("probe %d returned %d, want 200", i, code)
		}
	}
}

func TestHandlerHeadAndMethodNotAllowed(t *testing.T) {
	h := &Handler{
		ping: func(_ context.Context) bool { return true },
	}
	if err := h.Provision(caddy.Context{}); err != nil {
		t.Fatal(err)
	}

	assertResponse(t, h, http.MethodHead, http.StatusOK, "")

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/health/game", nil)
	if err := h.ServeHTTP(rec, req, nil); err != nil {
		t.Fatal(err)
	}
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST status = %d, want 405", rec.Code)
	}
	if allow := rec.Header().Get("Allow"); allow != "GET, HEAD" {
		t.Fatalf("Allow header = %q, want %q", allow, "GET, HEAD")
	}
}

func TestUnmarshalCaddyfile(t *testing.T) {
	d := caddyfile.NewTestDispenser(`game_health`)
	var def Handler
	if err := def.UnmarshalCaddyfile(d); err != nil {
		t.Fatal(err)
	}
	if err := def.Provision(caddy.Context{}); err != nil {
		t.Fatal(err)
	}
	if def.Address != "127.0.0.1:25565" || time.Duration(def.Timeout) != 7*time.Second || time.Duration(def.CacheTTL) != 2*time.Second {
		t.Fatalf("unexpected defaults: addr=%q timeout=%s cache_ttl=%s", def.Address, time.Duration(def.Timeout), time.Duration(def.CacheTTL))
	}

	d = caddyfile.NewTestDispenser(`
		game_health {
			address 127.0.0.1:25566
			timeout 5s
			cache_ttl 1s
		}
	`)
	var custom Handler
	if err := custom.UnmarshalCaddyfile(d); err != nil {
		t.Fatal(err)
	}
	if err := custom.Provision(caddy.Context{}); err != nil {
		t.Fatal(err)
	}
	if custom.Address != "127.0.0.1:25566" || time.Duration(custom.Timeout) != 5*time.Second || time.Duration(custom.CacheTTL) != time.Second {
		t.Fatalf("unexpected custom config: addr=%q timeout=%s cache_ttl=%s", custom.Address, time.Duration(custom.Timeout), time.Duration(custom.CacheTTL))
	}
}

func assertResponse(t *testing.T, h *Handler, method string, wantCode int, wantBody string) {
	t.Helper()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(method, "/api/health/game", nil)
	if err := h.ServeHTTP(rec, req, nil); err != nil {
		t.Fatal(err)
	}
	if rec.Code != wantCode {
		t.Fatalf("%s status = %d, want %d (body=%q)", method, rec.Code, wantCode, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", ct)
	}
	if rec.Body.String() != wantBody {
		t.Fatalf("%s body = %q, want %q", method, rec.Body.String(), wantBody)
	}
}

func answerStatus(conn net.Conn) {
	r := bufio.NewReader(conn)
	handshakeLen, err := readVarInt(r)
	if err != nil {
		return
	}
	if _, err := io.CopyN(io.Discard, r, int64(handshakeLen)); err != nil {
		return
	}
	requestLen, err := readVarInt(r)
	if err != nil {
		return
	}
	if _, err := io.CopyN(io.Discard, r, int64(requestLen)); err != nil {
		return
	}

	jsonBody := `{"version":{"name":"Velocity","protocol":767}}`
	var body []byte
	body = append(body, 0x00)
	body = appendVarInt(body, int32(len(jsonBody)))
	body = append(body, jsonBody...)

	var packet []byte
	packet = appendVarInt(packet, int32(len(body)))
	packet = append(packet, body...)
	_, _ = conn.Write(packet)
}
