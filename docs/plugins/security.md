# Plugin permissions

Each plugin has its own Lua VM and permissions stored by its installed filename.
Changing permissions reloads the engine: old VMs stop, timers/watchers/hooks are
removed, and callbacks from an older VM cannot run in its replacement. Requests
already sent cannot be undone; queued actions and callbacks check permissions
again before execution or delivery.

## Java access levels

| Level | Access |
| --- | --- |
| Isolated | Lua and permission-checked `xenon` APIs. No direct Java objects. |
| Restricted | Adds `luajava.bindClass` / `newInstance` for approved value classes. No reflection, Android, streams or sockets. Cached methods check revocation. |
| Trusted | Unrestricted Java, available only with global God Mode **and** this plugin's `JAVA` permission. This can bypass all other permission switches. |

God Mode does not automatically grant messaging, files, internet or links through
the mediated API. Unrestricted Java itself gives full access to the client; use
Isolated or Restricted when the other switches need to enforce a boundary.

## Scopes

| Scope | Controls |
| --- | --- |
| `GENERAL` | Registration, timers and the plugin's own settings; always available. |
| `MESSAGING` | Master switch for the five message permissions below. |
| `READ_MESSAGES` | Message queries, peer names, current chat, message events and watchers. |
| `SEND_MESSAGES` | Sending text; sending existing media also requires `READ_MESSAGES`. |
| `DELETE_MESSAGES` | Deleting messages. |
| `REACTIONS` | Setting reactions. |
| `READ_RECEIPTS` | Marking history as read. |
| `UI` | Dialogs, bulletins, navigation and refreshing settings. Chat pickers and chat menu hooks also require `READ_MESSAGES`. |
| `LIMITED_JAVA` | Restricted Java bridge. |
| `JAVA` | Unrestricted Java; additionally requires global God Mode. |
| `HOOKS` | Pine method hooks; additionally requires `JAVA`. |
| `FILES` | `readFile`, `writeFile`, `deleteFile` within this plugin's directory. |
| `INTERNET` | Asynchronous `httpGet`. |
| `OPEN_LINKS` | Opening HTTP(S) URLs in the browser. |

`onSendMessage` requires both read and send permissions. Notification observers
require unrestricted Java and read permission. Custom native screens require
unrestricted Java and UI permission.

For compatibility, UI starts enabled, and a declared `MESSAGING` scope is granted
on first load. Its five children inherit that grant unless explicitly set.
Declare `MESSAGING` together with any granular message scopes. Revoked choices
persist across reloads and updates. Java, hooks, files, internet and browser links
require explicit user grants even when declared in `plugin_scopes`.

```lua
plugin_scopes = {"MESSAGING", "READ_MESSAGES", "FILES"}

if xenon.hasPermission("FILES") then
    xenon.writeFile("state.txt", "hello")
end
```

Protected API calls raise a Lua error when access is denied. Use
`xenon.hasPermission` before an optional feature, or handle the error with `pcall`.
Scope names are uppercase; the manifest accepts single or double quoted strings.

An installed filename cannot be replaced by a different `plugin_id`: choose
another filename for a different plugin. Uninstalling clears that filename's
permissions, settings and mediated file storage, so a later installation cannot
inherit its privileges or private data. Watcher IDs use a length-prefixed owner
namespace; existing watchers establish a fresh baseline on this engine version.

## Execution and data limits

Lua callbacks serialize within a VM and have a two-second bytecode budget.
`pcall` cannot swallow budget exhaustion. Coroutines and Lua filesystem/process
libraries are removed. These guards do not provide process-level CPU or memory
isolation, and cannot interrupt an arbitrary Java/native call in progress.

Hook return values must contain only Lua primitives and tables, up to 4096 values
and 32 levels. The host copies them without metatables, so reading a result cannot
execute plugin code. Settings schemas use raw fields, with at most 512 rows and
256 options per list. Timers accept finite delays from zero to one year and are
limited to 256 pending timers per plugin.

Files use plain filenames, a 1 MiB per-file limit and a 16 MiB plugin quota.
`httpGet` supports HTTP(S) without embedded credentials, does not follow redirects,
and returns at most 1 MiB. It calls the callback with a table containing `status`
and `body`, or `error`. Revocation or unloading drops pending delivery.

Run the host-side regression checks without an Android build:

```sh
python3 -m unittest discover -s tests -p 'test_plugin_security.py' -v
```

The checks use the real LuaJ runtime and production guards with fake Android
scheduling and HTTP transport. They do not validate Android UI lifecycle, Pine
hooks or live Telegram requests; those still need testing in the built client.
