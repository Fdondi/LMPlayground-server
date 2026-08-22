# LM Playground inference API — wire protocol v1

LM Playground exposes its on-device llama.cpp engine to other apps through an
AIDL bound service. Payloads are **OpenAI-shaped JSON strings**, so migrating a
client from a remote OpenAI-compatible server means changing the transport and
keeping the data model.

All LM Playground extensions live under a single `"lmp"` key at each level, so a
strict OpenAI parser can ignore them entirely.

## Connecting

```xml
<!-- Client manifest. Required: minSdk 30+ means package-visibility filtering
     always applies, and without this queryIntentServices returns nothing. -->
<queries>
    <intent>
        <action android:name="com.druk.lmplayground.api.BIND_CHAT_SERVICE" />
    </intent>
</queries>
```

Resolve the service by **action**, never by package name — the debug build uses
`applicationIdSuffix = ".debug"`, so a hardcoded package silently fails against
a debug install:

```kotlin
val target = packageManager
    .queryIntentServices(Intent("com.druk.lmplayground.api.BIND_CHAT_SERVICE"), 0)
    .firstOrNull() ?: return  // LM Playground not installed
bindService(
    Intent(ACTION_BIND).setComponent(ComponentName(target.serviceInfo.packageName,
                                                   target.serviceInfo.name)),
    connection,
    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
)
```

`BIND_IMPORTANT` raises LM Playground's process importance while your UI is
visible, making it much less likely to be evicted mid-generation.

`LmPlaygroundClient` in this module does all of the above for you.

### Version handshake — do this first

Binder assigns transaction codes by declaration order. A newer client calling a
method an older service doesn't have gets an empty reply parcel that throws in
`readException()`. So **always** call `getApiVersion()` / `getServiceInfo()`
before anything else and gate on `features`.

```json
{
  "api_version": 1,
  "app_version_name": "1.9.1",
  "features": ["chat.stream", "chat.tools", "chat.vision", "models.list", "blobs"],
  "limits": { "max_request_bytes": 716800, "max_blob_bytes": 20971520 }
}
```

## `createChatCompletion(requestJson, callback)`

Returns immediately with an opaque `requestId`, or `""` on synchronous
rejection (in which case `onError` also fires, so you only need one error path).

### Request

```json
{
  "model": "auto",
  "messages": [
    { "role": "system", "content": "You are a terse assistant." },
    { "role": "user", "content": "What is in this picture?" },
    { "role": "assistant", "content": "A cat on a windowsill." },
    { "role": "user", "content": [
        { "type": "text", "text": "And this one?" },
        { "type": "image_url", "image_url": { "url": "lmp-blob:2f9c1a44-…" } }
    ]}
  ],
  "stream": true,
  "temperature": 0.8,
  "top_p": 0.95,
  "top_k": 40,
  "min_p": 0.05,
  "seed": -1,
  "max_tokens": 1024,
  "stop": ["\n\nUser:"],
  "tools": [
    { "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "Current local time.",
        "parameters": { "type": "object", "properties": {}, "required": [] }
      } }
  ],
  "tool_choice": "auto",

  "lmp": {
    "require": { "vision": true, "tools": true, "thinking": false, "min_context": 8192 },
    "allow_load": true,
    "context_size": 4096,
    "thinking": "auto",
    "thinking_budget": 2048,
    "timeout_ms": 300000,
    "client_label": "Recipe Helper",
    "continuation_token": null
  }
}
```

