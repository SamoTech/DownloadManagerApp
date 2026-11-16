# Evidence Ledger

| Criterion | Check (exact command or action) | Result (what you saw) | Status |
|---|---|---|---|
| R8 release minification & `mapping.txt` generation | `gradle :app:minifyReleaseWithR8 :app:testDebugUnitTest --console=plain && ls -lh app/build/outputs/mapping/release/mapping.txt` | `> Task :app:minifyReleaseWithR8 BUILD SUCCESSFUL`, generated `app/build/outputs/mapping/release/mapping.txt` (30M) | pass |
| Unit test suite including `phase11_releaseR8DeobfuscationAndProguardConfigVerified` | `gradle :app:testDebugUnitTest --console=plain` | All 30 Robolectric / JUnit tests pass (`BUILD SUCCESSFUL`) | pass |
| Full applet compilation | `compile_applet` | `Build succeeded - the applet is compiled` | pass |
