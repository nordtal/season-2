package gamehealth

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig/caddyfile"
	"github.com/caddyserver/caddy/v2/caddyconfig/httpcaddyfile"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
)

const (
	defaultAddress  = "127.0.0.1:25565"
	defaultTimeout  = 7 * time.Second
	defaultCacheTTL = 2 * time.Second

	// Status ping is protocol-independent. Value -1 asks for no specific version.
	anyProtocol     = -1
	nextStateStatus = 1
	maxPacketLength = 1 << 21
)

var (
	bodyUp   = []byte(`{"game":true}`)
	bodyDown = []byte(`{"game":false}`)
)

func init() {
	caddy.RegisterModule(Handler{})
	httpcaddyfile.RegisterHandlerDirective("game_health", parseCaddyfile)
	httpcaddyfile.RegisterDirectiveOrder("game_health", httpcaddyfile.Before, "reverse_proxy")
}

// Handler answers GET and HEAD requests with whether a Minecraft proxy answers
// a status ping through Caddy's local layer4 game port.
type Handler struct {
	// Target host:port for the Minecraft status ping. Defaults to 127.0.0.1:25565.
	Address string `json:"address,omitempty"`

	// Maximum duration for one status ping, including layer4 proxy failover. Defaults to 7s.
	Timeout caddy.Duration `json:"timeout,omitempty"`

	// Duration a ping result is reused across probes. Defaults to 2s.
	CacheTTL caddy.Duration `json:"cache_ttl,omitempty"`

	clock func() time.Time
	ping  func(context.Context) bool

	mu          sync.Mutex
	hasCache    bool
	cachedUp    bool
	cachedUntil time.Time
	inFlight    *probeCall
}

type probeCall struct {
	done chan struct{}
	up   bool
}

// CaddyModule returns the Caddy module information.
func (Handler) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "http.handlers.game_health",
		New: func() caddy.Module { return new(Handler) },
	}
}

// Provision applies defaults and validates configuration.
func (h *Handler) Provision(_ caddy.Context) error {
	if h.Address == "" {
		h.Address = defaultAddress
	}
	if _, _, err := parseHostPort(h.Address); err != nil {
		return fmt.Errorf("invalid address %q: %w", h.Address, err)
	}
	if h.Timeout == 0 {
		h.Timeout = caddy.Duration(defaultTimeout)
	}
	if h.Timeout < 0 {
		return fmt.Errorf("timeout must be positive: %s", time.Duration(h.Timeout))
	}
	if h.CacheTTL == 0 {
		h.CacheTTL = caddy.Duration(defaultCacheTTL)
	}
	if h.CacheTTL < 0 {
		return fmt.Errorf("cache_ttl must not be negative: %s", time.Duration(h.CacheTTL))
	}
	if h.clock == nil {
		h.clock = time.Now
	}
	if h.ping == nil {
		addr := h.Address
		h.ping = func(ctx context.Context) bool {
			return pingStatus(ctx, addr)
		}
	}
	return nil
}

// ServeHTTP responds with 200 {"game":true} when a proxy answers the status ping
// and 503 {"game":false} otherwise.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request, _ caddyhttp.Handler) error {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(http.StatusMethodNotAllowed)
		return nil
	}

	up, err := h.check(r.Context())
	if err != nil {
		return nil
	}

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if up {
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write(bodyUp)
		}
		return nil
	}

	w.WriteHeader(http.StatusServiceUnavailable)
	if r.Method != http.MethodHead {
		_, _ = w.Write(bodyDown)
	}
	return nil
}

