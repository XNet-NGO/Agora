# Porting AIOPE tools to Agora

This branch (`port-aiope-tools`) ports a set of tools from **AIOPE** (XNet-NGO/aiope) into the
**Agora** fork (XNet-NGO/Agora), following Agora's existing `ToolProvider` architecture.

## Scope

The following AIOPE capabilities were ported:

| AIOPE tool | Agora tool(s) | Notes |
|---|---|---|
| `todo_write` / `todo_read` | `todo_write` / `todo_read` | File-backed working todo list (`TodoStore`) |
| `get_location` / `search_location` | `get_location` / `search_location` | GPS + platform Geocoder; requires location permission |
| `introspect` | `introspect` | Self-knowledge from bundled manual asset |
| Network scanner | `network_scan` | LAN host discovery via ICMP ping |
| File server | `file_server_start` / `file_server_stop` / `file_server_status` | Lightweight HTTP file server |

## Architecture fit

Each ported tool is a self-contained `ToolProvider` in `app/src/main/java/com/newoether/agora/tool/`,
registered in `GenerationToolExecutor.createDefault()`. Enablement is gated by new flags on
`GenerationContext`, backed by DataStore settings in `SettingsManager` / `SettingsRepository`
(preference keys in `SettingsPreferenceSchema.kt`). All tools default to **disabled**; enable them
via the settings surface.

### Files added
- `app/src/main/java/com/newoether/agora/data/TodoStore.kt`
- `app/src/main/java/com/newoether/agora/tool/TodoToolProvider.kt`
- `app/src/main/java/com/newoether/agora/tool/LocationToolProvider.kt`
- `app/src/main/java/com/newoether/agora/tool/IntrospectToolProvider.kt`
- `app/src/main/java/com/newoether/agora/tool/NetworkScannerToolProvider.kt`
- `app/src/main/java/com/newoether/agora/tool/FileServerToolProvider.kt`
- `app/src/main/assets/manual/agora-manual.md` (introspect source)

### Files modified
- `app/src/main/AndroidManifest.xml` — added location permissions
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationContracts.kt` — new `GenerationContext` flags
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationToolExecutor.kt` — registered providers
- `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt` — wired flags into context
- `app/src/main/java/com/newoether/agora/data/SettingsPreferenceSchema.kt` — new preference keys
- `app/src/main/java/com/newoether/agora/data/SettingsManager.kt` — new settings flows + setters
- `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt` — new StateFlows + setters

## Design decisions

- **Todo** uses a simple JSON file store rather than Room, matching AIOPE's lightweight scratch-list
  semantics and keeping it distinct from Agora's durable Tasks/Loops automation.
- **Location** uses the platform `Geocoder` (no network dependency) and respects the user's location
  permission. `search_location` requires the permission because it resolves coordinates.
- **Introspect** uses a bundled Markdown manual with a keyword scorer instead of an embedding model,
  keeping it dependency-free.
- **Network scanner** does ICMP host discovery only. Full port/banner scanning is a documented
  follow-up.
- **File server** is a minimal single-threaded HTTP server (read-only, MIME detection). Uploads,
  HTTPS, and PIN protection are documented follow-ups.

## Follow-ups

- Add a settings UI surface to toggle each new tool (currently wired to settings but no screen).
- Full port/banner scanning in the network scanner.
- Uploads, HTTPS, and PIN protection for the file server.
- Consider porting device actions (SMS, calendar, alarms) as additional `ToolProvider`s.

## License

This fork is derived from Agora (GPL-3.0). All new code in this branch is contributed under the
GPL-3.0 license in effect for the fork. The AIOPE-derived tool concepts are reimplemented for the
Agora architecture; no AIOPE source is copied verbatim.