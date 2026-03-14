# MDropDX12 TCP Server Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a thin TCP listener + mDNS advertisement + device authorization to MDropDX12 so Android clients can connect directly without MWR_Web.

**Architecture:** A new `tcp_server.h/cpp` module runs alongside the existing named pipe server. It accepts TCP connections, handles length-prefixed UTF-8 framing, converts to UTF-16, and dispatches commands via `PostMessageW(hwnd, WM_MW_IPC_MESSAGE, ...)` — the same path used by the pipe server. For `SIGNAL|` messages, reuses the pipe server's signal dispatch table. Per-client response routing uses a "responding client" context so request/response commands (STATE, DIAG_MIRRORS, SHADER_*) reply only to the requester. Auth and device authorization are per-connection state. mDNS is registered via Windows DNS-SD APIs.

**Tech Stack:** C++17, Winsock2, Windows DNS-SD (`dnssd.h` / `DnsServiceRegister`), existing MDropDX12 build system.

**Spec:** `docs/design/2026-03-14-mdr-android-design.md`

---

## Chunk 1: TCP Listener Core

### Task 1: TCP Server Header

**Files:**
- Create: `src/mDropDX12/tcp_server.h`

- [ ] **Step 1: Create tcp_server.h with class declaration**

```cpp
#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <cstdint>

#pragma comment(lib, "ws2_32.lib")

enum class TcpAuthState { Unauthenticated, Pending, Authenticated };

struct TcpClientConnection {
    SOCKET socket = INVALID_SOCKET;
    TcpAuthState authState = TcpAuthState::Unauthenticated;
    std::string deviceId;
    std::string deviceName;
    ULONGLONG lastActivity = 0; // GetTickCount64
    std::vector<uint8_t> readBuffer;
    std::vector<uint8_t> writeBuffer; // Pending outgoing data for WOULDBLOCK handling
};

// Thread-local pointer to the TCP client that initiated the current command.
// Set before PostMessage dispatch, read by response-sending code to route
// replies to the requesting client instead of broadcasting.
extern thread_local TcpClientConnection* g_respondingTcpClient;

class TcpServer {
public:
    using MessageHandler = std::function<void(TcpClientConnection& client, const std::wstring& message)>;
    using AuthRequestHandler = std::function<void(TcpClientConnection& client, const std::string& pin,
                                                   const std::string& deviceId, const std::string& deviceName)>;

    TcpServer();
    ~TcpServer();

    bool Start(int port, MessageHandler onMessage, AuthRequestHandler onAuthRequest);
    void Stop();
    void Poll();  // Called from main loop — non-blocking select
    void Broadcast(const std::wstring& message);  // Send to all authenticated clients
    void SendTo(TcpClientConnection& client, const std::string& utf8Message);
    void SendTo(TcpClientConnection& client, const std::wstring& message);
    void ApproveDevice(const std::string& deviceId);
    void DenyDevice(const std::string& deviceId);
    void DisconnectDevice(const std::string& deviceId);
    bool IsRunning() const { return m_running.load(); }
    int GetPort() const { return m_port; }

private:
    void AcceptNewClients();
    void ReadFromClients();
    void ProcessFrames(TcpClientConnection& client);
    void RemoveClient(size_t index);
    void SendRaw(SOCKET sock, const uint8_t* data, int len);
    void CheckTimeouts();

    static std::string WideToUtf8(const std::wstring& wide);
    static std::wstring Utf8ToWide(const std::string& utf8);

    SOCKET m_listenSocket = INVALID_SOCKET;
    int m_port = 9270;
    std::atomic<bool> m_running{false};
    std::vector<TcpClientConnection> m_clients;
    std::mutex m_clientsMutex;
    MessageHandler m_onMessage;
    AuthRequestHandler m_onAuthRequest;

    static constexpr int RECV_BUFFER_SIZE = 65536;
    static constexpr ULONGLONG CLIENT_TIMEOUT_MS = 60000;
};
```

- [ ] **Step 2: Commit**

```bash
git add src/mDropDX12/tcp_server.h
git commit -m "feat: add TcpServer header with connection and auth types"
```

### Task 2: TCP Server Implementation — Startup & Accept

**Files:**
- Create: `src/mDropDX12/tcp_server.cpp`

- [ ] **Step 1: Implement constructor, destructor, Start, Stop, AcceptNewClients**

