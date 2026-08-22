# DONE — public inference API + proof-of-concept client

**Status: code complete, statically verified, not yet run on a device.**

This file is a handoff. Everything below the "Needs a device" line is untested
by me and needs someone with hardware.

> Uncommitted on purpose — this is a scratch status file, not repository
> history. If you're picking this up from a remote session that pulls rather
> than sharing this worktree, commit it on a branch first.

---

## What was built

An exported AIDL service that lets any app on the device run inference through
LM Playground, with OpenAI-shaped JSON payloads so a developer moving off a
remote server changes their transport and keeps their data model. Plus a
standalone chat app that proves it works from the outside.

Three properties drove the design:

1. **The user's chat is never disturbed.** An API request opens its *own*
   session on the model already loaded, so the chat's KV cache is untouched.
   If the loaded model doesn't meet the request's requirements, the request is
   refused with a list of models that would work — never by evicting several
   gigabytes the user was in the middle of using.
2. **Access control is a real seam, permissive by default.** Checked per binder
   transaction via `Binder.getCallingUid()`, with a user-facing off switch
   shipped. Allowlist, signature pinning, bearer token and interactive consent
   all fit the existing `ApiAccessPolicy.check()` signature without a change.
3. **The transport is swappable.** `ChatCompletionHandler` writes to a
   `ResponseSink` and knows nothing about binder. A loopback HTTP server is a
   second `ResponseSink` implementation plus a status-code mapping —
   `ApiError.httpStatus` is already in the wire format for exactly that.

### New modules

| Path | What |
|---|---|
| `playground-api/` | The public contract: 2 `.aidl` files, JSON codecs, typed models, `LmPlaygroundClient` SDK, `PROTOCOL.md`. Zero new external dependencies — JSON is platform `org.json`, matching the repo convention. |
| `samples/chat-client/` | The PoC chat app. Depends on `:playground-api` and **nothing else** from this repo — that constraint is what proves the API is genuinely public. |

### New code in `:app` (`com.druk.lmplayground.api`)

`ApiService` (exported Stub) → `ChatCompletionHandler` (transport-agnostic) →
`EngineArbiter` (mutex, model publication, crash collector) → `ApiTurnRunner`.
Supporting: `ApiAccessPolicy`, `BinderResponseSink`, `ResponseSink`,
`HeadlessModelManager`, `ApiModelResolver`, `ApiHistoryMapper`,
`StreamDeltaTracker`, `ParkedToolTurns`, `BlobStore`.

### Files touched (existing code)

Small and enumerable on purpose:

- `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` — module wiring.
- `app/src/main/AndroidManifest.xml` — one `<service>` block.
- `app/proguard-rules.pro` — one keep rule.
- `App.kt` — three `lateinit` singletons + a hoisted `StorageRepository` local.
- `ModelRuntime.kt` — a nested `SharedModelSink` interface, one nullable trailing
  constructor param (defaulted, so every test construction still compiles), six
  one-line publish calls, and a custom setter on the existing `generatingJob`.
- `ConversationViewModel.kt` — one added argument.
- `ChatImageStore.kt` — a `ByteArray` overload of `resizeImageForVision`,
  sharing the existing scale-and-compress path.
- `StoragePreferences.kt` + `AdvancedViewModel/Screen/Fragment`,
  `SettingsFragment.kt`, `strings.xml` — the "Allow other apps" toggle.
- `.github/workflows/pull-request-check.yml` — lint and build the new modules.
- `ARCHITECTURE.md`, `README.md`.

---

## Verified — I ran these and they passed

