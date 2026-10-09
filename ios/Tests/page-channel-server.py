#!/usr/bin/env python3
"""Short-lived loopback pages for the actual WKWebView history check."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys
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


server = HTTPServer(("127.0.0.1", 0), PageHandler)
Path(sys.argv[1]).write_text(str(server.server_port))
server.serve_forever()
