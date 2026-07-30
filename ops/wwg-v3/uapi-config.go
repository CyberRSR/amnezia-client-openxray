package main

import (
	"bufio"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

func main() {
	socketPath := flag.String("socket", "/var/run/amneziawg/awg0.sock", "AmneziaWG UAPI socket")
	headerKey := flag.String("header-protection-key", "", "32-byte header protection key in hex")
	contentPadding := flag.String("content-padding-addition", "", "optional AWG3 uint32 range")
	rekeyAfter := flag.String("rekey-after-time", "", "optional AWG3 uint32 range")
	rekeyTimeout := flag.String("rekey-timeout", "", "optional AWG3 uint32 range")
	rejectAfter := flag.String("reject-after-time", "", "optional AWG3 uint32 range")
	keepaliveTimeout := flag.String("keepalive-timeout", "", "optional AWG3 uint32 range")
	maxHandshakeAttempts := flag.String("max-handshake-attempts", "", "optional AWG3 uint32 range")
	timeout := flag.Duration("timeout", 5*time.Second, "connect and response timeout")
	flag.Parse()

	if err := validateHeaderKey(*headerKey); err != nil {
		fatal(err)
	}

	fields := [][2]string{
		{"header_protection_key", *headerKey},
		{"content_padding_addition", *contentPadding},
		{"rekey_after_time", *rekeyAfter},
		{"rekey_timeout", *rekeyTimeout},
		{"reject_after_time", *rejectAfter},
		{"keepalive_timeout", *keepaliveTimeout},
		{"max_handshake_attempts", *maxHandshakeAttempts},
	}
	lines := []string{"set=1"}
	for _, field := range fields {
		if field[1] != "" {
			lines = append(lines, field[0]+"="+field[1])
		}
	}

	if _, err := sendRequest(*socketPath, *timeout, lines); err != nil {
		fatal(err)
	}

	values, err := sendRequest(*socketPath, *timeout, []string{"get=1"})
	if err != nil {
		fatal(err)
	}
	if !strings.EqualFold(values["header_protection_key"], *headerKey) {
		fatal(errors.New("header protection key verification failed"))
	}
}

func sendRequest(socketPath string, timeout time.Duration, lines []string) (map[string]string, error) {
	conn, err := net.DialTimeout("unix", socketPath, timeout)
	if err != nil {
		return nil, fmt.Errorf("connect UAPI socket: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	if _, err := fmt.Fprint(conn, strings.Join(lines, "\n")+"\n\n"); err != nil {
		return nil, fmt.Errorf("write UAPI request: %w", err)
	}

	values := make(map[string]string)
	foundErrno := false
	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			break
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			return nil, fmt.Errorf("invalid UAPI response line %q", line)
		}
		values[key] = value
		if key == "errno" {
			foundErrno = true
			errno, parseErr := strconv.Atoi(value)
			if parseErr != nil {
				return nil, fmt.Errorf("invalid UAPI response %q", line)
			}
			if errno != 0 {
				return nil, fmt.Errorf("UAPI returned errno=%d", errno)
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read UAPI response: %w", err)
	}
	if !foundErrno {
		return nil, errors.New("UAPI response did not contain errno")
	}
	return values, nil
}

func validateHeaderKey(value string) error {
	if value == "" {
		return errors.New("header protection key is required")
	}
	decoded, err := hex.DecodeString(value)
	if err != nil {
		return fmt.Errorf("header protection key must be hexadecimal: %w", err)
	}
	if len(decoded) != 32 {
		return fmt.Errorf("header protection key must decode to 32 bytes, got %d", len(decoded))
	}
	return nil
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "awg-uapi-config:", err)
	os.Exit(1)
}