```cpp
#include "tcp_server.h"
#include <algorithm>

TcpServer::TcpServer() {
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);
}

TcpServer::~TcpServer() {
    Stop();
}

bool TcpServer::Start(int port, MessageHandler onMessage, AuthRequestHandler onAuthRequest) {
    if (m_running.load()) return false;
    m_port = port;
    m_onMessage = std::move(onMessage);
    m_onAuthRequest = std::move(onAuthRequest);

    m_listenSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (m_listenSocket == INVALID_SOCKET) return false;

    // Allow port reuse
    int opt = 1;
    setsockopt(m_listenSocket, SOL_SOCKET, SO_REUSEADDR, (const char*)&opt, sizeof(opt));

    // Non-blocking
    u_long mode = 1;
    ioctlsocket(m_listenSocket, FIONBIO, &mode);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons((u_short)port);

    if (bind(m_listenSocket, (sockaddr*)&addr, sizeof(addr)) == SOCKET_ERROR) {
        closesocket(m_listenSocket);
        m_listenSocket = INVALID_SOCKET;
        return false;
    }

    if (listen(m_listenSocket, SOMAXCONN) == SOCKET_ERROR) {
        closesocket(m_listenSocket);
        m_listenSocket = INVALID_SOCKET;
        return false;
    }

    m_running.store(true);
    return true;
}

void TcpServer::Stop() {
    m_running.store(false);
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (auto& c : m_clients) {
        if (c.socket != INVALID_SOCKET) closesocket(c.socket);
    }
    m_clients.clear();
    if (m_listenSocket != INVALID_SOCKET) {
        closesocket(m_listenSocket);
        m_listenSocket = INVALID_SOCKET;
    }
}

void TcpServer::AcceptNewClients() {
    sockaddr_in clientAddr{};
    int addrLen = sizeof(clientAddr);
    SOCKET clientSocket = accept(m_listenSocket, (sockaddr*)&clientAddr, &addrLen);
    if (clientSocket == INVALID_SOCKET) return;

    // Set non-blocking
    u_long mode = 1;
    ioctlsocket(clientSocket, FIONBIO, &mode);

    TcpClientConnection conn;
    conn.socket = clientSocket;
    conn.lastActivity = GetTickCount64();

    std::lock_guard<std::mutex> lock(m_clientsMutex);
    m_clients.push_back(std::move(conn));
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mDropDX12/tcp_server.cpp
git commit -m "feat: implement TCP server startup, shutdown, and client accept"
```

### Task 3: TCP Server Implementation — Framing & Read

**Files:**
- Modify: `src/mDropDX12/tcp_server.cpp`

- [ ] **Step 1: Implement ReadFromClients, ProcessFrames, encoding helpers**

```cpp
void TcpServer::ReadFromClients() {
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    uint8_t buf[RECV_BUFFER_SIZE];

    for (size_t i = 0; i < m_clients.size(); ) {
        auto& c = m_clients[i];
        int bytesRead = recv(c.socket, (char*)buf, RECV_BUFFER_SIZE, 0);

        if (bytesRead > 0) {
            c.lastActivity = GetTickCount64();
            c.readBuffer.insert(c.readBuffer.end(), buf, buf + bytesRead);
            ProcessFrames(c);
            ++i;
        } else if (bytesRead == 0) {
            // Connection closed
            RemoveClient(i);
        } else {
            int err = WSAGetLastError();
            if (err == WSAEWOULDBLOCK) {
                ++i;
            } else {
                RemoveClient(i);
            }
        }
    }
}

void TcpServer::ProcessFrames(TcpClientConnection& client) {
    while (client.readBuffer.size() >= 4) {
        // Read length prefix (little-endian uint32)
        uint32_t payloadLen = 0;
        memcpy(&payloadLen, client.readBuffer.data(), 4);

        // Sanity check: max 4MB message
        if (payloadLen > 4 * 1024 * 1024) {
            // Invalid frame, disconnect
            closesocket(client.socket);
            client.socket = INVALID_SOCKET;
            return;
        }

        if (client.readBuffer.size() < 4 + payloadLen) break; // Incomplete frame

        std::string utf8((char*)client.readBuffer.data() + 4, payloadLen);
        client.readBuffer.erase(client.readBuffer.begin(), client.readBuffer.begin() + 4 + payloadLen);

        // Handle AUTH specially
        if (utf8.rfind("AUTH|", 0) == 0) {
            // Parse: AUTH|<pin>|<device_id>|<device_name>
            // Split on '|'
            std::vector<std::string> parts;
            size_t start = 0;
            for (size_t pos = 0; pos <= utf8.size(); ++pos) {
                if (pos == utf8.size() || utf8[pos] == '|') {
                    parts.push_back(utf8.substr(start, pos - start));
                    start = pos + 1;
                }
            }
            if (parts.size() >= 4) {
                m_onAuthRequest(client, parts[1], parts[2], parts[3]);
            } else {
                SendTo(client, "AUTH_FAIL|MALFORMED");
            }
            continue;
        }

        // Drop all non-AUTH commands from unauthenticated clients
        if (client.authState != TcpAuthState::Authenticated) continue;

        // Handle PING (authenticated only — prevents service fingerprinting)
        if (utf8 == "PING") {
            SendTo(client, "PONG");
            continue;
        }

        // Convert to wide and dispatch via PostMessage (same path as pipe server)
        std::wstring wide = Utf8ToWide(utf8);
        if (!wide.empty() && m_onMessage) {
            m_onMessage(client, wide);
        }
    }
}

std::string TcpServer::WideToUtf8(const std::wstring& wide) {
    if (wide.empty()) return {};
    int len = WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), (int)wide.size(), nullptr, 0, nullptr, nullptr);
    std::string utf8(len, 0);
    WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), (int)wide.size(), &utf8[0], len, nullptr, nullptr);
    return utf8;
}

std::wstring TcpServer::Utf8ToWide(const std::string& utf8) {
    if (utf8.empty()) return {};
    int len = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), nullptr, 0);
    std::wstring wide(len, 0);
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), &wide[0], len);
    return wide;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mDropDX12/tcp_server.cpp
git commit -m "feat: implement length-prefixed framing, UTF-8 conversion, auth dispatch"
```

