#!/usr/bin/env python3
"""
aegis-tunnel-proxy.py — Server-Side peer connector for Aegis Tunnel App.
Runs inside sys-vpn. Listens on Port 5000 within the VPN tunnel and forwards queries
to the secure offline sys-copilot via the 'aegis.HeimdallChat' qrexec call.
"""

import http.server
import json
import subprocess
import sys
import os

PORT = 5000
BIND_ADDRESS = "0.0.0.0"

class AegisTunnelHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Override to log cleanly to stdout/journald
        sys.stdout.write("%s - - [%s] %s\n" %
                         (self.address_string(),
                          self.log_date_time_string(),
                          format%args))
        sys.stdout.flush()

    def do_OPTIONS(self):
        # Support CORS for web preview or API clients
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'POST, GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_POST(self):
        if self.path == "/api/chat":
            self.handle_chat()
        elif self.path == "/api/goal":
            self.handle_goal()
        else:
            self.send_error_response(404, "Not Found")
            
    def do_GET(self):
        if self.path == "/api/status":
            self.handle_status()
        else:
            self.send_error_response(404, "Not Found")
            
    def handle_chat(self):
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        try:
            req = json.loads(post_data.decode('utf-8'))
            query = req.get("query", "")
        except Exception as e:
            self.send_error_response(400, f"Invalid JSON: {str(e)}")
            return
            
        if not query:
            self.send_error_response(400, "Missing 'query' field")
            return
            
        # Execute qrexec command to forward the query to sys-copilot's aegis.HeimdallChat
        try:
            proc = subprocess.run(
                ["qrexec-client-vm", "sys-copilot", "aegis.HeimdallChat"],
                input=json.dumps({"query": query}).encode('utf-8'),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=130
            )
            
            if proc.returncode != 0:
                err_msg = proc.stderr.decode('utf-8', errors='replace').strip()
                self.send_error_response(502, f"RPC error from sys-copilot: {err_msg}")
                return
                
            resp_data = json.loads(proc.stdout.decode('utf-8'))
            self.send_success_response(resp_data)
        except subprocess.TimeoutExpired:
            self.send_error_response(504, "Heimdall agent timeout.")
        except Exception as e:
            self.send_error_response(500, f"Internal proxy error: {str(e)}")

    def handle_goal(self):
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        try:
            req = json.loads(post_data.decode('utf-8'))
            goal = req.get("goal", "")
        except Exception as e:
            self.send_error_response(400, f"Invalid JSON: {str(e)}")
            return
            
        if not goal:
            self.send_error_response(400, "Missing 'goal' field")
            return
            
        query = f"/goal {goal}"
        try:
            proc = subprocess.run(
                ["qrexec-client-vm", "sys-copilot", "aegis.HeimdallChat"],
                input=json.dumps({"query": query}).encode('utf-8'),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=300
            )
            
            if proc.returncode != 0:
                err_msg = proc.stderr.decode('utf-8', errors='replace').strip()
                self.send_error_response(502, f"RPC error from sys-copilot: {err_msg}")
                return
                
            resp_data = json.loads(proc.stdout.decode('utf-8'))
            self.send_success_response(resp_data)
        except subprocess.TimeoutExpired:
            self.send_error_response(504, "Goal execution timeout.")
        except Exception as e:
            self.send_error_response(500, f"Internal proxy error: {str(e)}")

    def handle_status(self):
        # Query wireguard interface info
        wg_status = "inactive"
        try:
            proc = subprocess.run(["wg", "show", "wg0"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if proc.returncode == 0:
                wg_status = "active"
        except Exception:
            pass
            
        # Query Syncthing status
        syncthing_status = "inactive"
        try:
            proc = subprocess.run(["pgrep", "syncthing"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if proc.returncode == 0:
                syncthing_status = "active"
        except Exception:
            pass
            
        status_data = {
            "status": "ok",
            "wireguard": wg_status,
            "syncthing": syncthing_status,
            "aegis_node": "sys-vpn (WireGuard)",
            "api_version": "1.0.0"
        }
        self.send_success_response(status_data)

    def send_success_response(self, data):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode('utf-8'))

    def send_error_response(self, code, message):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps({"status": "error", "message": message}).encode('utf-8'))

def main():
    print(f"Starting Aegis Tunnel Proxy on {BIND_ADDRESS}:{PORT}...", flush=True)
    server = http.server.HTTPServer((BIND_ADDRESS, PORT), AegisTunnelHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    main()
