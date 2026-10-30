# Hey Buddy — rules for Claude Code

**Read `docs/HEY_BUDDY_SPEC.md` in full before any work.** It is the source of truth. Follow its rules and task order.

## Working rules

1. **Follow the spec exactly.** Never change a "Fixed decision" (spec section 2) without asking the owner first.
2. **One task at a time** from spec section 5.10, in order. Never start the next task on your own.
3. **Plan first.** Before writing code for a task, show a short plan: the files you will create or change, and how you will test it. Wait for the owner's "OK".
4. **Code style** (spec section 5.8):
   - Small, single-purpose functions. No clever one-liners.
   - Clear variable names; explicit intermediate variables instead of long call chains.
   - KDoc on every public class and function. Type annotations on public APIs.
   - Comments explain *why*, not *what*.
   - No magic numbers — put them in `Constants.kt` with a comment.
   - User-facing errors are in simple English and say what to do next.
5. **No new libraries.** Only the libraries in spec section 5.9. Ask before adding anything else. Ask before using a pre-release version.
6. **After each task, report:** what you built, how to test it, and which "Done when" checks pass (and any that do not).

## Git rules

- After the owner approves a task's report, commit with the message `Task X.Y: <short name>` and push to `origin/main`.
- Before committing, run `git status`. If any file is outside the project's normal folders, or could contain a secret, key, password, token or personal document, STOP and show the owner instead of committing.
- Never force-push.

## Testing on the owner's phone

- Allowed without asking: `adb devices`, install, launch, read logcat, run tests.
- Ask first: uninstalling the app, clearing app data, or any other adb command that changes the phone.
- Run instrumented tests with `adb shell am instrument`, not Gradle's `connectedAndroidTest`, because that one uninstalls the app afterwards.
- After each task: run only the NEW or CHANGED tests for that task, plus the owner's manual check. No full run.
- One full test run only at the end of each phase, or when shared security code (keys, encryption, database, lock) changes. Tell the owner before starting a full run and why.
- Do not repeat automatically what the owner has already tested by hand, and do not ask the owner to re-test things that already passed.
- Standing permission (owner, Task 1.10p): before any FULL phone test run you may run `adb shell svc power stayon usb`. You must ALWAYS run `adb shell svc power stayon false` right after the run, even if it fails or is stopped, and always say in the report that it is off.

## Always true

- The app has **no INTERNET permission**, ever.
- The Accessibility API is **never** used.
- Dependency injection is manual (`AppContainer`), no Hilt/Dagger.
- Never log personal data (file names, document names, contents). Use `SafeLog`.
- Hey Buddy suggests and fills, but **never acts alone** — the user always presses Submit.
- Never process identity document images from the internet or from unknown people. Test only with generated SAMPLE documents or the owner's own documents.

## Owner preferences

- Use plain, simple words in explanations. Short steps.
