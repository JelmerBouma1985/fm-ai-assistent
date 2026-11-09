# Updater implementation validation — 2026-11-09

Host: native Windows, GraalVM CE/OpenJDK 25.0.2, PowerShell. No Linux machine
was used during this implementation. No release, tag, push or publishing
workflow was performed. Both release-gate flags remain false pending owner
review and the missing platform/manual checks.

## Executed on Windows

- Maven fast tests, full `clean verify`, ArchUnit and JaCoCo minimum gates.
  Final combined `./mvnw.cmd clean verify -Pdesktop`: **BUILD SUCCESS**, 417
  tests discovered, 406 executed successfully, 11 skipped, zero failures/errors.
  The skipped set includes two Linux-specific updater tests.
- Desktop packaging with `-DskipTests verify -Pdesktop`; real output is
  `target/dist/FM AI Assistent/FM AI Assistent.exe`, with `app/`, `runtime/`,
  `updater/helper.jar`, `updater/runtime/bin/java.exe` and `app-update.properties`.
- Two actual stable Windows images built in disposable source copies:
  `2.1.0` and `2.1.1`. A further baseline built from this checkout embeds
  `2.1.0` from the current `2.1.0-SNAPSHOT` Maven version.
- Actual packaged local `2.1.0 -> 2.1.1` upgrades, including a repeat with the
  latest transaction/process validation. The independent copied helper/runtime
  survived desktop shutdown, backed up data, moved the complete image,
  launched the new EXE, received its acknowledgement and journaled COMPLETED.
- Actual packaged checksum-failure tests, including a repeat through the
  PowerShell wrapper: bad checksum rejected, original EXE remained alive,
  installed version remained `2.1.0`, no image replacement occurred.
- Installation, release and profile paths contained spaces. Preserved sentinel,
  transaction-linked data backup and old image retention were verified.
- New version `/actuator/health` returned UP; `/` returned HTTP 200.
- Child-process integration tests verified healthy acknowledgement, failed
  startup recovery, refusal of hung/stale PIDs, interrupted directory-swap
  recovery, data preservation and the post-migration data-review barrier.
- Python tooling tests verified both-platform manifest merging, final artifact
  hashes/sizes and metadata validation, version rejection and unsafe sandbox
  refusal. These small fixtures are not claimed as Linux app-image tests.
  Both tooling tests passed. Both changed workflow YAML files parsed successfully.

The tests used **only disposable profiles** under Windows Temp, with explicit
`app.updates.test-home` markers. Normal FM application data was not an update
target. Test apps were closed through their desktop window lifecycle after
inspection. The installed app used its packaged runtimes; JAVA_HOME was not
needed by the helper command. A host JDK was present for building, so an
otherwise Java-free Windows machine was not independently tested.

## Reproduction and evidence

The first two stable images were built with:

```powershell
python scripts/updater/updater_tools.py build `
  --output-directory C:/Users/jelme/AppData/Local/Temp/fmai-updater-build-20261109
```

Python resolved to the existing uv interpreter at
`C:/Users/jelme/AppData/Roaming/uv/python/cpython-3.15-windows-x86_64-none/python.exe`;
the system `python` alias was not a working interpreter. For the PowerShell
wrapper runs its directory was prepended to that command process's PATH.

Latest successful updater-transaction run:

```powershell
.\scripts\updater\test-local-update.ps1 `
  --old-image 'C:/Users/jelme/Documents/fm-ai-assistent/target/dist/FM AI Assistent' `
  --new-image 'C:/Users/jelme/AppData/Local/Temp/fmai-updater-final-20261109/new/target/dist/FM AI Assistent' `
  --sandbox 'C:/Users/jelme/AppData/Local/Temp/fmai updater current success 20261109' `
  --automated --wait --timeout 180
```

Output: `PASS: packaged 2.1.0 -> 2.1.1, health acknowledgement, backup and data
preservation.` The matching checksum test used that transaction's retained
`2.1.0` backup as baseline and a new `fmai updater current checksum 20261109`
sandbox, with `--failure checksum`. Output: `PASS: corrupt checksum rejected;
old packaged application remains running.`

Build logs and command transcripts are retained locally in
`target/updater-verification/`. Transaction and application logs remain inside
the disposable Temp sandboxes. Full stable build logs are in
`%LOCALAPPDATA%/Temp/fmai-updater-build-20261109/` and
`%LOCALAPPDATA%/Temp/fmai-updater-final-20261109/`.

## Not executed / owner release checks

- **Linux packaged success and failure: NOT EXECUTED.** Linux-specific TAR
  executable-mode and jlink-link tests are deliberately skipped on Windows.
  Use the copy/paste Bash commands in [the updater guide](desktop-auto-updater.md).
- Linux unit/full verification and desktop packaging: not executed here.
- Interactive Vaadin Download & install, Later, notification and manual check:
  not driven by a human/browser automation. Packaged tests used explicit
  isolated CLI consent through the same coordinator installation action.
- Windows antivirus/sharing-lock delay injection, non-writable installed-image
  E2E and physical power-loss tests: not executed. Journal recovery has automated
  filesystem/process coverage, but these are additional release checks.
- Real schema-changing packaged downgrade/data restore and production GitHub
  release download/install: not executed. Recovery conservatively blocks old
  startup after possible migration and retains both data and binary backups.
- Internet was not globally disabled during Windows tests. The local update
  source itself has no network client/fallback; other normal application
  integrations were not audited as an offline network test.
- The final UI retry/close fix is covered by automated component testing;
  Windows packaged installation tests exercise the updater transaction path.
- GitHub Actions changes were inspected locally; no workflow was dispatched.

Release remains gated until the owner reviews these results and completes the
Linux and remaining manual release checks. Neither cross-platform unit tests
nor Windows packaging are evidence that a Linux desktop was upgraded.
