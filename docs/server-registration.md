# Server registration and inventory

Project Service provides server registration and organization-scoped inventory
using the existing `project.servers` table. No migration is required.

| Method | Endpoint | Success |
| --- | --- | --- |
| POST | `/api/servers` | 201, Location header, server response |
| GET | `/api/servers/{serverId}` | 200, server response |
| GET | `/api/servers?page=0&size=20` | 200, array of server responses |

Requests require the server-access permission and an authenticated organization.
The local developer account has both project and server permissions. Outside
the local profile, access remains denied until real identity integration exists.

1. Start PostgreSQL from the repository root and load its environment:

   ```powershell
   docker compose --project-directory infrastructure/database up -d --wait
   .\scripts\Import-DatabaseEnv.ps1
   Set-Location .\microservice\project-service
   .\gradlew.bat bootRun --args="--spring.profiles.active=local"
   ```

2. Import `postman/Shipora-Server-Inventory.postman_collection.json` in Postman.
   Set collection variables `password`, `host`, `credentialSecretRef`, and
   `verifiedFingerprint`. Use the password already configured in
   `application-local.yaml`; no credentials are included in the collection.

3. Obtain the target server's actual host key fingerprint through a trusted
   console. For a Linux target using an Ed25519 host key:

   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
   ```

   Set `verifiedFingerprint` to the complete `SHA256:...` value. Use the public
   host key file appropriate to your target. The API validates format, not that
   the fingerprint actually belongs to the host.

4. Run the collection in order. Registration saves `serverId` for subsequent
   lookup. The last two requests verify invalid input and missing authentication.

Example registration body (replace the variables with your actual values):

```json
{
  "name": "Development Server",
  "host": "{{host}}",
  "sshPort": 22,
  "sshUsername": "deploy",
  "credentialSecretRef": "{{credentialSecretRef}}",
  "sshHostKeyFingerprint": "{{verifiedFingerprint}}"
}
```

The port defaults to 22 when omitted. Provide IPv6 addresses without brackets.
Hosts must not contain URLs or embedded ports. Lists have deterministic
creation-time/ID ordering and a page size between 1 and 100; offsets exceeding
the JPA integer limit are rejected.

New servers always start OFFLINE. Credential references are opaque handles, not
SSH passwords or private keys, and are omitted from inventory responses. This
feature stores the reference but does not resolve or authorize it in a secret
store. Registration performs no SSH connection and does not prove reachability.
Server names and hosts are not unique in the current schema; each POST creates
a new registration.

The SSH authentication check is now implemented separately; see
[server-connection-checks.md](server-connection-checks.md). It resolves a configured
local key, authorizes the exact destination, verifies the host key, authenticates,
and updates status. It executes no commands. Updates, retirement/deletion
coordination, secret-store integration, and deployment execution are separate
lifecycle features. Replace local Basic Auth with real identity integration before
exposing the system to users.

The domain and application logic are independent of Spring and JPA. Incoming
ports exchange application records; outgoing ports exchange domain objects.
HTTP records are mapped separately. A transaction wrapper covers the entire
use case; registration explicitly persists a new JPA entity.

Run the targeted tests from the service directory:

```powershell
.\gradlew.bat test --tests "*ServerServiceTest" --tests "*ServerWebSecurityTest"
```

These tests cover application behavior and the real local Spring MVC/security
chain with mocked use cases, including continued access to Project CRUD.
They do not require a running database or exercise real SSH connectivity.
