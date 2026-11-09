// Package main builds awgnetstack.dll: the WWG entry layer for Windows.
//
// The entry AmneziaWG device runs on an in-process gVisor netstack instead of a
// Windows adapter, and a loopback UDP relay forwards the exit layer's datagrams
// into that netstack. This mirrors the Android libwg-go extension, where the
// same netstack+relay pair keeps the entry layer out of the OS routing table.
package main

import "C"

import (
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"unsafe"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

const maxLogLines = 512

var (
	logMu   sync.Mutex
	logLine []string

	handlesMu sync.Mutex
	handles   = make(map[int32]*tunnelHandle)
)

type tunnelHandle struct {
	wgDevice *device.Device
	relay    *udpRelay
}

func logf(format string, args ...interface{}) {
	line := fmt.Sprintf(format, args...)
	logMu.Lock()
	logLine = append(logLine, line)
	if len(logLine) > maxLogLines {
		logLine = logLine[len(logLine)-maxLogLines:]
	}
	logMu.Unlock()
}

func newLogger(interfaceName string) *device.Logger {
	prefix := interfaceName + ": "
	return &device.Logger{
		Verbosef: func(format string, args ...interface{}) {
			logf(prefix+format, args...)
		},
		Errorf: func(format string, args ...interface{}) {
			logf(prefix+format, args...)
		},
	}
}

func storeTunnelHandle(handle *tunnelHandle) int32 {
	handlesMu.Lock()
	defer handlesMu.Unlock()

	for i := int32(0); i < (1<<31)-1; i++ {
		if _, exists := handles[i]; !exists {
			handles[i] = handle
			return i
		}
	}
	return -1
}

func takeTunnelHandle(tunnelHandle int32) *tunnelHandle {
	handlesMu.Lock()
	defer handlesMu.Unlock()

	handle, ok := handles[tunnelHandle]
	if !ok {
		return nil
	}
	delete(handles, tunnelHandle)
	return handle
}

func peekTunnelHandle(tunnelHandle int32) *tunnelHandle {
	handlesMu.Lock()
	defer handlesMu.Unlock()

	return handles[tunnelHandle]
}

func parseAddresses(raw string) ([]netip.Addr, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, nil
	}

	parts := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\t' || r == '\n' || r == '\r'
	})
	addresses := make([]netip.Addr, 0, len(parts))
	for _, part := range parts {
		if prefix, err := netip.ParsePrefix(part); err == nil {
			addresses = append(addresses, prefix.Addr())
			continue
		}
		address, err := netip.ParseAddr(part)
		if err != nil {
			return nil, fmt.Errorf("invalid address %q: %w", part, err)
		}
		addresses = append(addresses, address)
	}
	return addresses, nil
}

//export wwgNetstackStart
func wwgNetstackStart(interfaceName, localAddresses, dnsServers *C.char, mtu C.int,
	settings, relayHost *C.char, relayPort, bindInterfaceIndex C.int) C.int {

	name := C.GoString(interfaceName)
	logger := newLogger(name)

	addresses, err := parseAddresses(C.GoString(localAddresses))
	if err != nil || len(addresses) == 0 {
		logf("Parse entry local addresses: %v", err)
		return -1
	}
	dns, err := parseAddresses(C.GoString(dnsServers))
	if err != nil {
		logf("Parse entry DNS addresses: %v", err)
		return -1
	}
	if mtu < 576 || mtu > 65535 {
		logf("Invalid entry MTU: %d", int(mtu))
		return -1
	}

	tunDevice, tnet, err := netstack.CreateNetTUN(addresses, dns, int(mtu))
	if err != nil {
		logf("CreateNetTUN: %v", err)
		return -1
	}

	bind := conn.NewStdNetBind()
	wgDevice := device.NewDevice(tunDevice, bind, logger)
	if err = wgDevice.IpcSet(C.GoString(settings)); err != nil {
		logf("IpcSet: %v", err)
		wgDevice.Close()
		return -1
	}
	wgDevice.DisableSomeRoamingForBrokenMobileSemantics()
	if err = wgDevice.Up(); err != nil {
		logf("Unable to bring up entry device: %v", err)
		wgDevice.Close()
		return -1
	}

	// The exit layer installs a default route through the Windows adapter, so
	// the entry sockets have to stay attached to the physical interface that
	// reaches the entry endpoint. Without this the chain feeds itself.
	if bindInterfaceIndex > 0 {
		binder, ok := bind.(conn.BindSocketToInterface)
		if !ok {
			logf("Entry socket interface binding is unsupported")
		} else {
			if err = binder.BindSocketToInterface4(uint32(bindInterfaceIndex), false); err != nil {
				logf("Bind entry IPv4 socket to interface %d: %v", int(bindInterfaceIndex), err)
				wgDevice.Close()
				return -1
			}
			// An entry endpoint may be reached over IPv6, so the second family
			// must be pinned as well. Machines without IPv6 simply error here.
			if err = binder.BindSocketToInterface6(uint32(bindInterfaceIndex), false); err != nil {
				logf("Entry IPv6 socket stays unpinned on interface %d: %v", int(bindInterfaceIndex), err)
			}
		}
	}

	relay, err := startUDPRelay(tnet, C.GoString(relayHost), int32(relayPort), logger)
	if err != nil {
		logf("Start UDP relay: %v", err)
		wgDevice.Close()
		return -1
	}

	handle := &tunnelHandle{wgDevice: wgDevice, relay: relay}
	i := storeTunnelHandle(handle)
	if i < 0 {
		relay.Close()
		wgDevice.Close()
		return -1
	}
	logger.Verbosef("Entry device started with UDP relay on 127.0.0.1:%d", relay.Port())
	return C.int(i)
}

//export wwgNetstackRelayPort
func wwgNetstackRelayPort(tunnelHandle C.int) C.int {
	handle := peekTunnelHandle(int32(tunnelHandle))
	if handle == nil || handle.relay == nil {
		return -1
	}
	return C.int(handle.relay.Port())
}

//export wwgNetstackIsHealthy
func wwgNetstackIsHealthy(tunnelHandle C.int) C.int {
	handle := peekTunnelHandle(int32(tunnelHandle))
	if handle == nil || handle.relay == nil || !handle.relay.healthy.Load() {
		return 0
	}
	if _, err := handle.wgDevice.IpcGet(); err != nil {
		return 0
	}
	return 1
}

//export wwgNetstackStop
func wwgNetstackStop(tunnelHandle C.int) {
	handle := takeTunnelHandle(int32(tunnelHandle))
	if handle == nil {
		return
	}
	if handle.relay != nil {
		handle.relay.Close()
	}
	handle.wgDevice.Close()
}

//export wwgNetstackDrainLog
func wwgNetstackDrainLog(buf *C.char, bufLen C.int) C.int {
	if buf == nil || bufLen <= 1 {
		return 0
	}

	logMu.Lock()
	text := strings.Join(logLine, "\n")
	logLine = nil
	logMu.Unlock()

	out := []byte(text)
	if len(out) > int(bufLen)-1 {
		out = out[len(out)-int(bufLen)+1:]
	}

	dst := unsafe.Slice((*byte)(unsafe.Pointer(buf)), int(bufLen))
	n := copy(dst, out)
	dst[n] = 0
	return C.int(n)
}

func main() {}
