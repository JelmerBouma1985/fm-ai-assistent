# Desktop auto-updater

Windows x64 and Linux x64 **jpackage app-images** check asynchronously for stable
compatible FM27 releases. The UI shows current/new versions, plain-text notes,
Download & install, Later and Check for updates. Installation requires explicit
consent. Later persists dismissal for that version; manual checking ignores it.
Existing users must manually install the first updater-enabled build once.
JARs, native executables, ARM, unrecognized and protected/read-only installations
use the GitHub Releases manual link. There is no privilege escalation.

Defaults: `app.updates.enabled=true`, `app.updates.source=github`,
`app.updates.developer-mode=false`, `app.updates.check-on-startup=true`,
`app.updates.local-directory=`. Disable everything with
`--app.updates.enabled=false`; disable only startup checking with
`--app.updates.check-on-startup=false`. Manual checking remains available.

## Identity, sources and integrity

`DesktopImageFinalizer` generates `app-update.properties` from Maven's parsed
numeric desktop version, with OS, x64, desktop and FM27 identity. The extracted
descriptor must match jpackage's embedded `jpackage.app-version`; the running
launcher checks its actual executable location. An IDE SNAPSHOT is not an
installed version. Compatibility uses `supportedGames`, never a version-major
heuristic. Only strictly newer stable SemVer releases qualify.

