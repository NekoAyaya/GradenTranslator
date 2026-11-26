# GradenTranslator

LSPosed Modern API prototype for translating Graden (`com.graden.editor`) Compose UI text.

## Build
GitHub Actions builds a debug APK on every push to `main`, or manually via Actions → Android APK → Run workflow.

## Included in this prototype
- LSPosed Modern API 102 entry and Graden-only scope.
- Candidate hooks for Material 3 / Material / Foundation Compose text methods.
- Basic UI-text filter to avoid common paths, URLs, media extensions and timecodes.
- OpenAI Chat Completions-compatible translation with in-memory and SharedPreferences cache.
- Basic settings screen for Base URL, model and API key.

## Known limitations
This is an early development build, not a verified release:
- Compose overloads and parameter positions have not yet been validated against the installed Graden 0.5.0-beta APK.
- String overloads only; `AnnotatedString` styling preservation is not implemented.
- Compose recomposition refresh after background translation is not implemented; translated text may not appear until a later redraw/restart.
- API key storage is currently ordinary app preferences, not encrypted.
- Translation is one request per string; batching, retry/backoff, dictionary editor/import-export and profiles remain unfinished.
- CI compilation does not prove runtime hook compatibility.

Do not treat a successful CI build as proof that all Graden text is translated. Test cautiously.
