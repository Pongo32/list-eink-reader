# AGENTS.md — Reader project instructions

These instructions apply to coding agents working on the Android E-Ink reader described in [ТЗ-электронная-читалка-Android-4.md](ТЗ-электронная-читалка-Android-4.md). Preserve existing user work and make focused, verifiable changes.

## Product and scope

- Build a simple offline-first reader for older Android 4.x E-Ink devices.
- PDF and EPUB are the first-release formats. Treat DjVu, FB2, MOBI, and CBZ as unconfirmed until a concrete engine/version is tested.
- The core user flow is open a book and read. Detection, PDF adaptation, caching, and position persistence should be automatic.
- The first page must appear before background conversion finishes. If k2pdfopt fails, silently continue with the original document; show a concise error only when the original cannot be opened.
- The first release is assumed to be for personal use, not store distribution. Revisit licensing before any wider distribution.

## Project status and decision boundaries

- The technical specification records proposed architecture and open questions; it is not proof that any component has been built or validated.
- Before implementation, confirm the exact device model, Android version/API, CPU ABI, RAM, screen size/resolution/DPI, E-Ink refresh API, supported formats, and distribution plan.
- MuPDF and k2pdfopt compatibility, build flags, licenses, and output formats must be verified for the actual target device. Do not treat estimates or unverified CLI examples as confirmed facts.
- Resolve material product or platform ambiguities with the user before committing to an implementation that affects compatibility or data behavior.

## Architecture and technology

- Target Java with native Android `Activity`/`View`; avoid AndroidX/AppCompat/Kotlin unless compatibility is demonstrated for the agreed Android version.
- MuPDF via JNI is the candidate renderer for PDF and EPUB. Confirm supported formats and Android API compatibility for the selected release.
- Build k2pdfopt from source for the target ARM ABI and run it as a separate process where practical. The provided x86-64 Linux binary is not an Android ARM executable.
- Use a compatible background worker for conversion, a custom `View`/`Canvas` for the reader, and `SharedPreferences` for small settings and reading positions.
- Keep document access behind a small document-management boundary; keep UI independent from conversion details.
- Respect API-specific storage and URI access. Android 4 does not provide Storage Access Framework on all target versions.

## E-Ink UX and performance

- Keep reading pages high-contrast and free of textured backgrounds behind body text.
- Use a limited grayscale palette, no page-turn animation, and no UI that blocks reading while conversion runs.
- Support hardware page keys and tap zones; expose pinch font sizing for EPUB only if it is reliable on the target device.
- Keep only a small bounded number of rendered pages in memory; convert PDF in page ranges and use a bounded cache.
- Measure first-page latency, conversion time, memory, APK size, and battery impact on real hardware. Do not state performance targets as achieved without measurements.
- Treat vendor E-Ink APIs as optional, device-specific integrations with a safe fallback.

## Data safety, privacy, and licensing

- Never alter or delete an original book. Keep source files, generated output, cache, and reading state separate.
- Validate file URIs, file contents, and persisted/imported values at boundaries. Handle corrupt or unsupported documents without crashing or losing user data.
- Identify a document by a stable content-based identifier where feasible; preserve reading position across moves and renames.
- Do not log book contents, private paths, or personal data. Do not send documents or reading history over the network without an explicit product requirement and user-visible consent.
- Check MuPDF's AGPL/commercial terms and k2pdfopt's GPLv3 obligations before distribution. Personal use is not a substitute for confirming the terms of a specific release.

## Engineering workflow

- Read relevant source, configuration, and call sites before editing. Search for existing helpers and reuse project conventions.
- Keep changes incremental. Do not add frameworks, dependencies, storage schemas, or background services without a demonstrated need.
- Use strict types where available; validate external input; avoid broad catches and silent fallbacks that hide failure.
- Add focused tests for new behavior when a test harness exists. Verify a regression test fails for the expected reason before implementing a fix where practical.
- Run the smallest relevant test/build command, inspect its complete output and exit status, and report checks not run. Do not claim a build or test passed without fresh evidence.
- Review the final diff and run `git diff --check`. Do not commit or publish unless explicitly asked.
- Update this specification and related documentation when an approved design decision changes.

## Test-only tools and TOON logs

- `test-tools/web-to-app/` is an isolated upstream checkout reserved strictly for test/evaluation use. It is not a reader dependency or a source of production code.
- Use the checkout only when the task explicitly concerns testing/evaluation. Do not modify its upstream files as part of normal reader development.
- Record actions and outcomes involving the checkout in `test-tools/actions.toon`. Keep the TOON row count accurate and never include credentials, book contents, or personal data.

## Workflow reference

- For applicable code changes, consult [Superpowers test-driven development](https://github.com/obra/superpowers/tree/main/skills/test-driven-development) and [verification-before-completion](https://github.com/obra/superpowers/tree/main/skills/verification-before-completion) as process references only.
- Do not install Superpowers or treat it as an application dependency.
