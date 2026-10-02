# Contributing to Pebble

Thanks for helping. Pebble is built in the open and every kind of contribution counts:
- Hinglish and Hindi phrasings
- bug reports
- Kotlin UI
- reminder logic
- model training
- speech accuracy for Indian accents

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help (no ML needed)

- **Tell us how you'd say things.** Pebble is only as good as the phrasings it has seen. Open a *"Pebble misunderstood me"* issue with what you typed or said, what Pebble did, and what you meant. These become test sentences and training data.
- **Report bugs** with the bug template. Include your Windows version and `%APPDATA%\Pebble\brain.log` if the brain is involved.
- **Pick an issue** labelled `good first issue` or `help wanted`, and comment on it so nobody duplicates the work.

## Development setup

```powershell
git clone https://github.com/Wickedsoni/pebble.git
cd pebble
./gradlew :desktopApp:run                     # JDK 21, Windows 10/11
./gradlew :shared:jvmTest :desktopApp:test    # all Kotlin tests
```

For the `brain/` (Python 3.11, [uv](https://docs.astral.sh/uv/)), see [brain/README.md](brain/README.md). Model files aren't in git. Tests that need them skip themselves and print `SKIPPED`.

## Branches and pull requests

- `main` is protected and always releasable. Nobody pushes to it directly, maintainers included.
- Work on a short-lived branch from `main`:
  - `feat/<topic>`: a new capability, e.g. `feat/weekday-parsing`
  - `fix/<topic>`: a bug fix
  - `model/<topic>`: a training or data change that produces a new model
  - `docs/<topic>`, `chore/<topic>`: docs, build, CI
- Open a pull request into `main`. CI must be green, and a maintainer reviews it. We **squash-merge**, so the PR title becomes the commit message. Write it in the imperative ("Add weekday parsing to HinglishTime").
- Keep PRs focused. One idea per PR is much easier to review.

## Code quality

CI runs everything below on every PR. Run it locally first:

```powershell
./gradlew spotlessApply                   # format Kotlin (ktlint, rules in .editorconfig)
./gradlew spotlessCheck :shared:jvmTest :desktopApp:test
cd brain
uvx ruff format . ; uvx ruff check .      # Python format + lint (config in brain/pyproject.toml)
uv run python -m unittest tests/test_feedback.py
```

- **Tests:** new behaviour comes with a test next to the existing ones (`shared/src/jvmTest`, `desktopApp/src/test`, `brain/tests`). Bug fixes come with a test that failed before the fix.
- **Style:** match the surrounding code. Comments explain *why*, not *what*. Public behaviour gets a KDoc or docstring.
- **No new dependency** without a reason in the PR description, and its licence must be permissive (Apache-2.0, MIT, BSD, CC0, CC BY).

## Changing the brain (models and data)

The model is shipped only when it beats the current one on frozen tests. Please keep that true:

1. **Never train on `brain/eval/`.** Generated data goes through the leak check in `pebble_data.py`.
2. A `model/*` PR must include the before/after numbers from:
   - `python -m pebble_brain.evaluate models/<new> v1`
   - `$env:PEBBLE_EVAL_MODEL="models/<new>-pruned"; ./gradlew :desktopApp:test --tests "*EvalSetRouterTest*" --rerun`
3. The gates in `EvalSetRouterTest` (right-or-first-choice, acted-wrongly) may only go up. The mood eval must stay ≥ 80%.
4. Data and models must be permissively licensed and recorded in `brain/data/licenses.json` and `NOTICE`. We don't train on outputs of proprietary models.
5. Any personal data in examples must be your own and anonymised: no real names, numbers or addresses.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.

## Licence of contributions

Pebble is licensed under [Apache-2.0](LICENSE). By submitting a contribution you agree that it's licensed under the same terms (Apache-2.0 §5). No separate CLA is needed.
