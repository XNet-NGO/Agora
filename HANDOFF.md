# HANDOFF — Agora (XNet fork) session state

_Written 2026-10-28 before context compaction + server reboot. Read this top-to-bottom to resume._

---

## 1. Where things are RIGHT NOW

- **Repo:** `/home/ubuntu/projects/Agora` (XNet fork of newo-ether/Agora).
  - `origin` = `https://github.com/XNet-NGO/Agora.git`
  - `upstream` = `https://github.com/newo-ether/Agora.git`
- **Current branch:** `feat/embedding-picker-and-live-voice` (NOT merged, NOT pushed as of writing).
- **Working tree:** clean (only untracked `gradle.properties.bak`, ignore it).
- **master tip:** `638d13e9` (PR #30 sandbox terminal/extrakeys merged).
- **Latest release tag:** `v2.1.0-xnet.26` (next would be `.27`).
- **Device:** Pixel 10 Pro XL, codename `mustang`, arm64, over network ADB at
  `100.81.64.120:5555` (also a flaky portmux tunnel `127.0.0.1:2222/17100`). Frequently drops;
  reconnect with `adb connect 100.81.64.120:5555`. Prefer push-install (see §6) when flaky.

### Commits on this branch (newest first), all local-only:
```
70919ee9 feat(voice): send agent tools + MCP to the live session with tool round-trip
88d820e4 feat(voice): force model resync on fetch-schema bump + live conversation transcription
43acdbc0 fix(voice): audible Live playback (speakerphone route) + seed Google live models
532024b7 fix(voice): live-voice shutdown crash, composer phone button, model-list filter, example model
7753a891 feat(voice): Live Voice (Gemini Live API, direct route) + settings section
73055851 feat(embedding): auto-populate model picker from provider model lists
```

---

## 2. What this branch delivers (all built + installed on the Pixel; last signed release installed)

### A. Embedding model picker auto-populates from provider lists (`73055851`)
- `ui/settings/ProviderModelPicker.kt` — reusable `buildProviderModels(availableModels,
  customProviders, providerBaseUrls, modelFilter)` → `List<ProviderModels(name,baseUrl,models,isCustom)>`;
  `BuiltInRemoteProviders` list. Used by the embedding editor AND Live Voice.
- `ui/settings/EmbeddingModelEditor.kt` — model field now shows an "Available models" dropdown
  (string `embedding_model_suggestions`) unioning the provider's fetched models with presets; free
  text still allowed.

### B. Live Voice (Gemini Live API, DIRECT Google route only — gateway intentionally dropped)
Package `com.newoether.agora.voice.live`:
- `LiveStreamEvent.kt` — sealed events + `LiveAudioConfig` (16k in / 24k out).
- `LiveVoiceConfig.kt` — persisted config (enabled/provider/model/voiceName/systemInstruction);
  `GOOGLE_VOICES`; `GOOGLE_LIVE_MODELS` seed list; `looksLiveCapable(id)` matches live/realtime/native-audio.
- `LiveVoiceAudioManager.kt` — AudioRecord(16k VOICE_COMMUNICATION)+AudioTrack(24k); AEC; **forces
  speakerphone** (MODE_IN_COMMUNICATION + setSpeakerphoneOn) on start, restores on stop; all
  start/stop are `@Synchronized` + state-guarded (fixes the `AudioRecord.native_stop`
  IllegalStateException crash).
- `LiveVoiceStreamer.kt` — WS to
  `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=KEY`.
  Sends setup (audio modality, voiceConfig, in/out transcription, session resumption, context
  compression, `functionDeclarations` from the tool bridge), parses serverContent (audio→playback,
  text, in/out transcription, turnComplete, interrupted→clearPlayback), handles `toolCall` →
  bridge.execute → `toolResponse`. Own coroutine scope cancelled on stop.
- `LiveVoiceSessionController.kt` — self-contained lifecycle; `start(context, config, apiKey,
  toolBridge?)` / `stop()` / `sendText()`; StateFlows `state` (IDLE/CONNECTING/ACTIVE),
  `userTranscript`, `assistantTranscript`; `turns` SharedFlow; okHttp readTimeout 0.
- `LiveVoiceSession.kt` — process-scoped singleton `controller` so composer button + settings share
  ONE session.
- `LiveToolBridge.kt` (interface, voice.live) — `functionDeclarations(): JSONArray` +
  `suspend execute(name, argsJson): String`.

Persistence of config: `SettingsManager.liveVoiceConfig` flow + `saveLiveVoiceConfig` (key
`LIVE_VOICE_CONFIG_JSON` in SettingsPreferenceSchema.kt); `SettingsRepository.liveVoiceConfig`
StateFlow + `setLiveVoiceConfig`.

UI:
- `ui/settings/SettingsLiveVoicePage.kt` — "Live Voice" settings section (category `livevoice`,
  `GraphicEq` icon, registered in `SettingsScreen.kt` after imagegen + dispatched in
  `SettingsTwoPane.kt`). Enable toggle, provider picker, Live model picker (seeded
  `GOOGLE_LIVE_MODELS` + fetched, free text), voice picker, system instruction, Start/Stop test.
- `ui/chat/bottombar/ComposerLiveVoiceButton.kt` — phone (Call/CallEnd) button next to the STT mic
  (`ComposerMicButton`); rendered via the new `liveVoiceButton` slot param in `ChatBottomBar.kt`,
  wired from `ChatApp.kt`; hidden unless config enabled+model set; gates on key + RECORD_AUDIO.
- `ui/chat/bottombar/LiveVoiceTranscriptBar.kt` — shows running in/out transcript above the
  composer during a session (rendered in a Column wrapping ChatBottomBar in ChatApp.kt).

Tools + MCP in the live session (`70919ee9`):
- `voice.live/LiveToolBridge.kt` (interface) + `viewmodel/LiveVoiceToolBridge.kt` (impl) over the
  SAME `GenerationToolExecutor` normal generation uses (so MCP + all providers are included).
  `definitions()`→Google `functionDeclarations` via `ToolSchemaJson.of(...)`; executes via
  `AuthorizedToolCall` with a synthetic live-voice `RunEffectIdentity`.
- `GenerationManager.buildLiveToolBridge(ctx)` wraps its `toolExecutor`.
- `ChatViewModel.buildLiveToolBridge()` builds a `GenerationContext` from settings toggles
  (shell/web/memory/rag/todo) and delegates. Composer button passes it into `controller.start`.

### Model list fix (`532024b7` + `88d820e4`)
- `api/gemini/GeminiProvider.kt` fetchModels filter was `generateContent`-only → **hid embedding
  (embedContent) + Live (bidiGenerateContent) models**. Now keeps all three. (OpenAI side already
  unfiltered.)
- Root cause of "still only 49 models": the fix is in the dex but the `availableModels` cache was
  stale (auto-refetch is fingerprint-gated). Fixed by folding `MODEL_FETCH_SCHEMA_VERSION=2` into
  `ProviderRegistry.computeFingerprint()` so this build forces a ONE-TIME resync. VERIFY post-reboot
  that the cache repopulated to ~58 Google models and Live models appear.

VERIFIED ON DEVICE (logs): `gemini-3.8-live` connects (WS 101), setupComplete, audio frames
delivered, clean stop (no crash). AUDIBILITY fix (speakerphone) shipped but user had reported "no
audio" before it; re-verify audio is now heard.

---

## 3. NEXT TASK (in progress, design-only) — persist voice transcripts to chat

See `docs/LIVE-VOICE-PERSISTENCE-SPEC.md` (written this session) for full detail. Summary:
- User wants live transcripts persisted into the chat as real messages (like AIOPE's `messageSink`).
- **GROUNDED DECISION: persist PLAIN TEXT user/assistant turns only** (AIOPE does the same). This
  keeps them trivially valid for the Gemini request builder (see §4).
- **The hard part is Agora's Run-graph + branch selection**, not the sink. Build a shared
  `ConversationRepository.persistVoiceExchange(conversationId, userText, assistantText, modelName)`
  using the terminal-run primitive `ChatDao.importRunGraph(runs, messages)` + a branch-selection
  update (mirror `updateConversationForRunAdmission` / the selection map in
  `ChatDao.createRunWithMessages` ~lines 199–305). Guard: no-op if `chatDao.getLiveRun(convId)!=null`.
- Two options in the spec: **A** = auto-persist on turn/stop (parity w/ AIOPE); **B** = explicit
  "Save to chat" action. Both use the same repo primitive; A is recommended, B is lower-risk timing.
- Wiring: add a `messageSink` to `LiveVoiceSessionController`; set it in `ChatViewModel` to call the
  repo on `appScope`, targeting the current conversation (or a dedicated "Voice" conversation when
  headless).

---

## 4. CRITICAL: Google / AI Studio context correctness — ALREADY DONE, do NOT rebuild

User concern (recurring): multi-agent/Google requests need tool-call context "on point / cleansed
to fit AI Studio's API." This is already solid. Single choke point for ALL Gemini requests
(chat, subagent, multi-agent):

`GeminiProvider.generateResponse` (:245) → `buildApiContents` (:256, used :432):
- `adaptToolRoundsForProvider` (ToolMessages.kt): groups `tool_`+`result_` msgs; degrades
  INCOMPATIBLE rounds to plain text (`toolRoundAsPlainContext`); `normalizeToolResults` rejects a
  round unless every call has exactly one result; `mergeConsecutiveSameRole` + `stripEmptyTurns`.
- `isGeminiToolRoundCompatible` (GeminiProvider.kt:59) — per-round compatibility (+ gemini-3
  thoughtSignature requirement).
- `coalesceGeminiContents` (GeminiRequestValidator.kt:7) — role alternation merge.
- `requireValidWireFormat` (GeminiRequestValidator.kt:22) — HARD backstop; THROWS on: bad role,
  repeated role, orphan functionResponse, pending functionCall not answered exactly once by id,
  name mismatch.

=> Plain-text voice turns can't make an orphaned tool round, so they pass trivially. No new
cleansing code needed for the persistence feature.

---

## 5. BUILD / SIZE RULES (hard constraints — will fail the build)

- **800-line cap per handwritten Kotlin file**, enforced by `verifyKotlinFileSize` (plugin
  `buildlogic.kotlin-source-size`). Baseline `config/kotlin-source-size-baseline.txt` is
  intentionally EMPTY and MUST stay empty (adding entries is rejected). Fix oversize by splitting
  into new files, never by baselining.
- **Files currently AT/NEAR the cap — watch when editing:**
  - `api/gemini/GeminiProvider.kt` = **800** (at the limit; any edit must stay ≤800)
  - `viewmodel/GenerationManager.kt` = **799**
  - `data/repository/SettingsRepository.kt` = **787**
  - `data/SettingsManager.kt` = **759**
- **Gradle memory (committed in gradle.properties):** `org.gradle.jvmargs=-Xmx4g
  -XX:MaxMetaspaceSize=1g`, `org.gradle.workers.max=1`, `org.gradle.parallel=false`. This was
  required because R8 release minification OOMed at 2g under parallel workers. Do NOT lower.
- Verify a file's size with `wc -l`. Run `./gradlew verifyKotlinFileSize` before committing.

---

## 6. BUILD / SIGN / INSTALL playbook

### Debug build + size check (fast dev loop)
```
cd /home/ubuntu/projects/Agora
./gradlew compileFdroidDebugKotlin -x verifyKotlinFileSize 2>&1 | grep -E "^e: |BUILD|FAILED"
./gradlew assembleFdroidDebug verifyKotlinFileSize
```
(Debug variant is x86_64/screenshots app id `com.newoether.agora.screenshots` — NOT for the arm64
Pixel. Use the signed release to install on the device.)

### Signed release via the encrypted vault (REQUIRED for device install; ~12–14 min, R8)
Signing key lives ONLY in the GPG vault. Follow `/home/ubuntu/projects/xnet-keystore/AGENTS.md`
rules: decrypt to tmpfs, inject via env, shred, NEVER print the passphrase/keys.
```
set -euo pipefail
cd /home/ubuntu/projects/Agora
KS=/home/ubuntu/projects/xnet-keystore
PASSFILE=~/.ssh/xnet-grapheneos-vault.passphrase            # mode 600, agent-readable
WORK="$(mktemp -d /dev/shm/sign.XXXXXX)"; chmod 700 "$WORK"
trap 'find "$WORK" -type f -exec shred -u {} \; 2>/dev/null; rm -rf "$WORK"' EXIT
gpg --batch --pinentry-mode loopback --passphrase-fd 3 \
    -o - -d "$KS/grapheneos/mustang/xnet-grapheneos-keys.vault.gpg" 3<"$PASSFILE" 2>/dev/null \
    | tar -C "$WORK" -xf -
APP="$WORK/app-signing"; source "$APP/secrets.env"
export ORG_GRADLE_PROJECT_xnetStoreFile="$APP/xnet-upload.keystore"
export ORG_GRADLE_PROJECT_xnetStorePassword="$XNET_UPLOAD_PASS"
export ORG_GRADLE_PROJECT_xnetKeyAlias="$XNET_UPLOAD_ALIAS"
export ORG_GRADLE_PROJECT_xnetKeyPassword="$XNET_UPLOAD_PASS"
./gradlew assembleFdroidRelease
```
Signing config (app/build.gradle.kts): release signing resolves from those `ORG_GRADLE_PROJECT_*`
env vars first, then local.properties; `hasKeystore` requires the file to exist (else debug
fallback). Signer identity: `CN=XNET, OU=Dev, O=XNET`.

### Install on the Pixel (streamed install drops on the flaky tunnel — use push)
```
adb connect 100.81.64.120:5555
D=100.81.64.120:5555
adb -s $D push app/build/outputs/apk/fdroid/release/app-fdroid-release.apk /data/local/tmp/agora.apk
adb -s $D shell pm install -r /data/local/tmp/agora.apk
adb -s $D shell rm -f /data/local/tmp/agora.apk
```
Launch + crash check:
```
adb -s $D logcat -c; adb -s $D shell monkey -p com.newoether.agora -c android.intent.category.LAUNCHER 1
adb -s $D logcat -d | grep -iE "FATAL|AndroidRuntime.*newoether|LiveVoice|AudioRecord"
```

---

## 7. Release pipeline (when ready to ship this branch)
1. `git push -u origin feat/embedding-picker-and-live-voice`
2. `gh pr create --base master --head feat/embedding-picker-and-live-voice --title ... --body ...`
3. verify no conflicts (`git merge-tree --write-tree origin/master origin/<branch>`), `gh pr merge <n> --merge`
4. `git checkout master && git pull --ff-only origin master`
5. tag `v2.1.0-xnet.27` (annotated), `git push origin v2.1.0-xnet.27`
6. build the TAGGED signed release (§6), verify `apksigner verify --print-certs`, single
   `libonnxruntime.so` in arm64 APK, sherpa jni present.
7. `gh release create v2.1.0-xnet.27 <named.apk> --title ... --target master --notes ...` (include sha256).
versionCode stays 31 (tag-driven, not bumped per release).

---

## 8. Other context worth knowing
- STT (sherpa-onnx) shipped earlier: `com.newoether.agora.stt.*` (SherpaSttEngine/Bootstrap/
  VoiceInputManager + warm-preload SttEngineHolder). sherpa AAR bundles ORT 1.28.2 = vendored core;
  `pickFirst **/libonnxruntime.so`; non-arm64 sherpa libs excluded. R8 keep rules for
  `com.k2fsa.sherpa.onnx.**` (minified builds otherwise fail "Failed to get field ID for
  decodingMethod"). STT model: Kroko tar.gz from `XNet-NGO/deps` release `sherpa-stt-en-kroko`.
- AIOPE source for grounding: `/home/ubuntu/projects/aiope` (Hilt multi-module; Agora is
  single-module manual DI via `AppContainer`). Voice ref:
  `feature-chat/engine/RealtimeStreaming.kt` (DUAL path — we ported DIRECT only),
  `RealtimeAudioManager.kt`, `VoiceSessionController.kt` (messageSink persistence pattern).
- AIOPE feature inventory (stale, pre-xnet.19): `/home/ubuntu/projects/AIOPE-feature-inventory.md`.
  Reconciled: Theming + STT already shipped; remaining AIOPE gaps = App-lock (biometric+TOTP,
  `aiope/core-auth/`), Dynamic UI (`aiope/.../dynamicui/`), terminal emulator panel.
- git identity is set to `xnetone <admin@foxnet.live>`.
- **SECURITY:** the user pasted a live AI Studio API key in chat this session
  (`AQ.Ab8RN6JP...`). It MUST be rotated — treat as compromised. It was used only to query the
  Google model list; never committed.

---

## 9. Immediate resume checklist after reboot
1. `cd /home/ubuntu/projects/Agora && git branch --show-current` → expect
   `feat/embedding-picker-and-live-voice`, clean tree.
2. `adb connect 100.81.64.120:5555` and confirm `device`.
3. Confirm the installed build is current (the 6 commits above). If re-verifying the 49→58 model
   fix: open Settings > Models (triggers the fingerprint-forced resync), then Settings > Live Voice
   and confirm Live models list.
4. Decide persistence Option A vs B (see `docs/LIVE-VOICE-PERSISTENCE-SPEC.md`) and build the shared
   `persistVoiceExchange` primitive WITH a unit test for the Run + branch-selection write (the only
   risky part). Persist PLAIN TEXT only.
5. Then run the release pipeline (§7) if shipping.
