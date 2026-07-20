# Lessons

## 2026-06-12 — Live TV "cursor disappears" is a FAMILY of bugs, not one bug (4th incarnation)
v3.5.6 (focus-loss debounce), v3.6.4 (preview focusability), v3.8.0 (preview D-pad dead-end), and now
v4.0.1 (focus not restored on return from fullscreen + focused/selected category styles identical) were
all reported as the same symptom: "the gold cursor is gone."
**Rule:** any change touching Live TV navigation/playback flow must end with the full loop test ON DEVICE:
open Live TV → scroll channels → OK to fullscreen → zap → BACK → *verify the gold cursor is visible and
on the channel that was playing*. Also verify the cursor is distinguishable on every list when it sits on
an already-"selected" item (focused state must differ from selected state by more than a gradient alpha).

## 2026-06-12 — A new screen isn't done until its in-screen context switching exists
User deliberately waited to see if I'd catch that the new TV Guide had no way to switch categories from
inside the guide (entry was category-scoped only). I didn't catch it; they did.
**Rule:** before declaring a new screen complete, walk its full UX loop: every entry point, every
navigation axis, *changing the screen's scope/context from within it*, and every exit. If the screen is
scoped by something (category, profile, date), there must be an in-screen way to change that scope.

## 2026-07-19 — A phone-touch "improvement" bricked every Ooustick; and isTV() code needs isTV() layouts
Customer Ooustick (Allwinner H616) could not leave the Home screen on v4.2.3. Root cause was NOT the
version or the ABI — the box reports `mCurUiMode=0x11` (NORMAL) and declares no `leanback` /
`television` / **`touchscreen`** feature, so `DeviceUtils.isTV()` (which tested uiMode ALONE) returned
false and `sw540dp < 600` made `isPhone()` true. All 72 `isTV()/isPhone()` call sites across 37 files
flipped to touch mode. v4.2.0's `TouchGridSetup.stripItemFocusForTouch()` then set
`isFocusable = false` on every card (10 call sites in HomeFragment) and `MainActivity`'s sidebar was
already TV-gated → no cursor, no menu, stranded. Git-verified: v4.1.0 had no card focus-strip; the
regression is v4.2.0 (`eef9171`).
**Rule 1:** never classify a device by `UI_MODE_TYPE_TELEVISION` alone. Generic boxes don't set it.
Use `!hasSystemFeature(FEATURE_TOUCHSCREEN)` as the discriminator and fail SAFE toward TV — a TV UI is
usable by finger, a phone UI is unusable by remote.
**Rule 2 (the one that bit me mid-fix):** `values-television/` and `layout-television/` are selected by
the **OS from uiMode**, which Kotlin cannot influence. Fixing `isTV()` alone made MainActivity skip the
Material theme while the OS still handed it the *phone* `activity_main.xml`, whose
`BottomNavigationView` can't inflate under Leanback → hard crash at `setContentView`. **If a code path
is gated on `isTV()`, its layout must be gated on `isTV()` too — never on the `-television` qualifier.**
Fix: moved it to `layout/activity_main_tv.xml`, chosen at runtime.
**Rule 3:** before shipping a "phone/mobile UX" pass, ask which shipping devices will be *classified*
as phones. Test on an Ooustick, not just a Fire TV — they take opposite branches.

## 2026-07-18 — Don't report a review's verdict from its in-progress journal
Read a review workflow's `journal.jsonl` while its verify pass was still running, saw only the
finished reviewers, and told the user "all 3 candidates refuted." The real final result was 12
candidates / **4 confirmed** — all genuine bugs in my own new code. Reporting a verification result I
hadn't finished collecting is exactly the No-Assume violation the global rule warns about.
**Rule:** a workflow's authoritative result is its FINAL synthesized return value (the task-completion
notification / the `return {...}` object), never the partial `journal.jsonl` mid-run. Do not state
"reviewed / all clear" until the workflow has actually completed and you've read its final output. If
you must peek at progress, label it explicitly as in-progress, not as the verdict.

## 2026-06-12 — Never read ViewModel identity fields inside launched coroutines
`PlayerViewModel.saveProgress`/`markCompleted` read `streamId` etc. inside `viewModelScope.launch` bodies;
any caller that synchronously swaps identity right after calling them (gapless binge advance; legacy
advance with cached series info) attributes the write to the WRONG content.
**Rule:** snapshot all identity state into locals/an entity BEFORE `launch`.
