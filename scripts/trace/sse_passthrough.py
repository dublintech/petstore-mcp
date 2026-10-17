"""mitmproxy addon: let Server-Sent Events through, and still record them.

mitmproxy holds back a response until its body is complete, and an MCP event stream (GET /mcp, or a POST answered
with text/event-stream) may stay open for minutes, so the client would see nothing. Streaming such responses fixes
that, but a streamed body isn't kept, so this addon copies each chunk as it passes and puts the whole body back on
the recorded flow when the stream closes. Each event is also written to the event log as it arrives.
"""

import logging

from mitmproxy import http


class SsePassthrough:

    def responseheaders(self, flow: http.HTTPFlow) -> None:
        if not flow.response.headers.get("content-type", "").startswith("text/event-stream"):
            return
        chunks: list[bytes] = []
        flow.metadata["sse_chunks"] = chunks

        def record(chunk: bytes) -> bytes:
            if chunk:
                chunks.append(chunk)
                logging.info("SSE %s %s: %s", flow.request.method, flow.request.path,
                             " | ".join(chunk.decode("utf-8", "replace").strip().splitlines()))
            return chunk

        flow.response.stream = record

    def response(self, flow: http.HTTPFlow) -> None:
        chunks = flow.metadata.pop("sse_chunks", None)
        if chunks is not None:
            # Already sent to the client; this only fills in what the UI shows.
            flow.response.content = b"".join(chunks)


addons = [SsePassthrough()]
