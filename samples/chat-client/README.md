# LMP API Demo — a chat app built on LM Playground's public API

A working chat client that runs its model inside **LM Playground** instead of
shipping one of its own.

The constraint that makes this a useful sample: it depends on
`:playground-api` and **nothing else** from this repository. No `:app`, no
shared internals, no privileged access. Everything it does — streaming tokens,
collapsible reasoning, model selection, capability requirements, client-side
tool calling — goes through the same exported AIDL surface any third-party app
would use.

## Run it

```bash
./gradlew :app:installDebug :samples:chat-client:installDebug
```

Then open LM Playground, download a model and load it, and switch to
**LMP API Demo**. (You can also send a message with nothing loaded — LM
Playground will load the smallest suitable model headlessly and unload it five
minutes later.)

## What to look at

| File | Why |
|---|---|
| [`DemoViewModel.kt`](src/main/java/com/druk/lmplayground/sample/chatclient/DemoViewModel.kt) | The whole integration: connect, degradation handling, the request, and the tool loop. |
| [`LocalTools.kt`](src/main/java/com/druk/lmplayground/sample/chatclient/LocalTools.kt) | Tools that execute **in this process**. LM Playground never sees this code. |
| [`ui/StatusBanner.kt`](src/main/java/com/druk/lmplayground/sample/chatclient/ui/StatusBanner.kt) | Every degraded state, each with the recovery that actually applies. |
| [`AndroidManifest.xml`](src/main/AndroidManifest.xml) | The `<queries>` block. Without it, discovery silently finds nothing on API 30+. |

## Things this sample exists to demonstrate

**Discovery by action, not package name.** LM Playground's debug build has an
`applicationId` ending in `.debug`, so a client that hardcodes
`com.druk.lmplayground` fails against exactly the install a developer is most
likely to be testing with. `LmPlaygroundClient.discover()` resolves the intent
action instead.

**The user's chat is not yours to disturb.** Ask for vision while the user has
a text-only model loaded and you get `capability_unavailable` naming their
model and listing ones that would work — not a silent eviction of several
gigabytes they were in the middle of using. Toggle "Require vision" and try it.

**Tools run on your side.** Turn on **Tools** and ask "what time is it?". The
model emits a tool call, this app executes `get_current_time` itself, and sends
the result back with the continuation token so LM Playground resumes from the
live KV cache. LM Playground's own tools (web search, page fetch, JavaScript)
are deliberately not exposed.

**Failure is a first-class state.** Kill LM Playground mid-stream
(`adb shell am force-stop com.druk.lmplayground.debug`) and the banner shows
the disconnect, keeps whatever streamed, and reconnects.