| Check | Result |
|---|---|
| `./gradlew :playground-api:assembleDebug` | ✅ |
| `./gradlew :samples:chat-client:assembleDebug` | ✅ APK produced |
| `./gradlew :app:compileDebugKotlin -PnoVulkan` | ✅ |
| `./gradlew :app:compileDebugAndroidTestKotlin -PnoVulkan` | ✅ |
| `./gradlew :app:testDebugUnitTest -PskipScreenshots` | ✅ **226 tests, 0 failures** (25 suites, 67 of them new) |
| `./gradlew :app:lintDebug` | ✅ **0 errors** (65 warnings, all pre-existing/baselined) |
| `./gradlew :playground-api:lintDebug :samples:chat-client:lintDebug` | ✅ clean, no baseline |
| `./gradlew :app:processDebugMainManifest` | ✅ exported service present with the right action |
| `./gradlew :app:minifyReleaseWithR8 -PnoVulkan` | ✅ and the mapping confirms `IChatService`, `$Stub`, `$Stub$Proxy`, the callback and `ApiService` all map to **themselves** despite `-repackageclasses` |

**The sample's isolation is verified, not just asserted.** Unpacking
`chat-client-debug.apk`: it contains `IChatService`, `IChatCompletionCallback`
and `LmPlaygroundClient`, and contains **zero** classes from
`lmplayground/conversation`, `lmplayground/inference` or `com.druk.llamacpp`,
and no inference native libraries (the only `.so` is AndroidX's own
`libandroidx.graphics.path`). So the demo really does run on the public
contract alone. Its 61 MB is unminified debug Compose plus `ui-tooling`,
not leaked engine.

Two plan assumptions I checked empirically rather than trusting:

- **The AAR really carries the AIDL contract.** `classes.jar` contains
  `IChatService.class`, `IChatService$Stub.class`, `$Stub$Proxy.class` and the
  callback equivalents, so `:app` and any client link the *same* classes —
  identical descriptor, identical transaction codes, no consumer compiles AIDL.
  `aidlPackagedList` additionally exports the `.aidl` sources into `aidl/`.
- **No new foreground service is needed.** `LlamaService.promoteToForeground()`
  already wraps `startForeground` in `catch (t: Throwable)`, and
  `ForegroundServiceStartNotAllowedException extends IllegalStateException`, so
  the Android 12+ background-start block is already tolerated.

### New tests

| Suite | Tests | Covers |
|---|---:|---|
| `ApiCodecTest` | 21 | Wire schema both directions; rejects `n>1`/`response_format`/`logprobs`/`functions`; every error type → its documented HTTP status; `ApiLimits` pinned to `InferenceLimits`. |
| `ApiModelResolverTest` | 15 | The whole selection table. The load-bearing one is `foregroundFailingRequirementsIsRefusedNotEvicted`. |
| `ApiHistoryMapperTest` | 13 | Turn pairing, same-role merge, tool flattening, image rules, payload budget. |
| `ParkedToolTurnsTest` | 7 | Continuation tokens, incl. rejecting one presented by a different UID. |
| `StreamDeltaTrackerTest` | 7 | Concatenated deltas always equal the final text, including across `ResponseProcessor`'s non-monotonic separator rewrite. |
| `ApiTransactionOrderTest` | 4 | Fails CI if anyone reorders the AIDL methods. |

---

## Needs a device — nothing below has been executed

**No model has been loaded and no token has streamed.** Every runtime claim in
this change is unverified: the binder transactions have never actually crossed a
process boundary, and the arbiter's concurrency has only been reasoned about,
not observed.

```bash
./gradlew :app:installDebug :samples:chat-client:installDebug
adb logcat -s EngineArbiter HeadlessModelManager ApiService ApiTurnRunner
```

If `connectedAndroidTest` has run on the device, re-pick the SAF model folder
first — it wipes the grant (see ARCHITECTURE.md).

### Checklist

