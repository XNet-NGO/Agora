# Spec: Live Voice transcript persistence into chat

Status: **design only — not implemented.** Two build options below. The context-correctness
(AI Studio / Gemini tool-call cleansing) work is a prerequisite that is ALREADY DONE in the
codebase (see "Context correctness" section); no new cleansing code is required for either option
as long as voice turns are persisted as PLAIN TEXT.

---

## Problem

A Live Voice session (Gemini Live API, `com.newoether.agora.voice.live.*`) streams audio +
in/out transcriptions. Today the transcripts show only in an ephemeral bar above the composer
(`LiveVoiceTranscriptBar`) and are lost when the session ends. We want the spoken conversation to
land in the chat as real messages (like AIOPE does via its `messageSink`), so it persists and
appears in history.

### Why it's non-trivial in Agora (vs AIOPE)

- **AIOPE** has a FLAT message table; its `VoiceSessionController.messageSink` is a one-liner
  `chatDao.insertMessage(...)`. It persists plain user/assistant text per turn (assistant deltas
  buffered, flushed on turn/stop). No tool-protocol messages are persisted.
- **Agora** has a **Run-graph with branch selection**. A message requires a non-null `runId` and
  participates in a tree (`parentId`, `selectedBranchesJson`, `selectedRunBranchesJson`). A loose
  `insertMessage` is NOT enough — the turn must belong to a Run and be "selected" to be visible,
  and must not corrupt the live-generation state machine.

### Key constraint (verified)

Persist voice turns as **PLAIN TEXT user/assistant messages only** — never as tool-protocol
(`tool_`/`result_`) messages. Rationale under "Context correctness". This matches AIOPE and keeps
persisted turns trivially valid for the Gemini request builder.

---

## Context correctness (ALREADY IMPLEMENTED — do not rebuild)

The user's concern: "when having a multi-agent chat using Google's API the chat context,
especially tool calls, needs to be on point / cleansed to fit AI Studio's API."

This is already handled. Every Gemini request (chat, subagent, multi-agent) flows through ONE
choke point and is cleansed/validated:

`GeminiProvider.generateResponse` (GeminiProvider.kt:245)
 → `buildApiContents` (GeminiProvider.kt:256, called at :432)
   → `adaptToolRoundsForProvider(messages, providerName, isCompatible)` (ToolMessages.kt)
       • groups each `tool_` message with its following `result_` messages
       • `isGeminiToolRoundCompatible` (GeminiProvider.kt:59) decides per-round compatibility
       • INCOMPATIBLE round → `toolRoundAsPlainContext(...)` degrades the whole round to plain text
       • `normalizeToolResults` REJECTS a round unless every call has exactly one result (no orphans)
       • `stripEmptyTurns(mergeConsecutiveSameRole(...))` fixes role ordering
   → `coalesceGeminiContents(...)` (GeminiRequestValidator.kt:7) merges adjacent same-role contents
   → `requireValidWireFormat(modelName)` (GeminiRequestValidator.kt:22) HARD backstop — validates:
       • role ∈ {user, model}, no repeated consecutive roles
       • pending `functionCall` answered by `functionResponse` immediately, same IDs, exactly once
       • no orphan functionResponse, response name matches call name
       • THROWS (collects violations, require-empty) on any violation

Implication: **plain-text voice turns cannot create an orphaned tool round**, so they pass this
machinery trivially. No changes needed here. If we ever persist voice TOOL calls as tool-protocol
messages, they would need valid paired IDs or the adapter would (correctly) degrade them to text.

---

## Option A — Terminal-Run persistence (RECOMMENDED, correct & visible)

Persist each completed voice exchange as ONE terminal Run (user msg + assistant msg) appended to
the conversation tip, visible in history.

### Mechanism
- New `ConversationRepository.persistVoiceExchange(conversationId, userText, assistantText, modelName)`.
- Build via the terminal-run primitive `ChatDao.importRunGraph(runs, messages)` (requires
  `runs.all { it.status.isTerminal }`; messages reference imported/existing runs). This path does
  NOT assume live generation and does NOT trip the "no existing live Run" check, so it won't race.