### Task 4: TCP Server Implementation — Send, Broadcast, Poll, Timeouts

**Files:**
- Modify: `src/mDropDX12/tcp_server.cpp`

- [ ] **Step 1: Implement remaining methods**

```cpp
void TcpServer::SendRaw(SOCKET sock, const uint8_t* data, int len) {
    // Temporarily set socket to blocking for sends to avoid
    // partial frame corruption from WSAEWOULDBLOCK on non-blocking sockets.
    u_long blocking = 0;
    ioctlsocket(sock, FIONBIO, &blocking);
    int sent = 0;
    while (sent < len) {
        int r = send(sock, (const char*)(data + sent), len - sent, 0);
        if (r == SOCKET_ERROR) break;
        sent += r;
    }
    u_long nonBlocking = 1;
    ioctlsocket(sock, FIONBIO, &nonBlocking);
}

void TcpServer::SendTo(TcpClientConnection& client, const std::string& utf8Message) {
    uint32_t payloadLen = (uint32_t)utf8Message.size();
    uint8_t header[4];
    memcpy(header, &payloadLen, 4); // LE on x86/x64
    SendRaw(client.socket, header, 4);
    SendRaw(client.socket, (const uint8_t*)utf8Message.data(), (int)payloadLen);
}

void TcpServer::SendTo(TcpClientConnection& client, const std::wstring& message) {
    SendTo(client, WideToUtf8(message));
}

void TcpServer::Broadcast(const std::wstring& message) {
    std::string utf8 = WideToUtf8(message);
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (auto& c : m_clients) {
        if (c.authState == TcpAuthState::Authenticated) {
            SendTo(c, utf8);
        }
    }
}

void TcpServer::Poll() {
    if (!m_running.load()) return;
    AcceptNewClients();
    ReadFromClients();
    CheckTimeouts();
}

void TcpServer::CheckTimeouts() {
    ULONGLONG now = GetTickCount64();
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (size_t i = 0; i < m_clients.size(); ) {
        if (now - m_clients[i].lastActivity > CLIENT_TIMEOUT_MS) {
            RemoveClient(i);
        } else {
            ++i;
        }
    }
}

void TcpServer::RemoveClient(size_t index) {
    // m_clientsMutex must be held
    if (index < m_clients.size()) {
        closesocket(m_clients[index].socket);
        m_clients.erase(m_clients.begin() + index);
    }
}

void TcpServer::ApproveDevice(const std::string& deviceId) {
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (auto& c : m_clients) {
        if (c.deviceId == deviceId && c.authState == TcpAuthState::Pending) {
            c.authState = TcpAuthState::Authenticated;
            SendTo(c, "AUTH_OK");
            break;
        }
    }
}

void TcpServer::DenyDevice(const std::string& deviceId) {
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (size_t i = 0; i < m_clients.size(); ++i) {
        if (m_clients[i].deviceId == deviceId && m_clients[i].authState == TcpAuthState::Pending) {
            SendTo(m_clients[i], "AUTH_FAIL|DENIED");
            RemoveClient(i);
            break;
        }
    }
}

void TcpServer::DisconnectDevice(const std::string& deviceId) {
    std::lock_guard<std::mutex> lock(m_clientsMutex);
    for (size_t i = 0; i < m_clients.size(); ++i) {
        if (m_clients[i].deviceId == deviceId) {
            RemoveClient(i);
            break;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mDropDX12/tcp_server.cpp
git commit -m "feat: implement TCP broadcast, send, poll, timeouts, device approve/deny"
```

