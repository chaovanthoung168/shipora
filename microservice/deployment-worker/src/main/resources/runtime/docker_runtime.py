"""Shipora Docker Engine rollout, transported over a pinned SSH connection.
Only this fixed program is executed; credentials and environment arrive on stdin.
"""
import base64
import fcntl
import http.client
import json
import os
import pathlib
import re
import socket
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid

os.umask(0o077)

class Failed(Exception):
    pass

def pull_failure(message):
    text = str(message).lower()
    cases = (
        ("REGISTRY_ACCESS_DENIED", ("unauthorized", "access denied", "denied", "authentication required")),
        ("REGISTRY_TLS_FAILED", ("x509", "certificate")),
        ("RUNTIME_STORAGE_FULL", ("no space left", "disk quota exceeded")),
        ("IMAGE_PLATFORM_UNSUPPORTED", ("no matching manifest", "no match for platform")),
        ("IMAGE_EXTRACTION_FAILED", ("failed to extract", "failed to unpack", "failed to apply layer", "snapshot")),
        ("REGISTRY_CONNECTION_FAILED", ("timeout", "timed out", "no such host", "network is unreachable", "connection refused", "context deadline")),
    )
    for code, markers in cases:
        if any(marker in text for marker in markers):
            raise Failed(code)
    raise Failed("PULL_FAILED")

