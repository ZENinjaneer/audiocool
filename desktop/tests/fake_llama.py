"""Stands in for llama.cpp's server in tests: fake_llama.py PORT [die].

Says it's loading for a moment, then answers chat completions with "Reply to: <the prompt's start>",
and keeps each request in requests.jsonl beside its log.
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(sys.argv[1])
if len(sys.argv) > 2 and sys.argv[2] == "die":
    print("error: out of memory loading the model", flush=True)
    sys.exit(1)
STARTED = time.monotonic()
LOG = Path(sys.argv[3]) if len(sys.argv) > 3 else None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            loading = time.monotonic() - STARTED < 0.3
            self.send(503 if loading else 200, {"status": "loading model" if loading else "ok"})
        else:
            self.send(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if LOG:
            with open(LOG, "a") as f:
                f.write(json.dumps(body) + "\n")
        prompt = body["messages"][0]["content"]
        self.send(200, {"choices": [{"message": {"role": "assistant", "content": f"Reply to: {prompt[:30]}"}}]})


print("listening", flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