## Chunk 2: Integration, Auth, mDNS

### Task 5: Device Authorization Storage

**Files:**
- Modify: `src/mDropDX12/tcp_server.h` (add device storage methods)
- Modify: `src/mDropDX12/tcp_server.cpp`

- [ ] **Step 1: Add device persistence to TcpServer**

Add to `tcp_server.h`:
```cpp
struct AuthorizedDevice {
    std::string id;
    std::string name;
    std::string dateAdded; // YYYY-MM-DD
};

// In TcpServer class:
void LoadAuthorizedDevices(const std::wstring& iniPath);
void SaveAuthorizedDevices(const std::wstring& iniPath);
bool IsDeviceAuthorized(const std::string& deviceId) const;
void AddAuthorizedDevice(const std::string& id, const std::string& name);
void RemoveAuthorizedDevice(const std::string& id);
std::vector<AuthorizedDevice> GetAuthorizedDevices() const;

private:
    std::vector<AuthorizedDevice> m_authorizedDevices;
    std::wstring m_iniPath;
```

- [ ] **Step 2: Implement device persistence using INI read/write**

Use `GetPrivateProfileString` / `WritePrivateProfileString` for INI persistence under `[AuthorizedDevices]` section.

- [ ] **Step 3: Commit**

```bash
git add src/mDropDX12/tcp_server.h src/mDropDX12/tcp_server.cpp
git commit -m "feat: add authorized device persistence via INI"
```

### Task 6: Integration with MDropDX12 Main Loop

**Files:**
- Modify: The file containing the main render/message loop (likely `engine_messages.cpp` or main app file)
- Modify: `pipe_server.cpp` (to broadcast outgoing messages to TCP clients too)

- [ ] **Step 1: Instantiate TcpServer in app initialization**

**IMPORTANT:** MDropDX12 dispatches IPC messages via `PostMessageW(hwnd, WM_MW_IPC_MESSAGE, 1, (LPARAM)heapCopy)`. The render thread processes them in `engine_messages.cpp`. The TCP server must use the same path — never call the message handler directly (it touches render-thread-only state).

For `SIGNAL|` prefixed messages, reuse the pipe server's `DispatchSignal()` logic (extract it as a shared static/free function).

```cpp
#include "tcp_server.h"
#include "pipe_server.h"  // For DispatchSignal / signal table

thread_local TcpClientConnection* g_respondingTcpClient = nullptr;

// In app init:
TcpServer g_tcpServer;
g_tcpServer.LoadAuthorizedDevices(iniPath);

int tcpPort = GetPrivateProfileInt(L"Network", L"TcpPort", 9270, iniPath);
bool tcpEnabled = GetPrivateProfileInt(L"Network", L"TcpEnabled", 1, iniPath) != 0;
if (!tcpEnabled) return; // Skip TCP server startup

g_tcpServer.Start(tcpPort,
    // onMessage: dispatch via PostMessage (same path as pipe server)
    [](TcpClientConnection& client, const std::wstring& msg) {
        // Set responding client context for per-client response routing
        g_respondingTcpClient = &client;

        if (msg.find(L"SIGNAL|") == 0) {
            // Use pipe server's signal dispatch (PostMessage with WM_APP offsets)
            PipeServer::DispatchSignal(g_hwndRender, msg);
        } else {
            // Heap-allocate wide string copy, post to render thread
            wchar_t* copy = new wchar_t[msg.size() + 1];
            wcscpy_s(copy, msg.size() + 1, msg.c_str());
            PostMessageW(g_hwndRender, WM_MW_IPC_MESSAGE, 1, (LPARAM)copy);
        }
    },
    // onAuthRequest: check PIN hash and device authorization
    [](TcpClientConnection& client, const std::string& pin,
       const std::string& deviceId, const std::string& deviceName) {
        // Check PIN (skip if no PIN configured)
        if (g_pinConfigured && !VerifyPinHash(pin)) {
            g_tcpServer.SendTo(client, "AUTH_FAIL|BAD_PIN");
            return;
        }
        client.deviceId = deviceId;
        client.deviceName = deviceName;
        if (g_tcpServer.IsDeviceAuthorized(deviceId)) {
            client.authState = TcpAuthState::Authenticated;
            g_tcpServer.SendTo(client, "AUTH_OK");
        } else {
            client.authState = TcpAuthState::Pending;
            g_tcpServer.SendTo(client, "AUTH_PENDING");
            ShowDeviceApprovalDialog(deviceId, deviceName);
        }
    }
);
```

