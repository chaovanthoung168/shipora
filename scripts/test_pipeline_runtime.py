"""Isolated Docker API fixture tests; never mounts or contacts a host daemon.

Run in an ephemeral Python Linux container with only this file and the runtime
source mounted read-only. The Unix socket below belongs to this fixture process.
"""
import copy
import http.server
import importlib.util
import json
import os
import pathlib
import socketserver
import tempfile
import threading
import unittest
import urllib.parse
import uuid

spec = importlib.util.spec_from_file_location("runtime", "/runtime/docker_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
containers, ports, health_servers = {}, {}, []
pull_result, pull_status = {"status": "fixture pull complete"}, 200
gate = threading.RLock()


class Health(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with gate:
            item = containers.get(ports.get(self.server.server_port))
            okay = item and item["State"]["Running"] and item["Config"].get("Env", []) != ["FAIL=true"]
        self.send_response(200 if okay else 503)
        self.end_headers()

    def log_message(self, *args):
        pass


class Engine(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def do_DELETE(self):
        self.dispatch()

    def dispatch(self):
        url = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(url.path.removeprefix("/v1.44"))
        query = urllib.parse.parse_qs(url.query)
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        data = json.loads(raw) if raw else None
        result, status = {}, 200
        with gate:
            if path == "/images/create":
                result, status = pull_result, pull_status
            elif path == "/containers/create":
                identity = uuid.uuid4().hex
                binding_key, bindings = next(iter(data["HostConfig"]["PortBindings"].items()))
                port = int(bindings[0]["HostPort"] or "0")
                if not port:
                    health = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Health)
                    port = health.server_port
                    health_servers.append(health)
                    threading.Thread(target=health.serve_forever, daemon=True).start()
                ports[port] = identity
                containers[identity] = {
                    "Id": identity, "Name": query["name"][0], "Config": data,
                    "State": {"Running": False},
                    "NetworkSettings": {"Ports": {binding_key: [{"HostIp": "127.0.0.1", "HostPort": str(port)}]}},
                }
                result, status = {"Id": identity}, 201
            elif path.startswith("/containers/"):
                part = path.removeprefix("/containers/").split("/")
                item = next((v for v in containers.values() if v["Id"] == part[0] or v["Name"] == part[0]), None)
                if item is None:
                    status = 404
                elif self.command == "DELETE":
                    containers.pop(item["Id"])
                elif len(part) == 2 and part[1] == "json":
                    result = item
                elif part[1] == "start":
                    item["State"]["Running"] = True
                    ports[runtime.published(item, 8080)] = item["Id"]
                elif part[1] == "stop":
                    item["State"]["Running"] = False
                elif part[1] == "rename":
                    item["Name"] = query["name"][0]
            else:
                status = 404
            body = json.dumps(result).encode() + b"\n"
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class RuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        socket_path = pathlib.Path("/var/run/docker.sock")
        if socket_path.exists():
            raise RuntimeError("Refusing to replace an existing Docker socket")
        cls.engine = socketserver.ThreadingUnixStreamServer(str(socket_path), Engine)
        threading.Thread(target=cls.engine.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.engine.shutdown()
        cls.engine.server_close()
        pathlib.Path("/var/run/docker.sock").unlink()
        for server in health_servers:
            server.shutdown()
            server.server_close()

    def setUp(self):
        global pull_result, pull_status
        pull_result, pull_status = {"status": "fixture pull complete"}, 200
        self.home = tempfile.TemporaryDirectory()
        self.old_home = os.environ.get("HOME")
        os.environ["HOME"] = self.home.name
        self.request = {
            "organization": str(uuid.uuid4()), "application": str(uuid.uuid4()),
            "deployment": str(uuid.uuid4()), "fence": 1, "action": "deploy",
            "image": "registry.example/test@sha256:" + "a" * 64,
            "port": 8080, "healthPath": "/health", "timeout": 1,
            "environment": {},
        }

    def tearDown(self):
        if self.old_home is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = self.old_home
        self.home.cleanup()

    def stable(self):
        return runtime.inspect("shipora-" + self.request["application"])

    def next_request(self):
        result = copy.deepcopy(self.request)
        result.update(fence=2, deployment=str(uuid.uuid4()), image="registry.example/test@sha256:" + "b" * 64)
        return result

    def test_pull_failures_are_classified_without_returning_registry_secrets(self):
        global pull_result, pull_status
        cases = (
            (200, {"errorDetail": {"message": "unauthorized password=private-value"}}, "REGISTRY_ACCESS_DENIED"),
            (500, {"message": "failed to extract layer token=private-value"}, "IMAGE_EXTRACTION_FAILED"),
            (200, {"error": "x509: certificate rejected token=private-value"}, "REGISTRY_TLS_FAILED"),
            (200, {"error": "no matching manifest for this platform"}, "IMAGE_PLATFORM_UNSUPPORTED"),
            (500, {"message": "no space left on device"}, "RUNTIME_STORAGE_FULL"),
        )
        for status, result, code in cases:
            with self.subTest(code=code):
                pull_result, pull_status = result, status
                with self.assertRaises(runtime.Failed) as failure:
                    runtime.api("POST", "/images/create?fromImage=fixture", stream=True)
                self.assertEqual(code, str(failure.exception))
                self.assertNotIn("private-value", str(failure.exception))

    def test_success_and_idempotent_deploy_preserve_published_port(self):
        first = runtime.run(self.request)
        self.assertTrue(first["ok"])
        self.assertEqual(first["port"], runtime.run(self.request)["port"])
        verify = dict(self.request, action="verify")
        self.assertEqual(first["port"], runtime.run(verify)["port"])
        self.assertEqual("1", self.stable()["Config"]["Labels"]["shipora.fence"])

    def test_failed_candidate_preserves_previous_healthy_runtime(self):
        runtime.run(self.request)
        old_id = self.stable()["Id"]
        failed = self.next_request()
        failed["environment"] = {"FAIL": "true"}
        with self.assertRaises(runtime.Failed):
            runtime.run(failed)
        self.assertEqual(old_id, self.stable()["Id"])
        self.assertTrue(self.stable()["State"]["Running"])

    def test_health_failure_rolls_back_and_stale_fence_cannot_remove_runtime(self):
        old_port = runtime.run(self.request)["port"]
        old_id = self.stable()["Id"]
        newer = self.next_request()
        self.assertEqual(old_port, runtime.run(newer)["port"])
        containers[self.stable()["Id"]]["Config"]["Env"] = ["FAIL=true"]
        with self.assertRaises(runtime.Failed):
            runtime.run(dict(newer, action="verify"))
        runtime.run(dict(newer, action="rollback"))
        self.assertEqual(old_id, self.stable()["Id"])
        self.assertTrue(self.stable()["State"]["Running"])
        with self.assertRaisesRegex(runtime.Failed, "STALE_FENCE"):
            runtime.run(dict(self.request, action="rollback"))
        self.assertEqual(old_id, self.stable()["Id"])

    def test_foreign_container_is_never_adopted_or_removed(self):
        runtime.run(self.request)
        original = self.stable()["Id"]
        containers[original]["Config"]["Labels"]["shipora.organization"] = str(uuid.uuid4())
        with self.assertRaisesRegex(runtime.Failed, "FOREIGN_CONTAINER"):
            runtime.run(self.next_request())
        self.assertIn(original, containers)


if __name__ == "__main__":
    unittest.main(verbosity=2)
