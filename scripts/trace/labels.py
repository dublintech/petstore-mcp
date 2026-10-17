"""mitmproxy addon: say which process sent each request, as the flow's comment.

When a client connects, Windows still knows which process owns the client's end of the connection (its source
port), so the addon looks that up once per connection: the browser, the gateway, petstore-auth, or mcp-remote.

Claude Desktop runs several mcp-remote processes, which look identical from the outside. Each one names its MCP
client in the initialize message (clientInfo.name, e.g. "claude-ai"), so once that has passed, every flow from that
process, earlier ones included, is labelled with the name too.
"""

import json
import os

import psutil
from mitmproxy import connection, ctx, hooks, http

IGNORED_CLIENT_NAMES = {"mcp-remote-fallback-test"}  # mcp-remote's own transport test, not a real client


class Labels:

    def __init__(self) -> None:
        self.pid_by_client: dict[str, int | None] = {}
        self.client_name_by_pid: dict[int, str] = {}
        self.flows_by_pid: dict[int, list[http.HTTPFlow]] = {}

    def client_connected(self, client: connection.Client) -> None:
        port = client.peername[1]
        pid = next((c.pid for c in psutil.net_connections("tcp")
                    if c.laddr and c.laddr.port == port and c.pid != os.getpid()), None)
        self.pid_by_client[client.id] = pid

    def client_disconnected(self, client: connection.Client) -> None:
        self.pid_by_client.pop(client.id, None)

    def request(self, flow: http.HTTPFlow) -> None:
        pid = self.pid_by_client.get(flow.client_conn.id)
        if pid is None:
            flow.comment = "unknown process"
            return
        self.flows_by_pid.setdefault(pid, []).append(flow)

        name = self._client_name(flow)
        if name and pid not in self.client_name_by_pid:
            self.client_name_by_pid[pid] = name
            earlier = self.flows_by_pid[pid]
            for f in earlier:
                f.comment = self._label(pid)
            ctx.master.addons.trigger(hooks.UpdateHook(earlier))
        flow.comment = self._label(pid)

    def _label(self, pid: int) -> str:
        label = describe(pid)
        name = self.client_name_by_pid.get(pid)
        return f"{label} [{name}] pid {pid}" if name else f"{label} pid {pid}"

    @staticmethod
    def _client_name(flow: http.HTTPFlow) -> str | None:
        if flow.request.method != "POST" or b'"initialize"' not in (flow.request.content or b""):
            return None
        try:
            name = json.loads(flow.request.content)["params"]["clientInfo"]["name"]
        except (ValueError, KeyError, TypeError):
            return None
        name = name.split(" (via ")[0]
        return None if name in IGNORED_CLIENT_NAMES else name


def describe(pid: int) -> str:
    try:
        process = psutil.Process(pid)
        exe = process.name().lower()
        cmdline = " ".join(process.cmdline())
    except psutil.Error:
        return "exited process"
    if "mcp-remote" in cmdline:
        return "mcp-remote"
    for jar, label in (("petstore-mcp", "gateway"), ("petstore-auth", "petstore-auth"), ("petstore-api", "petstore-api")):
        if jar in cmdline:
            return label
    if exe in ("msedge.exe", "chrome.exe", "firefox.exe", "brave.exe"):
        return f"browser ({exe.removesuffix('.exe')})"
    return exe


addons = [Labels()]