GitHub's repository is fixed to `JelmerBouma1985/fm-ai-assistent`. Up to five
pages of 100 releases are scanned for the highest compatible version, excluding
drafts/prereleases. ETags cache list responses. Exact named assets must come
from that repository release. Timeouts, bounded reads, SHA-256, byte counts and
GitHub asset digests (when present) protect integrity. JSON schema and filenames
are strict. Archives reject traversal, links, special files, duplicate entries,
alternate streams and excessive counts/expanded sizes. Linux TAR permissions
are preserved. ZIP uses the central-directory reader recommended by
[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/zip.html).
Packaging materializes internal regular-file links produced by
[jlink's legal-notice deduplication](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/jdk.jlink/share/classes/jdk/tools/jlink/internal/plugins/LegalNoticeFilePlugin.java),
so Linux archives can retain license contents while extraction rejects links.

Checksums detect corruption; they do not authenticate a publisher whose GitHub
account is compromised. Ed25519 release signing is recommended as subsequent
hardening. These releases are not cryptographically signed by the updater.

Local mode reads only `update-manifest.json` and the selected platform asset
under a canonical filesystem directory. It has **no network fallback** and uses
the same verification, extraction, helper, backup, swap and restart path. Missing
other-platform files do not prevent selecting the current platform. It requires
explicit developer opt-in, shows LOCAL UPDATE SOURCE / DEVELOPMENT MODE and is
not persisted as production configuration. Only safe local/profile arguments
are forwarded on restart; arbitrary arguments/secrets are not journaled.

Windows PowerShell launcher arguments:

```powershell
& 'C:\fmai-updater-test\installed\FM AI Assistent\FM AI Assistent.exe' `
  '--app.updates.source=local' '--app.updates.developer-mode=true' `
  '--app.updates.local-directory=C:\fmai-updater-test\releases'
```

Linux Bash (owner must validate on Linux):

```bash
"$HOME/fmai-updater-test/installed/FM AI Assistent/bin/FM AI Assistent" \
  --app.updates.source=local --app.updates.developer-mode=true \
  "--app.updates.local-directory=$HOME/fmai-updater-test/releases"
```

For destructive tests use the scripts below. They additionally set
`--app.updates.test-home=<sandbox>/profile`, which changes Java `user.home`
before Spring starts. This requires explicit local/developer flags and a
`.fmai-updater-test-profile` marker. H2, settings, logs and default exports then
stay in the disposable profile. Do not add custom datasource/export overrides.
The updater refuses nonstandard datasource URLs whose backup it cannot ensure.

## Build two real versions and test offline

Prerequisites: JDK 25 with jpackage/jlink, Maven/frontend dependencies, Python
3.10+, several GB of disk space and an interactive desktop. Python is build/test
tooling only; installed users need neither Python nor an external JDK. Building
may download dependencies; testing two already-built images works offline.

The builder copies the current checkout twice, excluding Git, outputs and
dependencies, then changes versions only in those disposable copies. It never
pushes, tags or publishes. Use a new directory outside the checkout.

Windows:

For a double-clickable interactive test, run `windows_update_test.bat` in the
repository root. It checks JDK/Python, builds both versions in a fresh Temp
directory, then opens the isolated old app. Click **Download & install** once
and wait for PASS in the console. Each run uses new directories and retains
logs; no previous test installation is overwritten. The launcher also supports
`windows_update_test.bat -CheckPrerequisites` to check tools without building.

Alternatively, run the individual commands:

```powershell
# python must be a real interpreter, not the Microsoft Store alias.
# With uv: $env:Path = "$(Split-Path (uv python find));$env:Path"
.\scripts\updater\build-test-images.ps1 --output-directory C:\fmai-updater-builds
.\scripts\updater\test-local-update.ps1 `
  --old-image 'C:\fmai-updater-builds\old\target\dist\FM AI Assistent' `
  --new-image 'C:\fmai-updater-builds\new\target\dist\FM AI Assistent' `
  --sandbox 'C:\fmai updater success' --automated --wait
# Close the new desktop app, then use a fresh sandbox:
.\scripts\updater\test-local-update.ps1 `
  --old-image 'C:\fmai-updater-builds\old\target\dist\FM AI Assistent' `
  --new-image 'C:\fmai-updater-builds\new\target\dist\FM AI Assistent' `
  --sandbox 'C:\fmai updater checksum' --failure checksum --automated --wait
```

Linux commands supplied for owner execution, **not claimed as tested here**:

Run `./linux_update_test.sh` from a terminal for the matching interactive
launcher. It checks Linux x64, JDK 25 and Python, builds both versions in a new
temporary directory, and opens the isolated old app. Click **Download & install**
once, wait for PASS, then close the app. Logs and test files are retained.
`./linux_update_test.sh --check-prerequisites` checks tools without building.
If your copy lost executable permissions, use `bash linux_update_test.sh`.

Alternatively, run the individual commands:

```bash
bash scripts/updater/build-test-images.sh --output-directory "$HOME/fmai-updater-builds"
bash scripts/updater/test-local-update.sh \
  --old-image "$HOME/fmai-updater-builds/old/target/dist/FM AI Assistent" \
  --new-image "$HOME/fmai-updater-builds/new/target/dist/FM AI Assistent" \
  --sandbox "$HOME/fmai updater success" --automated --wait
# Close the new desktop app, then:
bash scripts/updater/test-local-update.sh \
  --old-image "$HOME/fmai-updater-builds/old/target/dist/FM AI Assistent" \
  --new-image "$HOME/fmai-updater-builds/new/target/dist/FM AI Assistent" \
  --sandbox "$HOME/fmai updater checksum" --failure checksum --automated --wait
```

`--automated` supplies explicit installation consent only in the isolated
developer profile; it drives the same coordinator as the UI button and is not
forwarded on restart. Omit it to check the notification and click the button.
Sandboxes must be new, nonexistent directories, outside checkout/root/home and
without symlinks. Apps are left running for inspection; close each test app
before starting the next one. The script verifies version, acknowledgement,
retained old binaries and a backed-up/preserved data sentinel.

Inspect `original-app.log` and
`profile/.fm-ai-assistent/updater/<UUID>/{restarted-app.log,updater.log,transaction.properties,healthy.properties}`.
Visit the URL in the log: `/` must return 200 and `/actuator/health` must be UP.
Relaunch the updated image with the same local/profile arguments to verify
NO_UPDATE. Block internet/GitHub to independently verify offline operation.
Interactive notification/action checks remain separate from automated consent.

Create a release directory independently:

```bash
bash scripts/updater/create-local-release.sh --version 2.1.1 --game FM27 \
  --platform linux-x64 --app-image '/path/FM AI Assistent' \
  --output-directory /path/releases
```

PowerShell takes the same arguments through `create-local-release.ps1` with
`--platform windows-x64`. Scripts validate the packaged version, archive one
complete image root, compute real hashes/sizes and preserve the other platform
manifest entry. Filenames match publishing. Final publishing aggregation waits
for both platform jobs, validates both compressed artifacts' embedded metadata
and generates one manifest. No private token is needed for local testing.

## Transaction and recovery

State and logs live in `~/.fm-ai-assistent/updater/<UUID>/`. Extracted and backup
images are siblings of the installation for same-filesystem renames. The app
copies its JDK-only helper JAR and dedicated jlink runtime outside both images,
launches the helper and verifies its handshake before desktop shutdown. The
helper waits for the exact JVM/launcher PIDs plus start times and requires a
clean-shutdown marker. It never kills stuck/unrelated processes. A per-install
lock prevents concurrent swaps; bounded retries handle sharing violations.

Only after shutdown closes H2 does the helper copy normal user data (excluding
updater state) into `<transaction>/data-backup`. Linked/special data files abort
backup. This consistent pre-update backup is separate from binary rollback.
Normal data directories are never installation replacement/deletion targets.
Successful updates retain migrated data. Images, data backups and failed
staging are deliberately retained until the owner has verified recovery.

Journal: PREPARED, WAITING_FOR_SHUTDOWN, BACKING_UP_DATA, DATA_BACKED_UP,
BACKUP_PENDING, OLD_BACKED_UP, NEW_INSTALLED, RESTARTING, AWAITING_HEALTH,
COMPLETED. COMPLETED requires a matching packaged-version acknowledgement after
Spring readiness and desktop lifecycle initialization; starting a process alone
is not success.

If power loss interrupts renames, close all old/new app processes and use the
already-staged external helper. An installed launcher is not required:

```powershell
$profile = 'C:\fmai updater success\profile'
$tx = "$profile\.fm-ai-assistent\updater\<UUID>"
& "$tx\helper\runtime\bin\java.exe" -cp "$tx\helper\helper.jar" `
  com.github.fmaiassistent.update.helper.UpdaterMain recover $tx $profile
```

```bash
profile="$HOME/fmai updater success/profile"
tx="$profile/.fm-ai-assistent/updater/<UUID>"
"$tx/helper/runtime/bin/java" -cp "$tx/helper/helper.jar" \
  com.github.fmaiassistent.update.helper.UpdaterMain recover "$tx" "$profile"
```

Before a new process launched, recovery restores old binaries. After launch may
have run Liquibase, recovery restores binaries **without relaunching old code**,
sets DATA_REVIEW_REQUIRED and writes `.updater-data-review` in the restored
image. Its desktop launcher refuses to start until reviewed. Binary rollback
does not imply database rollback.

For data recovery: close every app process; separately copy the current data;
review post-update work that would be lost; with the owner's explicit decision,
restore `data-backup` into normal data while retaining `updater/`. Only after
confirming schema compatibility remove `.updater-data-review` and relaunch.
Keep both data copies and transaction logs. A failed new app still alive must
be closed before recovery. No potentially migrated data is silently replaced.

## Release gate

Run `./mvnw -Dvaadin.skip=true test`, `./mvnw clean verify` and
`./mvnw -DskipTests verify -Pdesktop`. CI runs manifest, extraction, real child
process, recovery and architecture tests on Windows and Linux. Before release,
require actual packaged success and failure on both OSes, manual UI checking,
Linux modes, Windows locks/read-only directories, interrupted swap recovery and
no-update after restart. Record evidence in `updater-test-report.md`. The owner
reviews both flags in `updater-release-gate.json`; publishing is blocked until
then. No publishing workflow was dispatched during implementation.