**PIN hash storage:** Add `[Network] PinHash=<sha256>` to INI. `VerifyPinHash(pin)` computes SHA256 of the input and compares to stored hash. If `PinHash` is empty, `g_pinConfigured = false` and PIN check is skipped (still requires device authorization).


- [ ] **Step 2: Add `g_tcpServer.Poll()` call to main loop**

Insert into the existing frame/message loop where pipe server is polled:
```cpp
// In main loop, alongside pipe polling:
g_tcpServer.Poll();
```

- [ ] **Step 3: Hook outgoing messages for TCP — broadcast vs per-client routing**

Two categories of outgoing messages:

**Broadcasts (all authenticated clients):** `TRACK|`, `PRESET=` — events that happen independently.
At existing broadcast sites, add:
```cpp
g_tcpServer.Broadcast(outgoingMessage);
```

**Per-client responses:** `OPACITY=`, `SETTINGS|`, `WAVE|` (from STATE), `MIRRORS|`, `SHADER_*_RESULT=` — replies to a specific request.
At these response sites, check `g_respondingTcpClient`:
```cpp
if (g_respondingTcpClient) {
    g_tcpServer.SendTo(*g_respondingTcpClient, responseMessage);
} else {
    // Response from pipe client or internal — broadcast to pipes only (existing behavior)
    g_pipeServer.Send(responseMessage);
}
// Always broadcast to pipes too (existing behavior unchanged)
```

**Note:** `g_respondingTcpClient` is set per-command in the onMessage handler and is valid during synchronous processing on the render thread. For async responses (e.g., shader compilation that completes later), store the client socket/ID with the pending operation.

- [ ] **Step 4: Commit**

```bash
git add src/mDropDX12/tcp_server.h src/mDropDX12/tcp_server.cpp <modified_files>
git commit -m "feat: integrate TCP server into MDropDX12 main loop"
```

### Task 7: Device Approval Dialog

