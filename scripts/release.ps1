<#
Triggers a release build. Version bump, git commit/tag/push, and the GitHub Release publish
(with the built APK attached) all now happen automatically as part of the `assembleRelease`
Gradle task itself (see the `publishReleaseToGit` task + `finalizedBy` wiring in app/build.gradle)
- this script is just a convenience entrypoint for the command line, since the project has no
gradlew wrapper. Requires keystore.properties (release signing) and an authenticated `gh` CLI to
already be set up locally (see .claude/skills for context). Building the release variant from
Android Studio directly (with the "release" build variant selected) triggers the exact same
Gradle hook, so this script and the IDE stay in sync automatically.
#>

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

$gradle = "C:\Users\aliya\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    $found = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.6-bin" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $gradle = Join-Path $found.FullName "gradle-8.6\bin\gradle.bat" }
}
if (-not (Test-Path $gradle)) { throw "Could not locate gradle.bat - update scripts/release.ps1" }

if (-not (Test-Path (Join-Path $repoRoot "keystore.properties"))) {
    throw "keystore.properties not found - release build would be unsigned. Aborting."
}

& $gradle -p $repoRoot assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build (or the publishReleaseToGit hook it triggers) failed" }
