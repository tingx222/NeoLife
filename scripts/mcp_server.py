"""MCP 2025-03-26 stdio adapter. Requires a user login token, NOT the DeepSeek key.
Set HMDP_MCP_URL (default localhost:8080/api/mcp) and HMDP_USER_TOKEN.
Only JSON-RPC is written to stdout. No third-party dependencies.
"""
import json
import os
import sys
import urllib.request
import urllib.parse
sys.stdin.reconfigure(encoding='utf-8')
sys.stdout.reconfigure(encoding='utf-8')
url = os.getenv('HMDP_MCP_URL', 'http://127.0.0.1:8080/api/mcp')
token = os.getenv('HMDP_USER_TOKEN', '')
if urllib.parse.urlparse(url).hostname not in ('localhost', '127.0.0.1'):
    raise SystemExit('This local bridge only permits loopback URLs')
for line in sys.stdin:
    request = None
    try:
        request = json.loads(line)
        if not isinstance(request, dict):
            raise ValueError('single JSON-RPC object required')
        if not token:
            raise RuntimeError('login token missing')
        headers = {'Content-Type': 'application/json', 'authorization': token}
        payload = json.dumps(request, ensure_ascii=False).encode('utf-8')
        with urllib.request.urlopen(urllib.request.Request(url, payload, headers), timeout=30) as response:
            raw = response.read()
        if raw and 'id' in request:
            print(json.dumps(json.loads(raw), ensure_ascii=False), flush=True)
    except Exception:
        if isinstance(request, dict) and 'id' not in request:
            continue
        print(json.dumps({'jsonrpc': '2.0', 'id': request.get('id') if isinstance(request, dict) else None,
                          'error': {'code': -32603, 'message': 'Bridge unavailable: check login token and backend'}}), flush=True)
