# GradenTranslator 0.2.0-dev

LSPosed Modern API module for translating Compose UI strings in Graden (`com.graden.editor`).

## This development snapshot adds

- `libxposed:service:102.0.1` integration in the module app.
- A service listener in the module `Application` and a dedicated LSPosed RemotePreferences group (`gradentranslator`).
- Migration of the first prototype's local API settings into the new draft format.
- Settings for API Base URL, Chat Completions path, API key, model ID, target language, batch size and timeout.
- An API test that translates `New project` before the user enables the translation hook.
- Asynchronous batched requests with JSON Schema → JSON object → plain JSON fallback.
- A SQLite translation cache stored under Graden's application-private data directory (only translations, never the API key).
- Weak references to Compose recomposition scopes; after a result is cached the module attempts to invalidate the corresponding scope.
- Default strict allowlist for known static UI labels extracted from the Graden v0.5.0-beta Compose source, plus filters for paths, URLs, media extensions and timecodes.
- Optional “translate unknown short text” switch. It is off by default because runtime text hooks cannot reliably distinguish a short user project title from a static UI label based on the string alone.
- Cache keys include the target language, so switching between Chinese and another target language does not reuse an old-language translation.
- Basic placeholder / format-token validation before accepting model output.

## Build

The repository workflow builds this project from `GradenTranslator-cloud-build-source/` and uploads a debug APK artifact. Open GitHub → Actions → GradenTranslator Android Build.

## First launch after installing this version

1. Enable GradenTranslator in LSPosed and keep `com.graden.editor` in scope.
2. Open GradenTranslator's settings app once so the `libxposed/service` provider can connect to LSPosed.
3. Enter the API Base URL, endpoint path, model ID and API key.
4. Tap **Test API**. Do not save a key into GitHub, a commit, or a screenshot.
5. Tap **Save and sync to LSPosed**, then force-stop and reopen Graden.

The module settings app keeps a local draft in its Android app-private storage. The active Graden process reads the key and other settings from LSPosed RemotePreferences. Translation results are cached in Graden's app-private SQLite database. The local draft is app-sandboxed but is not encrypted by this development snapshot; keep the API key out of screenshots, logs and commits.

## Known limitations

- This is still a development build. Compose method overloads have not been verified against the exact installed Graden 0.5.0-beta DEX on the user's phone.
- The current hook handles `String` overloads. `AnnotatedString` range/style preservation is not implemented yet.
- The strict UI allowlist is deliberately conservative and incomplete. Some labels may remain English until added. Enabling the unknown-text option improves coverage but can translate user-created text; the hook cannot infer meaning from text content alone.
- Recomposition is best-effort through the Compose `Composer`'s current `RecomposeScope`; if Graden's Compose compiler/runtime differs, some translated labels may only update on a later redraw.
- The API client assumes Bearer authentication and OpenAI Chat Completions-compatible request/response shapes. Custom headers, provider profiles, model discovery and non-OpenAI protocols are not included in this snapshot.
- Dictionary overrides, dictionary import/export, richer retry/backoff controls and multi-profile configuration remain future work.
- A successful CI build does not prove the runtime hook works. Check LSPosed logs and test Graden carefully.
