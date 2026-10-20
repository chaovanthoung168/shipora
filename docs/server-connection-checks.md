# SSH connection checks

After registering a server, Project Service can verify its saved SSH host key,
authenticate with an operator-configured private key, and persist its resulting
status. It opens no shell, executes no commands, and deploys no software.

```http
POST http://localhost:8081/api/servers/SERVER_ID/connection-checks
```

Use Basic Auth with your existing local developer account. No request body is
needed. The endpoint requires `ROLE_SERVER_CONNECT`, in addition to an
authenticated organization. Inventory access alone does not grant connection
permission. Unknown servers and servers outside the organization return 404.

The local profile already authorizes the target you provided:

| Setting | Value |
| --- | --- |
| Host | `10.1.18.4` |
| Port | `22` |
| SSH user | `root` |
| Credential reference | `ssh/unity-workspace` |
| Host key algorithm | `ssh-ed25519` |
| Host key fingerprint | `SHA256:BqEQX70okhPJpl1lPIpMtMTENOTKIuBgAG6bMp/tGqQ` |
| Organization | `11111111-1111-1111-1111-111111111111` |

The registered server must match all these authorization settings. A reference
does not permit using its private key for another organization, host, port,
username, or fingerprint. This implementation authorizes IP literals, avoiding
DNS resolution during checks. DNS names may still be stored in inventory, but
cannot be checked through this literal-IP credential configuration.

1. The default private-key location is the runtime user's
   `~/.ssh/unity-workspace`. Keep the key outside the repository. If needed, set
   `SHIPORA_SSH_PRIVATE_KEY` in the Project Service run configuration to its
   absolute path. This variable overrides the configured default location.

2. If the key is encrypted, set `SHIPORA_SSH_KEY_PASSPHRASE` in that same run
   configuration. The client is noninteractive and never opens a passphrase
   prompt or falls back to password authentication. No passphrase or private-key
   content belongs in Postman, source files, logs, or exported collections.

3. Restart Project Service with the `local` profile. Environment variables must
   be available to that service process; setting them in an unrelated terminal
   does not update an existing IntelliJ run configuration.

4. In Postman, use the updated collection in
   `docs/postman/Shipora-Server-Inventory.postman_collection.json`.
   Set `serverId` to the ID of the server you already registered, or run the
   registration request to create one. Set the collection's local Basic Auth
   password, then send **Check SSH connection**.

The local default configuration can be overridden with these properties:

```yaml
shipora:
  ssh:
    connect-timeout: 5s
    io-timeout: 5s
    check-timeout: 15s
    max-concurrent-checks: 2
```

Each timeout is bounded between 100ms and 1m; concurrent checks are bounded
between 1 and 8. There is no waiting queue. A check already running for the same
server, or exhausted check capacity, returns 429 and does not overwrite status.
Per-server exclusion and capacity are per service instance. Distributed job
coordination is handled separately by Deployment Service and Deployment Worker;
see [deployment-pipeline.md](deployment-pipeline.md).

Completed checks return HTTP 200, even if the target cannot be authenticated.
Inspect the result's `code` and `status`:

| Code | Saved status | Meaning |
| --- | --- | --- |
| CONNECTED | ACTIVE | Pinned host key verified; public-key authentication succeeded |
| UNREACHABLE | OFFLINE | SSH transport could not reach the server |
| TIMED_OUT | OFFLINE | Connection or overall deadline expired |
| HOST_KEY_MISMATCH | ERROR | Host key differs from the saved fingerprint |
| AUTHENTICATION_FAILED | ERROR | The verified server rejected the credential |
| CREDENTIAL_UNAVAILABLE | ERROR | Key could not be read or decrypted |
| TARGET_NOT_AUTHORIZED | ERROR | No configured credential authorizes all server attributes |
| SSH_NOT_CONFIGURED | ERROR | No targets are configured |
| CHECK_FAILED | ERROR | Check could not complete |

Example successful response:

```json
{
  "serverId": "your-server-uuid",
  "status": "ACTIVE",
  "code": "CONNECTED",
  "message": "SSH host key verified and authentication succeeded.",
  "checkedAt": "an ISO timestamp"
}
```

GET `/api/servers/{serverId}` then returns the saved status. `checkedAt` is a
response timestamp, not stored check history. No migration is required because
the existing servers table already contains a status column.

The SSH handshake runs outside database transactions. Reading the server and
saving status use separate short database operations. The private key is used
only after a successful host-key verification. The client uses only the selected
key; it does not read arbitrary identities from SSH config or an SSH agent.

Run the focused tests from `microservice/project-service`:

```powershell
.\gradlew.bat test --tests "*ServerServiceTest" --tests "*ServerWebSecurityTest" --tests "*ServerConnectionServiceTest" --tests "*ConfiguredSshCredentialResolverTest" --tests "*SshjServerConnectionProbeTest" --tests "*SshConnectionPropertiesTest"
```

The mocked SSH tests use generated, temporary fixture keys and exercise host-key
pinning, credential scope, authentication failure, deadlines, and bounded
capacity. They do not authenticate to the configured real target.

Implementation verification compiled the module and passed 38 focused JUnit
tests using cached project dependencies. Gradle verification was blocked by the
existing Windows JVM loopback/socket issue, so no successful Gradle build is
claimed. A separate authentication-only check against `10.1.18.4` verified the
supplied host fingerprint and authenticated with the configured local key. It
opened no command session and did not update database status; invoke the API
after restarting the service to record a result.

Before a customer deployment, replace local authentication and the local-file
credential resolver with your identity provider and authorized secret-store
integration. This feature proves SSH authentication, not Docker availability,
deployment readiness, or the safety of future remote commands.

The SSH adapter uses [SSHJ 0.41.1](https://github.com/hierynomus/sshj/releases/tag/v0.41.1)
and its [SHA256 fingerprint verifier](https://github.com/hierynomus/sshj/blob/v0.41.1/src/main/java/net/schmizz/sshj/transport/verification/FingerprintVerifier.java).
