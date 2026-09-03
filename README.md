# Plume

Select text anywhere on Android. Fix it, or translate it, without leaving the app you're in.

Plume adds two entries to the system text-selection toolbar — the one with Cut / Copy / Paste —
so they show up in WhatsApp, Messages, Gmail, your browser, and almost everywhere else:

- **Revise** — corrects spelling, grammar, punctuation, accents and agreement, in whatever language
  the text is already in. In an editable field the corrected text replaces your selection directly.
- **Translate** — asks which language you want, detects the source language itself, and translates.

It is the Android counterpart to [MyReviser](https://github.com/paradoxe35/myreviser), which does
the same job on the desktop with a hotkey.

## How it works

Plume declares two activities with an `ACTION_PROCESS_TEXT` intent filter. Android inserts one
toolbar item per such activity, in every app that uses the standard selection toolbar. No
accessibility service, no overlay permission, no root.

When the selection came from an editable field, Plume returns the result with
`Intent.EXTRA_PROCESS_TEXT` and the host app swaps it in place. When the selection is read-only —
a received message, a web page — Android provides no way to write back, so Plume shows the result
with a Copy button instead. That is a platform limit, not a missing feature.

## Setup

1. Install and open Plume.
2. Go to **AI providers**, pick one, and paste an API key.
3. Tap **Test connection** to confirm the key, URL and model all work.

Built-in providers: **OpenAI**, **OpenRouter**, **Gemini**. You can add as many custom providers as
you like — anything speaking the OpenAI chat-completions format works, with presets for Groq,
Mistral, DeepSeek, Together and Ollama.

### Reasoning and timeouts

Correcting a sentence is not a reasoning problem, but reasoning models left on their defaults will
deliberate anyway — slowly, and at a cost. Each provider is set to ask for minimal deliberation by
default, and the default timeout is 120s because a model that thinks for a minute before answering
should not be cut off.

There is no portable parameter for this. OpenAI returns 400 for `reasoning_effort` on a
non-reasoning model, OpenRouter uses its own `reasoning` object and rejects requests carrying both
shapes, and Gemini refuses a zero thinking budget on models that cannot turn thinking off. So Plume
picks the shape per provider and, if the request is rejected with 400 or 422, **retries once without
the parameter and remembers not to send it to that model again**. The correction still succeeds; the
user never sees the negotiation. Turn it off per provider to send nothing at all.

### Providers without an API key

Local runtimes — Ollama, LM Studio, llama.cpp — take no credentials. Turn off "This provider needs
an API key" and Plume omits the `Authorization` header entirely rather than sending an empty one,
which some servers reject. Entering a local address offers the switch inline.

### Models

The model field loads the provider's live catalogue from its `/models` endpoint, so you pick from
what the provider actually offers rather than guessing a name. The field stays free text: catalogues
go stale, and private deployments expose names that never appear in a list.

### One provider or several

By default both actions use the same provider. You can override either one independently under
**AI providers → Which provider runs what** — a cheap fast model for corrections, a stronger one for
translation, for example.

### Prompts

Both prompts are editable, with a Reset that restores the default. The translate prompt uses a
`{{target_language}}` placeholder, substituted with whichever language you pick. Remove the
placeholder and Plume appends the target as a final line instead.

## The companion keyboard (optional, off by default)

Plume can also install a keyboard *panel* — not a typing keyboard, just Revise and Translate as
buttons. You switch to it while typing, run an action, and switch straight back.

Why it exists: an input method holds an `InputConnection`, which is the only way to read and rewrite
an entire text field **without a selection and without any permission**. Inside a message box that
is strictly more capable than the selection menu.

| | Selection menu | Companion keyboard |
|---|---|---|
| Needs you to select text | Yes | **No** |
| Fix the whole message | Select all first | One tap |
| Works on text you're only reading | **Yes** | No |
| Permissions | None | None |

They cover different halves, so both ship. The selection menu handles text you're *reading*; the
keyboard handles text you're *writing*.

It is **disabled by default**. The service is declared `android:enabled="false"`, so a default
install adds nothing to your system keyboard list — turning it on in Plume enables the component,
which is what makes Android aware of it at all. Turning it back off removes it again.

Enabling is a three-step journey the system owns most of, and Settings → Plume keyboard walks
through it: switch it on in Plume, switch it on in Android's keyboard list, then select it while
typing.

## Privacy

Selected text goes to the AI provider you configured, and nowhere else. Plume has no backend and no
analytics. API keys are encrypted with Google Tink using a master key held in the Android Keystore,
and are stored separately from the rest of your settings so they cannot leak through a settings
export.

## Install

A signed release build is committed at the repository root as `Plume.apk` — download it and
install. It is signed with a local key, so Android will ask you to allow installing from an
unknown source.

## Building

Requires JDK 17 and an Android SDK with API 36.

```bash
./gradlew testDebugUnitTest    # 148 unit tests
./gradlew assembleDebug
./gradlew assembleRelease      # signed if keystore.properties exists
```

`keystore.properties` and `local.properties` are machine-local and not committed.

To refresh the APK at the root after a release build:

```bash
cp app/build/outputs/apk/release/app-release.apk Plume.apk
```

## Stack

Kotlin, Jetpack Compose (Material 3), DataStore with kotlinx.serialization, OkHttp, Google Tink.

No dependency-injection framework and no navigation library: both entry activities launch cold from
another app's process while the user is mid-sentence, and cold-start latency is the whole product.
The settings screens are a plain back stack held in state.

Tests are JVM-only — MockWebServer for the provider clients, the engine and the keyboard panel;
Robolectric for the `ACTION_PROCESS_TEXT` intent contract and for the keyboard's read/replace path,
which runs against a real `EditText` and `InputConnection` rather than a fake.
