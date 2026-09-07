#!/usr/bin/env python3
"""
webhook-logger: Simple HTTP server that receives Alertmanager webhook POSTs
and prints alert details to stdout (visible via: docker logs webhook-logger -f)

IN PRODUCTION: Replace this with a real notification integration:
  - Slack:      configure slack_configs in alertmanager.yml
  - PagerDuty:  configure pagerduty_configs in alertmanager.yml
  - MS Teams:   use prometheus-msteams bridge container
"""

import http.server
import json
import datetime


class AlertHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        try:
            payload = json.loads(body)
            print("\n" + "=" * 62, flush=True)
            print(f"[{ts}]  ALERT NOTIFICATION RECEIVED", flush=True)
            print("=" * 62, flush=True)

            for a in payload.get("alerts", []):
                status = a.get("status", "unknown").upper()
                name   = a.get("labels", {}).get("alertname", "unknown")
                sev    = a.get("labels", {}).get("severity", "unknown").upper()
                desc   = a.get("annotations", {}).get("description", "no description")
                starts = a.get("startsAt", "unknown")

                print(f"  STATUS   : {status}", flush=True)
                print(f"  ALERT    : {name}", flush=True)
                print(f"  SEVERITY : {sev}", flush=True)
                print(f"  STARTED  : {starts}", flush=True)
                print(f"  DETAILS  : {desc}", flush=True)
                print("", flush=True)

            print(f"  Total alerts in group: {len(payload.get('alerts', []))}", flush=True)
            print("=" * 62, flush=True)

        except Exception as e:
            print(f"[{ts}] Could not parse alert payload: {e}", flush=True)
            print(f"[{ts}] Raw body: {body.decode('utf-8', errors='replace')}", flush=True)

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        # Suppress default HTTP access logs — we only print alert content
        pass


if __name__ == "__main__":
    addr = ("", 5001)
    server = http.server.HTTPServer(addr, AlertHandler)
    print("=" * 62, flush=True)
    print("  Webhook Logger started — listening on :5001", flush=True)
    print("  Alertmanager will POST alerts here when rules fire.", flush=True)
    print("  Run:  docker logs webhook-logger -f   to watch live.", flush=True)
    print("=" * 62, flush=True)
    server.serve_forever()