- [ ] `./gradlew :app:assembleDebug -PnoVulkan` — **the full native build never ran in this session.** I only compiled Kotlin.
- [ ] `./gradlew :app:mvdApi35Check` — runs the new `ApiServiceTest` (compiles, never executed). It needs no GGUF.
- [ ] Load **Qwen 3 1.7B** in LM Playground, send one chat message so there's real KV state. Open the demo → banner connects; the model sheet shows it `loaded`, `verified`, with a real `max_context`.
- [ ] Demo: "Count to five slowly." Tokens stream. **Then switch back to LM Playground and confirm the chat history and token counts are exactly as left.** This is the acceptance test for the never-disturb-the-user property.
- [ ] Turn on **Tools**, ask "what time is it?" → the sample runs `get_current_time` itself and re-sends. Check logcat shows the *parked-session* path, not the flatten fallback (a flatten shows up as `lmp.warnings: ["tool_history_flattened"]`).
- [ ] Vision: load **Qwen 3.5 2B** (with its mmproj) and send an image. Repeat with a >1 MB photo to exercise `putBlob` rather than the inline `data:` path.
- [ ] Unload the model in LM Playground **while the demo is streaming** → demo shows `engine_unavailable` with `partial_content`; the chat is unaffected.
- [ ] Two rapid requests → the second gets `engine_busy` with `retry_after_ms`.
- [ ] `adb shell am force-stop com.druk.lmplayground.debug` mid-stream → demo disconnects and auto-reconnects.
- [ ] Unload everything, background LM Playground, send from the demo → watch the headless load, then the unload ~5 minutes later.
- [ ] Debug-only `crashForTest` mid-stream → the demo must error **within a second**, not after 30. (This is the whole point of the crash collector cancelling without joining.)
- [ ] Load **Gemma 3 1B**, toggle "Require vision" in the demo → `capability_unavailable` naming Gemma and listing Qwen candidates, **and Gemma stays loaded**.
- [ ] Settings → Advanced → turn **Allow other apps** off → the demo's next request gets `permission_denied`. It should take effect immediately, without rebinding.
- [ ] Release build: `:app:installRelease`, repeat the streaming and tool steps — proves R8 didn't break the descriptor/transaction contract.

---

## Known gaps and deferred items

- **R8 is proven statically, not at runtime.** The mapping shows the public API
  classes surviving unrenamed, so the descriptor and transaction codes are
  intact — but a release APK has never actually served a request. The last
  checklist item still matters.
- **Two `llama_context`s is not free.** Serving an API request on the user's
  model allocates a second KV cache. Mitigated by defaulting `lmp.context_size`
  to 4096 rather than the model maximum, destroying the session every turn, and
  capping parked tool sessions at 2 — but on a device that already trips the
  `DeviceCapability` RAM heuristic this is a plausible `:llama` OOM. Worth
  watching on real hardware.
- **`dataSync` FGS timeout on Android 15+** (targetSdk 36): cumulative ~6h/24h,
  then `Service.onTimeout` fires and failing to stop is an ANR. `LlamaService`
  doesn't override it. **Pre-existing**, but repeated headless loads amplify it.
  The 5-minute idle unload (which demotes the FGS) is a partial mitigation.
  An `onTimeout` override is a reasonable follow-up.
- **Preamble KV cache is off for API turns.** `PreambleCacheManager` keys files
  on (model, system prompt, tools); arbitrary third-party system prompts would
  thrash the directory the chat depends on. Revisit with a separate subdirectory
  and its own prune budget.
- **Play data-safety declaration should be reviewed before release.** An
  exported endpoint lets any installed app enumerate the user's downloaded model
  filenames and spend their battery on prompts. That is a real change to the
  app's stated fully-offline positioning, even though nothing leaves the device.
  The Settings toggle is shipped; the store listing hasn't been looked at.
- **Two new strings are untranslated** (`external_api_title`,
  `external_api_desc`), marked `tools:ignore="MissingTranslation"` rather than
  buried in the 3,500-line lint baseline so the translation pass can find them.
  The app ships 28 locales.
- **`usage.prompt_tokens` is always 0.** The engine doesn't expose a prompt
  token count over AIDL. Documented in `PROTOCOL.md` rather than faked.
- **Images in conversation history are dropped** (only the final turn's image is
  sent), with an `lmp.warnings` entry. `replayHistory` is text-only and
  `setImageData` applies to the next `addMessage`, so earlier images genuinely
  can't be reconstructed.

## Where to start reading

- `playground-api/PROTOCOL.md` — the contract, with worked JSON.
- `ARCHITECTURE.md` § "Public inference API" — how it fits the existing engine.
- `samples/chat-client/README.md` — what the sample is meant to demonstrate.
- `EngineArbiter.kt` — the concurrency and crash-handling reasoning.
