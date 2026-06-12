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

## 2026-06-12 — Never read ViewModel identity fields inside launched coroutines
`PlayerViewModel.saveProgress`/`markCompleted` read `streamId` etc. inside `viewModelScope.launch` bodies;
any caller that synchronously swaps identity right after calling them (gapless binge advance; legacy
advance with cached series info) attributes the write to the WRONG content.
**Rule:** snapshot all identity state into locals/an entity BEFORE `launch`.