**Files:**
- Modify: UI code (ImGui or Win32 dialog, depending on MDropDX12's UI framework)

- [ ] **Step 1: Implement approval dialog/toast**

When `AUTH_PENDING` is triggered, show a dialog or ImGui popup:
- Title: "New Device Connection"
- Message: "'Shane's Pixel 8' wants to connect. Allow?"
- Buttons: [Allow] [Deny]
- Allow → calls `g_tcpServer.ApproveDevice(deviceId)` + `g_tcpServer.AddAuthorizedDevice(id, name)` + `g_tcpServer.SaveAuthorizedDevices(iniPath)`
- Deny → calls `g_tcpServer.DenyDevice(deviceId)`

- [ ] **Step 2: Add device management list to Settings/Displays window**

Show list of authorized devices with "Remove" button per entry. Remove calls `g_tcpServer.RemoveAuthorizedDevice(id)` + `g_tcpServer.DisconnectDevice(id)` + save.

- [ ] **Step 3: Commit**

```bash
git add <modified_files>
git commit -m "feat: add device approval dialog and authorized device management UI"
```

### Task 8: mDNS Advertisement

**Files:**
- Create: `src/mDropDX12/mdns_advertiser.h`
- Create: `src/mDropDX12/mdns_advertiser.cpp`

- [ ] **Step 1: Create mDNS advertiser using Windows DNS-SD**

```cpp
// mdns_advertiser.h
#pragma once
#include <windns.h>
#include <string>

#pragma comment(lib, "dnsapi.lib")

class MdnsAdvertiser {
public:
    bool Register(const std::string& serviceName, int port, int pid);
    void Unregister();
    ~MdnsAdvertiser() { Unregister(); }
private:
    DNS_SERVICE_INSTANCE* m_instance = nullptr;
    DNS_SERVICE_REGISTER_REQUEST m_request{};
    bool m_registered = false;
};
```

- [ ] **Step 2: Implement Register/Unregister**

Uses `DnsServiceRegister` to advertise `_milkwave._tcp.local` with TXT records for version and PID.

- [ ] **Step 3: Integrate into app startup/shutdown**

```cpp
MdnsAdvertiser g_mdns;
// After TCP server starts:
// Service name includes hostname for multi-machine distinguishability
char hostname[256] = {};
gethostname(hostname, sizeof(hostname));
g_mdns.Register(std::string("MDropDX12-") + hostname, g_tcpServer.GetPort(), GetCurrentProcessId());
// On shutdown:
g_mdns.Unregister();
```

- [ ] **Step 4: Commit**

```bash
git add src/mDropDX12/mdns_advertiser.h src/mDropDX12/mdns_advertiser.cpp <modified_files>
git commit -m "feat: add mDNS service advertisement for _milkwave._tcp"
```

### Task 9: DEAUTH_DEVICE and LIST_DEVICES Pipe Commands

**Files:**
- Modify: `src/mDropDX12/engine_messages.cpp`

- [ ] **Step 1: Add DEAUTH_DEVICE handler to pipe message processing**

In `engine_messages.cpp`, add handler for `DEAUTH_DEVICE|<device_id>`:
```cpp
if (wcsncmp(msg, L"DEAUTH_DEVICE|", 14) == 0) {
    std::string deviceId = WideToUtf8(msg + 14);
    g_tcpServer.RemoveAuthorizedDevice(deviceId);
    g_tcpServer.DisconnectDevice(deviceId);
    g_tcpServer.SaveAuthorizedDevices(iniPath);
    SendPipeResponse(L"DEAUTH_OK");
    return;
}
```

- [ ] **Step 2: Add LIST_DEVICES handler**

```cpp
if (wcscmp(msg, L"LIST_DEVICES") == 0) {
    auto devices = g_tcpServer.GetAuthorizedDevices();
    std::wstring response = L"DEVICES";
    for (auto& d : devices) {
        response += L"|id=" + Utf8ToWide(d.id) + L",name=" + Utf8ToWide(d.name)
                  + L",added=" + Utf8ToWide(d.dateAdded);
    }
    SendPipeResponse(response);
    return;
}
```

**Note:** These commands are only processed from local pipe clients, never from TCP (the TCP server drops unrecognized commands that don't match its whitelist).

- [ ] **Step 3: Commit**

```bash
git add src/mDropDX12/engine_messages.cpp
git commit -m "feat: add DEAUTH_DEVICE and LIST_DEVICES pipe commands"
```

### Task 10: Add TCP port to build configuration

**Files:**
- Modify: Settings/INI handling code

- [ ] **Step 1: Add TCP settings to INI**

```ini
[Network]
TcpPort=9270
TcpEnabled=1
PinHash=
```

- [ ] **Step 2: Read TcpEnabled on startup, conditionally start TCP server**

```cpp
int tcpPort = GetPrivateProfileInt(L"Network", L"TcpPort", 9270, iniPath);
bool tcpEnabled = GetPrivateProfileInt(L"Network", L"TcpEnabled", 1, iniPath) != 0;
if (tcpEnabled) {
    g_tcpServer.Start(tcpPort, onMessage, onAuth);
    g_mdns.Register(...);
}
```

- [ ] **Step 3: Add WSACleanup to shutdown path**

In `TcpServer::Stop()`, add `WSACleanup()` after closing all sockets.

- [ ] **Step 4: Add PIN hash helpers**

```cpp
// Compute SHA256 of pin string, compare to stored PinHash from INI
bool VerifyPinHash(const std::string& pin) {
    // Use Windows CNG (BCryptHashData) to compute SHA256
    // Compare hex result to g_pinHash
}
```

- [ ] **Step 5: Add max client limit (16)**

In `AcceptNewClients()`, reject connections when `m_clients.size() >= MAX_CLIENTS`.

- [ ] **Step 6: Add logging**

Use same logging pattern as pipe server (`PipeLog()` equivalent) for:
- Connection accepted/closed
- Auth attempts (success/fail/pending)
- Device authorization changes

- [ ] **Step 7: Shutdown ordering — unregister mDNS before stopping TCP**

```cpp
// On shutdown:
g_mdns.Unregister();    // Stop advertising first
g_tcpServer.Stop();      // Then close connections
```

- [ ] **Step 8: Commit**

```bash
git add <modified_files>
git commit -m "feat: add TCP config, PIN hash, max clients, logging, graceful shutdown"
```
