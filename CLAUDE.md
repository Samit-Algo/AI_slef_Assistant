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

## Always true

- The app has **no INTERNET permission**, ever.
- The Accessibility API is **never** used.
- Dependency injection is manual (`AppContainer`), no Hilt/Dagger.
- Never log personal data (file names, document names, contents). Use `SafeLog`.
- Hey Buddy suggests and fills, but **never acts alone** — the user always presses Submit.

## Owner preferences

- Use plain, simple words in explanations. Short steps.