func (h *Handler) check(ctx context.Context) (bool, error) {
	h.mu.Lock()
	if h.hasCache && h.clock().Before(h.cachedUntil) {
		up := h.cachedUp
		h.mu.Unlock()
		return up, nil
	}
	if h.inFlight != nil {
		call := h.inFlight
		h.mu.Unlock()
		select {
		case <-call.done:
			return call.up, nil
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}

	call := &probeCall{done: make(chan struct{})}
	h.inFlight = call
	h.mu.Unlock()

	go func() {
		probeCtx, cancel := context.WithTimeout(context.Background(), time.Duration(h.Timeout))
		defer cancel()
		up := h.ping(probeCtx)

		h.mu.Lock()
		call.up = up
		if h.CacheTTL > 0 {
			h.cachedUp = up
			h.cachedUntil = h.clock().Add(time.Duration(h.CacheTTL))
			h.hasCache = true
		}
		h.inFlight = nil
		close(call.done)
		h.mu.Unlock()
	}()

	select {
	case <-call.done:
		return call.up, nil
	case <-ctx.Done():
		return false, ctx.Err()
	}
}

// UnmarshalCaddyfile parses the game_health directive.
func (h *Handler) UnmarshalCaddyfile(d *caddyfile.Dispenser) error {
	for d.Next() {
		if d.NextArg() {
			return d.ArgErr()
		}
		for nesting := d.Nesting(); d.NextBlock(nesting); {
			switch d.Val() {
			case "address":
				if !d.NextArg() {
					return d.ArgErr()
				}
				h.Address = d.Val()
				if d.NextArg() {
					return d.ArgErr()
				}
			case "timeout":
				if !d.NextArg() {
					return d.ArgErr()
				}
				dur, err := caddy.ParseDuration(d.Val())
				if err != nil {
					return d.Errf("invalid timeout %q: %v", d.Val(), err)
				}
				h.Timeout = caddy.Duration(dur)
				if d.NextArg() {
					return d.ArgErr()
				}
			case "cache_ttl":
				if !d.NextArg() {
					return d.ArgErr()
				}
				dur, err := caddy.ParseDuration(d.Val())
				if err != nil {
					return d.Errf("invalid cache_ttl %q: %v", d.Val(), err)
				}
				h.CacheTTL = caddy.Duration(dur)
				if d.NextArg() {
					return d.ArgErr()
				}
			default:
				return d.Errf("unknown subdirective %q", d.Val())
			}
		}
	}
	return nil
}

func parseCaddyfile(h httpcaddyfile.Helper) (caddyhttp.MiddlewareHandler, error) {
	var handler Handler
	if err := handler.UnmarshalCaddyfile(h.Dispenser); err != nil {
		return nil, err
	}
	return &handler, nil
}

func parseHostPort(address string) (string, uint16, error) {
	host, portStr, err := net.SplitHostPort(address)
	if err != nil {
		return "", 0, err
	}
	if host == "" {
		return "", 0, errors.New("empty host")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return "", 0, fmt.Errorf("invalid port %q", portStr)
	}
	return host, uint16(port), nil
}

func pingStatus(ctx context.Context, address string) bool {
	host, port, err := parseHostPort(address)
	if err != nil {
		return false
	}

	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return false
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	payload := buildStatusPing(host, port)
	if _, err := conn.Write(payload); err != nil {
		return false
	}

	return isStatusResponse(bufio.NewReaderSize(conn, 256))
}

func buildStatusPing(host string, port uint16) []byte {
	body := make([]byte, 0, 1+5+5+len(host)+2+1)
	body = append(body, 0x00)
	body = appendVarInt(body, anyProtocol)
	body = appendVarInt(body, int32(len(host)))
	body = append(body, host...)
	body = append(body, byte(port>>8), byte(port))
	body = appendVarInt(body, nextStateStatus)

	out := make([]byte, 0, 5+len(body)+2)
	out = appendVarInt(out, int32(len(body)))
	out = append(out, body...)
	// Status request packet: length 1, packet ID 0x00.
	out = append(out, 0x01, 0x00)
	return out
}

func isStatusResponse(r *bufio.Reader) bool {
	packetLen, err := readVarInt(r)
	if err != nil || packetLen < 2 || packetLen > maxPacketLength {
		return false
	}
	frame := make([]byte, packetLen)
	if _, err := io.ReadFull(r, frame); err != nil {
		return false
	}
	br := bytes.NewReader(frame)
	packetID, err := readVarInt(br)
	if err != nil || packetID != 0x00 {
		return false
	}
	jsonLen, err := readVarInt(br)
	if err != nil || jsonLen <= 0 || int(jsonLen) != br.Len() {
		return false
	}
	var payload map[string]json.RawMessage
	return json.Unmarshal(frame[len(frame)-br.Len():], &payload) == nil && payload != nil
}

func appendVarInt(dst []byte, value int32) []byte {
	u := uint32(value)
	for (u &^ 0x7F) != 0 {
		dst = append(dst, byte((u&0x7F)|0x80))
		u >>= 7
	}
	return append(dst, byte(u))
}

func readVarInt(r io.ByteReader) (int32, error) {
	var value uint32
	for shift := uint(0); shift < 35; shift += 7 {
		b, err := r.ReadByte()
		if err != nil {
			return 0, err
		}
		value |= uint32(b&0x7F) << shift
		if (b & 0x80) == 0 {
			return int32(value), nil
		}
	}
	return 0, errors.New("varint longer than five bytes")
}

var (
	_ caddy.Provisioner           = (*Handler)(nil)
	_ caddyhttp.MiddlewareHandler = (*Handler)(nil)
	_ caddyfile.Unmarshaler       = (*Handler)(nil)
)
