# Medium player validation runner

This operator-invoked runner targets the deployed Medium Paper 26.2 snapshot
`0.1.0-rc.1-7-g98e7568` (SHA-256
`84d28cfd8d20e16bd37a34235ab3e564aee79f9476f588ee92a2279998b8525c`).
The operator must confirm the installed JAR identity and unchanged Medium preset
before running; a client cannot attest server files. The client speaks 1.21.11
through existing ViaVersion support. Results do not establish native 26.3 support.

## Preparation

Provision a fresh normal disposable AuthMe account separately. Prefer a unique
`SP<random hex>` name, without staff/testing-role suffixes. Do not grant OP,
`sessionpulse.admin`, exemption or special role permissions. The runner does not
register accounts, change authentication or modify permissions. Existing player
records must not be reset to prepare this test. Select an ordinary safe standing
location before invocation; the runner performs no movement, combat, interaction,
world edits or inventory actions and disables simulated physics after spawn.

Confirm enforcement remains disabled, AFK AUTO uses the 300-second built-in timer,
and the 5/10/15-minute reminders and `Ninja6 » ` prefix match Medium. EssentialsX
is absent on Medium. A different configuration requires review of the runner's
expectations, not changing the production configuration to make it pass.

Use Node with an existing `node_modules` containing mineflayer 4.39.0 or compatible,
prismarine-chat and prismarine-nbt. The known operator harness contains these
libraries. Dependency installation is separate from execution. Offline checks:

```powershell
node --check scripts/medium-player-validation.cjs
node --test scripts/tests/medium-player-validation.test.cjs
```

## Invocation

The default destination is loopback port 42567. An optional SSH tunnel can be
opened separately in the operator's terminal, substituting their SSH alias and
Medium game port:

```text
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:42567:127.0.0.1:<Medium game port> <SSH alias>
```

The script opens no SSH/RCON connection and reads no server credentials. Supply
the account password through the environment, without writing it into this file,
shell history or evidence. For example, PowerShell can request it interactively:

```powershell
$env:BOT_MODULE_ROOT = '<existing node_modules directory>'
$env:SPULSE_USERNAME = '<provisioned fresh account>'
$secret = Read-Host 'Bot account password' -AsSecureString
$env:SPULSE_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$env:SPULSE_OUTPUT = '<absolute evidence path>'
try { node scripts/medium-player-validation.cjs }
finally { Remove-Item Env:SPULSE_PASSWORD }
```

`SPULSE_HOST` and `SPULSE_PORT` optionally override the destination.
`SPULSE_CANDIDATE_VERSION`, `SPULSE_CANDIDATE_SHA256`, `SPULSE_SERVER_BUILD`
and `SPULSE_SERVER_SHA256` optionally record operator-provided expected identities;
they are explicitly unverified by the player client. Do not enable
protocol debug logging: authentication payloads contain the password. The runner
clears `DEBUG` before loading client libraries and records only known plugin
messages, not unrelated chat or authentication/account-list contents. Protect the
JSON evidence, which names the disposable account. Close the SSH tunnel separately
when finished. CTRL+C requests cleanup; a stalled connection is destroyed after
two seconds. The hard runtime limit is 30 minutes; expect roughly 25 minutes.

## What the results mean

The runner handles the known AuthMe configuration-phase login dialog using the
length-prefixed nameless-NBT custom click packet, with legacy `/login` fallback.
Unknown dialogs, registration requirements or failed authentication abort; server
authentication settings are never changed.

Read-only `/spulse time` every 30 seconds supplies activity. Alias responses and
another-player time-query denial exercise ordinary-player commands without risking
reload/reset if the account unexpectedly has administrative access. Two independent
480-second idle spans send no commands, chat or input. Since a time query resets
activity and results are whole truncated minutes, each span expects 4-6 credited
minutes around the 300-second threshold, not zero. The separate resumed-active
phase must then increase time. Moving currents or other external input invalidate
the idle assumption; investigate failures rather than weakening the checks.

Exact chat, action-bar, title and subtitle text must arrive once for each configured
5/10/15-minute reminder. Received sound packet occurrences are recorded, but sound
source attribution, audibility, visual layout and colors remain manual. Reconnect
checks whole-minute continuity and a bounded 12-second no-replay observation.
This establishes join/leave continuity, not restart persistence. Reload, full server
restart, native-client appearance/audio and Easy's EssentialsX behavior remain
explicit manual gaps. No configuration, enforcement, restart or deployment occurs.

Exit 0 and `PASS_WITH_MANUAL_GAPS` mean these scoped checks passed. Any failed or
missing observation exits nonzero and retains partial evidence. This script was
prepared and tested offline; production invocation requires the operator's next
instruction and a provisioned account.