- Steps:
  1. Resolve the conversation tip (current selected leaf message id) for `parentId` linkage.
  2. Create a terminal `RunEntity` (status = a terminal RunStatus e.g. COMPLETED/STOPPED,
     `activeSlot = null`, `parentRunId` = current selected run, `currentPass`, `legacyAmbiguous=false`).
  3. Create two `MessageEntity`: USER (text=userText, runSequence 0, parentId=tip) then MODEL
     (text=assistantText, runSequence 1, parentId=USER msg id), both `status=SUCCESS`,
     `runId=run.id`, `modelName` set, `sourceJson` marking voice origin (optional, see MessageSource).
  4. `importRunGraph(listOf(run), listOf(userMsg, modelMsg))`.
  5. Update branch selection so the turns are VISIBLE: set `selectedBranchesJson` /
     `selectedRunBranchesJson` to point the parent→this run and parent-msg→these messages
     (mirror `updateConversationForRunAdmission` / the selection map logic in ChatDao
     createRunWithMessages around lines 199–305). This is the intricate part.
  6. `touchConversationData(conversationId, now)` so the list re-sorts.
- GUARD: no-op (or queue) if a live Run exists for the conversation
  (`chatDao.getLiveRun(conversationId) != null`) to avoid racing active generation.

### Wiring
- Add `messageSink` to `LiveVoiceSessionController` (callback `(userText, assistantText) -> Unit`),
  mirroring AIOPE: persist the USER transcript when a turn completes, buffer assistant
  OutputTranscription deltas, flush the assistant message on `TurnComplete`/`stop()`.
- In `ChatViewModel`, set `LiveVoiceSession.controller`'s sink to call
  `conversationRepository.persistVoiceExchange(currentConversationId, ...)` on `appScope`.
- Use the CURRENT chat conversation id (so the voice turns land in the open chat). If invoked
  headlessly (overlay with no open chat), create/append to a dedicated "Voice" conversation.

### Pros / Cons
+ Correct, matches Agora's architecture; turns appear as normal chat and persist.
+ Reuses the proven terminal-run import primitive.
− Branch-selection update is delicate (step 5). Must be unit-tested against the selection-map
  invariants. Risk of invisible-but-inserted messages if selection is wrong.
− Headless (overlay) path needs a target conversation.

### Effort: M (~1–2 focused hrs + on-device verify). Risk: medium (branch selection).

---

## Option B — Explicit "Save to chat" action (lighter, lower risk)

Keep transcripts ephemeral during the call; add a one-tap "Save conversation to chat" control that
persists the full accumulated transcript as ONE exchange when the user chooses.

### Mechanism
- Controller already accumulates `userTranscript` / `assistantTranscript` StateFlows.
- On "Save", call the SAME `persistVoiceExchange(...)` (Option A's repo method) once with the full
  accumulated user+assistant text (or a small list of exchanges if we track turn boundaries).
- Button lives in the `LiveVoiceTranscriptBar` (a save icon) and/or Settings > Live Voice.

### Pros / Cons
+ Same persistence primitive, but invoked deliberately → simpler lifecycle, no per-turn race, user
  controls what gets saved.
+ Avoids auto-persist surprises (e.g. accidental voice sessions polluting history).
− Not automatic; user must tap. Less "it just works" than AIOPE.
− Still needs the correct Run+selection write (same step 5 intricacy) — so it does NOT remove the
  hard part, only the lifecycle/race part.

### Effort: S–M. Risk: low–medium (same selection write, simpler timing).

---

## Recommendation

Build the shared primitive FIRST: `ConversationRepository.persistVoiceExchange(...)` with a proper
unit test for the Run + branch-selection write (the only risky part). Then:
- Ship **Option A** (auto-persist on turn/stop) for parity with AIOPE if we want "just works".
- Keep **Option B**'s save action as a fallback/opt-in if auto-persist proves noisy.

Both options are PLAIN-TEXT only, so the existing Gemini cleansing covers correctness with zero
new validator code.

## Verification (either option)
1. Live call, speak, end → user+assistant turns appear in the chat and survive app restart.
2. Immediately send a normal TEXT turn in the same conversation to a Gemini model → NO Google 400
   (confirms persisted voice turns pass `requireValidWireFormat`).
3. Multi-agent/subagent run in the same conversation afterward → still no 400.
4. Guard: starting a normal generation while a voice turn is mid-persist does not corrupt the Run
   graph (persist no-ops/queues when a live Run exists).
