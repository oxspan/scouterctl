# Connections and authentication

Create a named connection once, or select an existing context. Replace the example endpoint and account with values supplied by your Collector administrator. Use another name if `local` already exists.

```sh
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user observer
scouterctl context list
scouterctl context use --name local
scouterctl auth login
scouterctl object list --interactive
```

`context add` saves the supplied host, port and account without connecting or selecting it. Names are unique; existing entries are not overwritten. `context use` selects an existing entry for subsequent commands. These local commands require explicit arguments and never prompt. `auth login` checks the selected Collector and credentials, then disconnects; it does **not** save a password or session. Each subsequent command needs `SCOUTERCTL_PASSWORD` or its own `--interactive` input.

Selection order is `--context NAME`, then the saved current context. Explicit `--host`, `--port` and `--user` override individual saved values; absent values fall back to `127.0.0.1:6100`, with a user required. Supplying all three endpoint flags without `--context` bypasses the config entirely. A misspelled context or malformed config fails rather than silently connecting elsewhere. Offline `inspect` does not read this config.

The file is `$XDG_CONFIG_HOME/scouterctl/config.json` (or `$HOME/.config/scouterctl/config.json`). Set `SCOUTERCTL_CONFIG` to use a different file. It contains named connection settings and the current selection; no passwords, password hashes or sessions. Writes use a lock and atomic replacement; a busy file returns exit `10`. New config files use owner-only permissions on POSIX filesystems. Back up the file before manual editing; removing an entry also requires clearing `current` if it selected that entry.

Existing commands remain noninteractive by default. `auth login` and `--interactive` allow hidden input only when Java has a real console; `--no-input` always disables it. `SCOUTERCTL_PASSWORD` takes precedence and is never echoed. Redirected input/output cannot trigger a prompt or supply the password through stdin: missing input returns one JSON error with exit `2`. Prompts go to stderr, results remain JSON on stdout. Empty input or terminal EOF (Ctrl+D on Linux, Ctrl+Z then Enter on Windows) cancels with exit `2`; Ctrl+C terminates without saving credentials. There is no automatic retry or credential cache.

For CI, use explicit endpoint flags or an isolated `SCOUTERCTL_CONFIG`, set the password through your secret mechanism, and pass `--no-input`. Contexts are local preferences, not an access-control boundary. TCP still has no native TLS.

## Passwords in scripts

Select a context or supply `--host`, `--port` and `--user`. Set the password for the current shell, then use `--no-input` for online commands. Do not put passwords in command arguments or argument files.

### Bash

```bash
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
printf '\n'
export SCOUTERCTL_PASSWORD
scouterctl doctor --no-input
# Clear it when finished:
unset SCOUTERCTL_PASSWORD
```

### PowerShell

```powershell
$password = Read-Host 'Collector password' -AsSecureString
$env:SCOUTERCTL_PASSWORD = [System.Net.NetworkCredential]::new('', $password).Password
scouterctl doctor --no-input
# Clear it when finished:
Remove-Item Env:SCOUTERCTL_PASSWORD
```

For a single terminal command, use `--interactive` instead of setting an environment variable. Automated jobs should obtain the value from their secret store.

## Connection troubleshooting

| Result | Next step |
| --- | --- |
| Exit `2`, `INVALID_ARGUMENT` | Supply `--user` and `SCOUTERCTL_PASSWORD`. Use a hostname/IP for `--host`, a port in 1..65535, and `--timeout-ms` in 1..60000. |
| Exit `3`, `AUTH_FAILED` | Check the account and password with the Collector administrator. Re-enter the password using hidden input. |
| Exit `5`, `CONNECTION_FAILED` | Check hostname resolution, the TCP port, Collector availability, and the network/tunnel. For a slow reachable Collector, adjust `--timeout-ms` (default 5000). |
| Exit `9`, `VERSION_MISMATCH` | Check the [supported Collector releases](supported-versions.md). There is no compatibility override. |
| Exit `0`, empty object list | Login worked, but no targets were reported. Check the Agent's Collector address and startup logs. |

Messages suggest a next check, not a definitive network diagnosis. A timeout can occur after a connection was established. After correcting input or configuration, rerun `doctor`, then `object list`.
