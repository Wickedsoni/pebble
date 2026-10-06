## What and why

<!-- One or two sentences. Link the issue: "Fixes #123". -->

## How it was tested

<!-- Tests added or run. For UI changes, a screenshot or short clip. -->

## Checklist

- [ ] `./gradlew spotlessCheck :shared:jvmTest :desktopApp:test` passes (and `uvx ruff@0.16.10 check .` / `uvx ruff@0.16.10 format --check .` in `brain/` if Python changed)
- [ ] New behaviour has a test; bug fixes have a test that failed before
- [ ] No new dependency, or its purpose and permissive licence are explained above
- [ ] **Model/data changes only:** before → after numbers from `evaluate` and `EvalSetRouterTest`, nothing trained on `brain/eval/`, licences added to `brain/data/licenses.json` and `NOTICE`