| Field | Meaning |
|---|---|
| `model` | A GGUF filename from `listModels()`, or `"auto"` / absent to let LM Playground choose. |
| `lmp.require` | **Minimum capabilities.** An omitted or `false` entry means *no constraint*; it never means "must not have". |
| `lmp.allow_load` | `false` forbids a headless load — the request only succeeds against an already-loaded model. |
| `lmp.context_size` | Defaults to **4096**, not the model maximum. Serving your request allocates a *second* KV cache alongside the user's chat; raising this is opt-in and can OOM the engine process. |
| `lmp.thinking` | `"auto" \| "on" \| "off"`. |
| `lmp.client_label` | Shown to the user. Cross-checked against your package label — never trusted on its own. |
| `lmp.continuation_token` | See [Tool calling](#tool-calling). |

**Rejected with `invalid_request_error`** rather than silently ignored: `n > 1`,
`response_format`, `logprobs`, `top_logprobs`, and the legacy `functions` /
`function_call` fields.

`stop` is enforced by scanning the accumulated output and truncating, because
the sampler has no stop-sequence parameter.

### Streaming chunks (`onChunk`)

Exactly `chat.completion.chunk`. Thinking goes to `delta.reasoning_content` (the
convention used by DeepSeek, vLLM and Ollama), visible output to
`delta.content`, so you can collapse reasoning without string-matching
`<think>` tags:

```json
{"id":"chatcmpl-lmp-8f21c0","object":"chat.completion.chunk","created":1787270400,
 "model":"Qwen3-4B-Q4_K_M.gguf",
 "choices":[{"index":0,"delta":{"content":" A tabby"},"finish_reason":null}]}
```

Chunks are **coalesced** (~20/s or every ~24 characters, whichever comes first).
`oneway` binder transactions share a ~1 MB per-process async buffer; one call
per token at 40 tok/s can overflow it and surface as a confusing
`TransactionTooLargeException` on a tiny payload.

### Terminal completion (`onComplete`)

The same `chat.completion` object for streamed and non-streamed requests:

```json
{
  "id": "chatcmpl-lmp-8f21c0",
  "object": "chat.completion",
  "created": 1787270400,
  "model": "Qwen3-4B-Q4_K_M.gguf",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "A tabby cat sitting on a windowsill.",
      "reasoning_content": "The image shows a small animal…"
    },
    "finish_reason": "stop"
  }],
  "usage": { "prompt_tokens": 0, "completion_tokens": 148, "total_tokens": 148 },
  "lmp": {
    "reasoning_tokens": 62,
    "duration_ms": 4210,
    "model_was_preloaded": true,
    "headless_load_ms": 0,
    "warnings": []
  }
}
```

`usage.prompt_tokens` is **always 0** — the engine does not expose a prompt
token count across the process boundary. Reporting 0 and saying so beats
inventing a number.

`finish_reason` ∈ `stop | length | tool_calls | cancelled | error`.

## Vision

Two ways to attach an image; both are valid from day one.

**Inline `data:` URL** — matches OpenAI exactly, and the simplest thing that
works:

```json
{ "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,/9j/4AAQSk…" } }
```

The budget: the whole request crosses the binder as one string, measured as
`length * 2` (UTF-16). 700 KB ⇒ ≤ 358 400 characters of JSON ⇒ **≤ ~262 KB of
raw image bytes** after base64's 4/3 inflation.

**`putBlob(pfd, mimeType, sizeBytes)`** — for anything larger. File descriptors
are not counted against transaction size, so this bypasses the cap entirely.
Returns `lmp-blob:<uuid>`, which you then use as an `image_url`. Cap 20 MB.
The blob is deleted when the consuming request completes, or after 10 minutes.

Either way LM Playground re-downscales what you send (768 px max, JPEG q85)
before handing it to the vision encoder — it never trusts a client to have done
that. **One image per turn**; two in the same message is an
`invalid_request_error`. An image against a non-vision model is a
`capability_unavailable` (declare `lmp.require.vision: true` and you'll get that
error at resolution time instead of after a wasted load).

## Tool calling

Tools run **in your process**. LM Playground never executes a caller's tools,
and its own built-in tools (web search, page fetch, JavaScript) are not exposed.

1. Send `tools[]`. If the model emits calls, `onComplete` arrives with
   `finish_reason: "tool_calls"`:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{ "id": "call_0", "type": "function",
                       "function": { "name": "get_current_time", "arguments": "{}" } }]
    },
    "finish_reason": "tool_calls"
  }],
  "lmp": { "continuation_token": "cont_5b1e77a2", "continuation_expires_ms": 60000 }
}
```

2. Execute the calls yourself.
3. Re-send the **whole conversation**, with the assistant `tool_calls` message
   and one `{ "role": "tool", "tool_call_id": "call_0", "content": "…" }` per
   call appended, and `lmp.continuation_token` set to the token you got back.

`continuation_token` is an **optimization hint, not required state**. With it,
LM Playground resumes from the live KV cache using the model's own tool-response
template — exact continuity. Without it (expired, evicted, different caller,
app restarted) the conversation is replayed and the tool round trip is flattened
into text; the response then carries `lmp.warnings: ["tool_history_flattened"]`.
Either way the request succeeds, because your request always carries the
complete conversation.

Tokens expire after 60 s idle, and at most 2 are parked at once (each holds a
live inference context).

## `listModels()`

```json
{
  "object": "list",
  "data": [{
    "id": "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
    "object": "model",
    "created": 1772150400,
    "owned_by": "lm-playground",
    "lmp": {
      "display_name": "Qwen 3.5 4B",
      "downloaded": true, "loaded": false, "custom": false,
      "size_bytes": 2415919104,
      "languages": ["en","zh","fr","de","ja"],
      "capabilities": { "vision": true, "tools": true, "thinking": true,
                        "verified": false, "max_context": null }
    }
  }],
  "lmp": { "api_version": 1, "loaded_model": "gemma-3-1b-it-Q4_K_M.gguf",
           "engine_busy": false, "storage_configured": true }
}
```

Only downloaded models are listed.

### `capabilities.verified` — read this before matching requirements

Capabilities come from two sources and they are not equally trustworthy:

- **Unverified** (`verified: false`) — static catalog hints. `max_context` is
  `null` because determining it requires actually loading the model.
- **Verified** (`verified: true`) — read from the GGUF's own chat template the
  first time the model was loaded, and cached.

So `lmp.require` is matched **best-effort before a load and authoritatively
after one**. If a headless load reveals the model doesn't actually meet your
requirements, it is unloaded, the real capabilities are cached (so your next
request resolves correctly), and you get `capability_unavailable`.

## Model selection

LM Playground will **never unload the model the user is working with.**

| Situation | Outcome |
|---|---|
| A model is loaded and meets `lmp.require` | Served on a **new independent session** — the user's chat KV cache is untouched. |
| A model is loaded but you named a different one | `model_mismatch`, with candidates. Retry with `"model": "auto"`. |
| A model is loaded but fails `lmp.require` | `capability_unavailable`, naming the loaded model and listing downloaded models that *would* work. |
| Nothing is loaded, `allow_load: true` | The **smallest** downloaded model meeting your requirements is loaded headlessly, then unloaded after 5 minutes idle. Expect a multi-second first-token latency. |
| Nothing is loaded, `allow_load: false` | `no_model_loaded`. |
| Nothing downloaded qualifies | `no_model_available`, listing what is downloaded. |

Only one API generation runs at a time. A second concurrent request waits up to
15 s and then gets `engine_busy` with `lmp.retry_after_ms`. The user's own chat
is never queued behind an API request.

## Errors

```json
{
  "error": {
    "message": "The loaded model 'Gemma 3 1B' does not support image input. LM Playground will not unload a model the user is using.",
    "type": "capability_unavailable",
    "param": "lmp.require.vision",
    "code": "lmp_capability_unavailable",
    "lmp": {
      "http_status": 409,
      "loaded_model": "gemma-3-1b-it-Q4_K_M.gguf",
      "candidates": [
        { "id": "Qwen_Qwen3.5-2B-Q3_K_M.gguf", "display_name": "Qwen 3.5 2B", "downloaded": true }
      ],
      "partial_content": null,
      "retry_after_ms": null
    }
  }
}
```

`lmp.http_status` is the status this error would carry over HTTP, so a client
with existing status-code handling can reuse it directly.

| `type` | HTTP | When |
|---|---|---|
| `invalid_request_error` | 400 | Malformed JSON, no messages, unsupported field |
| `permission_denied` | 403 | The user has disabled API access, or a policy denied you |
| `model_not_found` | 404 | The named model is not downloaded |
| `capability_unavailable` | 409 | The loaded model fails `lmp.require` |
| `model_mismatch` | 409 | You named a model other than the loaded one |
| `payload_too_large` | 413 | Over `max_request_bytes` — use `putBlob` |
| `no_model_available` | 503 | Nothing downloaded satisfies the requirements |
| `no_model_loaded` | 503 | `allow_load: false` and nothing is loaded |
| `engine_busy` | 503 | Retry after `lmp.retry_after_ms` |
| `engine_unavailable` | 503 | Engine crashed, or the user unloaded mid-request; check `lmp.partial_content` |
| `cancelled` | 499 | You called `cancel()`, or your process died |
| `internal_error` | 500 | Anything else |

## Cancellation and lifetimes

- `cancel(requestId)` is idempotent and safe to call after completion.
- If **your** process dies, LM Playground notices via the callback binder's
  death recipient and cancels the generation — a killed client cannot leave the
  CPU pegged.
- If **LM Playground's** process dies, your `ServiceConnection` and death
  recipient fire; in-flight requests fail with `engine_unavailable`. Android
  re-creates the service on the next bind, but the loaded model is gone.
- Requests are capped by `lmp.timeout_ms` (default 5 minutes) and by
  `max_tokens`.

## Compatibility rules

The AIDL interface is **append-only**. Methods are never reordered, removed, or
re-signed, because binder transaction codes are positional and your compiled
client is not rebuilt when LM Playground updates. `getApiVersion()` is
transaction 0 forever and is the one method safe to call without
feature-detecting first.

New capabilities appear as new entries in `getServiceInfo().features` and new
optional keys under `"lmp"`. Unknown JSON keys are ignored in both directions.