class UnixHTTP(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(60)
        self.sock.connect("/var/run/docker.sock")

def api(method, path, data=None, allow_missing=False, auth=None, stream=False):
    connection = UnixHTTP("localhost", timeout=60)
    try:
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["X-Registry-Auth"] = base64.urlsafe_b64encode(json.dumps(auth).encode()).decode()
        encoded = None if data is None else json.dumps(data).encode()
        connection.request(method, "/v1.44" + path, encoded, headers)
        response = connection.getresponse()
        if allow_missing and response.status == 404:
            response.read()
            return None
        if not 200 <= response.status < 300:
            if path.startswith("/images/create"):
                raw = response.read(65537)
                if len(raw) > 65536:
                    raise Failed("PULL_RESPONSE_LIMIT")
                try:
                    detail = json.loads(raw).get("message", "")
                except (ValueError, AttributeError):
                    detail = ""
                pull_failure(detail)
            raise Failed("DOCKER_API_FAILED")
        if stream:
            while True:
                line = response.readline(65537)
                if not line:
                    break
                if len(line) > 65536:
                    raise Failed("PULL_RESPONSE_LIMIT")
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if "error" in event or "errorDetail" in event:
                    detail = event.get("errorDetail")
                    pull_failure(detail.get("message", "") if isinstance(detail, dict) else event.get("error", ""))
            return None
        raw = response.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024:
            raise Failed("DOCKER_RESPONSE_LIMIT")
        return json.loads(raw) if raw else {}
    finally:
        connection.close()

def inspect(name):
    return api("GET", "/containers/" + urllib.parse.quote(name, safe="") + "/json", allow_missing=True)

def remove(name):
    api("DELETE", "/containers/" + urllib.parse.quote(name, safe="") + "?force=true", allow_missing=True)

def owned(container, request, exact=False):
    if container is None:
        return
    labels = container.get("Config", {}).get("Labels", {}) or {}
    if labels.get("shipora.application") != request["application"] or labels.get("shipora.organization") != request["organization"]:
        raise Failed("FOREIGN_CONTAINER")
    if int(labels.get("shipora.fence", "0")) > request["fence"]:
        raise Failed("STALE_FENCE")
    if exact and (labels.get("shipora.deployment") != request["deployment"] or int(labels.get("shipora.fence", "0")) != request["fence"]):
        raise Failed("STALE_FENCE")

def published(container, port):
    ports = (container or {}).get("NetworkSettings", {}).get("Ports", {})
    values = ports.get(str(port) + "/tcp") or [entry for entries in ports.values() if entries for entry in entries]
    for entry in values:
        number = int(entry.get("HostPort", "0"))
        if 0 < number < 65536:
            return number
    raise Failed("PORT_UNAVAILABLE")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None

def healthy(container, request, timeout):
    port = published(container, request["port"])
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        latest = inspect(container["Id"])
        if latest is None or not latest.get("State", {}).get("Running"):
            raise Failed("CONTAINER_STOPPED")
        try:
            with opener.open("http://127.0.0.1:" + str(port) + request["healthPath"], timeout=2) as response:
                if 200 <= response.status < 300:
                    return port
        except urllib.error.HTTPError as failure:
            failure.close()
        except Exception:
            pass
        time.sleep(0.5)
    raise Failed("HEALTH_CHECK_FAILED")

def create(name, request, candidate, host_port=None):
    key = str(request["port"]) + "/tcp"
    document = {
        "Image": request["image"],
        "Env": [key + "=" + value for key, value in request["environment"].items()],
        "Labels": {
            "shipora.application": request["application"],
            "shipora.organization": request["organization"],
            "shipora.deployment": request["deployment"],
            "shipora.fence": str(request["fence"]),
        },
        "ExposedPorts": {key: {}},
        "HostConfig": {
            "PortBindings": {key: [{"HostIp": "127.0.0.1" if candidate else "", "HostPort": str(host_port) if host_port else ""}]},
            "RestartPolicy": {"Name": "no" if candidate else "unless-stopped"},
            "SecurityOpt": ["no-new-privileges:true"],
            "CapDrop": ["ALL"],
            "Memory": 1073741824,
            "NanoCpus": 1000000000,
            "PidsLimit": 256,
        },
    }
    result = api("POST", "/containers/create?name=" + urllib.parse.quote(name, safe=""), document)
    api("POST", "/containers/" + result["Id"] + "/start")
    return inspect(result["Id"])

def rename(container, name):
    api("POST", "/containers/" + container["Id"] + "/rename?name=" + urllib.parse.quote(name, safe=""))

def restore(stable_name, backup_name, request):
    current, backup = inspect(stable_name), inspect(backup_name)
    owned(current, request)
    owned(backup, request)
    if backup:
        if current:
            owned(current, request, exact=True)
            remove(current["Id"])
        rename(backup, stable_name)
        if not backup.get("State", {}).get("Running"):
            api("POST", "/containers/" + backup["Id"] + "/start")
    elif current:
        owned(current, request, exact=True)
        remove(current["Id"])

def run(request):
    for key in ("organization", "application", "deployment"):
        request[key] = str(uuid.UUID(request[key]))
    if type(request["fence"]) is not int or request["fence"] < 1:
        raise Failed("INVALID_FENCE")
    if not re.fullmatch(r"[a-z0-9._:/-]+@sha256:[a-f0-9]{64}", request["image"]):
        raise Failed("INVALID_IMAGE")
    if type(request["port"]) is not int or not 1 <= request["port"] <= 65535:
        raise Failed("INVALID_PORT")
    if not re.fullmatch(r"/[A-Za-z0-9_./-]*", request["healthPath"]) or ".." in request["healthPath"] or request["healthPath"].startswith("//"):
        raise Failed("INVALID_HEALTH_PATH")
    if not 1 <= request["timeout"] <= 1800:
        raise Failed("INVALID_TIMEOUT")
    for key, value in request["environment"].items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,254}", key) or not isinstance(value, str) or "\x00" in value:
            raise Failed("INVALID_ENVIRONMENT")
    root = pathlib.Path.home() / ".local" / "state" / "shipora-runtime"
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    identity = request["organization"] + "-" + request["application"]
    with open(root / (identity + ".lock"), "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        fence_path = root / (identity + ".json")
        previous = json.loads(fence_path.read_text()) if fence_path.exists() else {"fence": 0}
        if previous["fence"] > request["fence"]:
            raise Failed("STALE_FENCE")
        if request["action"] == "deploy":
            fence_path.write_text(json.dumps({"fence": request["fence"], "deployment": request["deployment"]}))
        elif previous["fence"] != request["fence"] or previous.get("deployment") != request["deployment"]:
            raise Failed("STALE_FENCE")
        stable_name = "shipora-" + request["application"]
        backup_name = stable_name + "-previous"
        candidate_name = stable_name + "-" + request["deployment"].replace("-", "")
        if request["action"] == "rollback":
            restore(stable_name, backup_name, request)
            candidate = inspect(candidate_name)
            if candidate:
                owned(candidate, request, exact=True)
                remove(candidate["Id"])
            return {"ok": True, "port": 0}
        if request["action"] == "verify":
            current = inspect(stable_name)
            owned(current, request, exact=True)
            if current is None:
                raise Failed("CONTAINER_MISSING")
            port = healthy(current, request, request["timeout"])
            backup = inspect(backup_name)
            if backup:
                owned(backup, request)
                remove(backup["Id"])
            return {"ok": True, "port": port}
        if request["action"] != "deploy":
            raise Failed("INVALID_ACTION")
        current, backup = inspect(stable_name), inspect(backup_name)
        owned(current, request)
        owned(backup, request)
        if current is None and backup is not None:
            rename(backup, stable_name)
            if not backup.get("State", {}).get("Running"):
                api("POST", "/containers/" + backup["Id"] + "/start")
            current, backup = inspect(stable_name), None
        if current and current.get("Config", {}).get("Labels", {}).get("shipora.deployment") == request["deployment"]:
            owned(current, request, exact=True)
            return {"ok": True, "port": healthy(current, request, request["timeout"])}
        api("POST", "/images/create?fromImage=" + urllib.parse.quote(request["image"], safe=""), auth=request.get("registryAuth"), stream=True)
        candidate = inspect(candidate_name)
        if candidate:
            owned(candidate, request, exact=True)
            remove(candidate["Id"])
        try:
            candidate = create(candidate_name, request, True)
            healthy(candidate, request, request["timeout"])
            remove(candidate["Id"])
            old_port = published(current, request["port"]) if current else None
            if backup:
                remove(backup["Id"])
            if current:
                if current.get("State", {}).get("Running"):
                    api("POST", "/containers/" + current["Id"] + "/stop?t=15")
                rename(current, backup_name)
            try:
                deployed = create(stable_name, request, False, old_port)
                port = healthy(deployed, request, request["timeout"])
            except Exception:
                restore(stable_name, backup_name, request)
                raise
            return {"ok": True, "port": port}
        finally:
            candidate = inspect(candidate_name)
            if candidate:
                owned(candidate, request, exact=True)
                remove(candidate["Id"])

if __name__ == "__main__":
    try:
        result = run(json.load(sys.stdin))
    except Failed as failure:
        result = {"ok": False, "code": str(failure)}
    except Exception:
        result = {"ok": False, "code": "DOCKER_RUNTIME_FAILED"}
    print(json.dumps(result))
