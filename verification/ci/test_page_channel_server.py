"""Serve the real loopback fixture with reverse DNS unavailable or blocked."""
import base64
from http.client import HTTPConnection
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import time


SCRIPT = Path(__file__).resolve().parents[2] / 'ios/Tests/page-channel-server.py'
BOOTSTRAP = '''import runpy, socket, sys, threading
mode, script, port_file = sys.argv[1:]
def reverse_dns(name):
    if mode == 'unavailable':
        raise AssertionError('Loopback fixture must not resolve reverse DNS')
    threading.Event().wait()
socket.getfqdn = reverse_dns
sys.argv = [script, port_file]
runpy.run_path(script, run_name='__main__')
'''
with TemporaryDirectory() as directory:
    port_file = Path(directory) / 'page-server.port'
    for mode in ('unavailable', 'blocked'):
        # Reuse the same port-file path, as the sequential Release/Debug wire runs do.
        port_file.unlink(missing_ok=True)
        process = subprocess.Popen([sys.executable, '-c', BOOTSTRAP, mode, str(SCRIPT), str(port_file)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and process.poll() is None:
                if port_file.exists() and port_file.stat().st_size:
                    break
                time.sleep(0.01)
            assert port_file.exists() and port_file.stat().st_size, f'{mode}: loopback fixture never became ready'
            port = int(port_file.read_text())
            assert 0 < port < 65536
            for path, status, content_type in [
                ('/page', 200, 'text/html; charset=utf-8'),
                ('/other', 200, 'text/html; charset=utf-8'),
                ('/missing', 404, 'text/html; charset=utf-8'),
                ('/mixed-image?nonce=1', 200, 'image/gif'),
            ]:
                connection = HTTPConnection('127.0.0.1', port, timeout=2)
                try:
                    connection.request('GET', path)
                    response = connection.getresponse()
                    body = response.read()
                    assert response.status == status, (mode, path, response.status)
                    assert response.getheader('Content-Type') == content_type
                    assert int(response.getheader('Content-Length')) == len(body)
                    if content_type == 'image/gif':
                        assert body == base64.b64decode('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7')
                    else:
                        assert b'window.contentScriptExecuted=true' in body
                finally:
                    connection.close()
        finally:
            process.terminate()
            stdout, stderr = process.communicate(timeout=5)
        assert not stdout
        for phase in ('importing HTTP modules', 'binding 127.0.0.1', 'ready on 127.0.0.1:'):
            assert 'wire fixture: ' + phase in stderr, (mode, stderr)

print('PASS: real loopback fixture starts without reverse DNS, including sequential restart; HTTP 200/404, HTML and GIF contracts passed in unavailable/blocked DNS cases.')
