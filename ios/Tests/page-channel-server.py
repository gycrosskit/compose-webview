#!/usr/bin/env python3
"""Short-lived loopback pages for the actual WKWebView history check."""
import sys

print("wire fixture: importing HTTP modules", file=sys.stderr, flush=True)
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from socketserver import TCPServer
import base64


class PageHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"<html><body>page message history fixture<script>window.contentScriptExecuted=true</script></body></html>"
        image = self.path.startswith("/mixed-image")
        if image: body = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        self.send_response(200 if image or self.path in ("/page", "/other") else 404)
        self.send_header("Content-Type", "image/gif" if image else "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


print("wire fixture: binding 127.0.0.1", file=sys.stderr, flush=True)
# 固定 loopback fixture 无需 HTTPServer.server_bind 的反向 DNS 查询。
server = HTTPServer(("127.0.0.1", 0), PageHandler, bind_and_activate=False)
TCPServer.server_bind(server)
server.server_name = "127.0.0.1"
server.server_port = server.server_address[1]
server.server_activate()
Path(sys.argv[1]).write_text(str(server.server_port))
print(f"wire fixture: ready on 127.0.0.1:{server.server_port}", file=sys.stderr, flush=True)
server.serve_forever()
