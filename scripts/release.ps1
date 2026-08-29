<#
Bumps version.properties (patch += 1), builds a signed release APK, commits +
tags + pushes to origin/master, and publishes a GitHub Release with the APK
attached. Requires keystore.properties (release signing) and an authenticated
`gh` CLI to already be set up locally (see .claude/skills for context).
#>
param(
    [string]$Notes = "Automated release"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

$gradle = "C:\Users\aliya\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    $found = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.6-bin" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $gradle = Join-Path $found.FullName "gradle-8.6\bin\gradle.bat" }
}
if (-not (Test-Path $gradle)) { throw "Could not locate gradle.bat - update scripts/release.ps1" }

$gh = "C:\Program Files\GitHub CLI\gh.exe"
if (-not (Test-Path $gh)) { $gh = "gh" }

if (-not (Test-Path (Join-Path $repoRoot "keystore.properties"))) {
    throw "keystore.properties not found - release build would be unsigned. Aborting."
}

# --- Bump version.properties ---
$versionFile = Join-Path $repoRoot "version.properties"
$props = @{}
Get-Content $versionFile | ForEach-Object {
    if ($_ -match '^(\w+)=(\d+)$') { $props[$matches[1]] = [int]$matches[2] }
}
$props['versionPatch'] += 1
$versionName = "$($props['versionMajor']).$($props['versionMinor']).$($props['versionPatch'])"
$tag = "v$versionName"

@(
    "versionMajor=$($props['versionMajor'])"
    "versionMinor=$($props['versionMinor'])"
    "versionPatch=$($props['versionPatch'])"
) | Set-Content -Path $versionFile -Encoding ascii

Write-Host "==> Releasing $tag"

# --- Build signed release APK ---
& $gradle -p $repoRoot assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$apkSrc = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkSrc)) { throw "Release APK not found at $apkSrc" }

# Staged outside the repo so it can never accidentally get committed.
$apkDest = Join-Path $env:TEMP "ComtradeDownloader-$versionName.apk"
Copy-Item $apkSrc $apkDest -Force

# --- Commit, tag, push ---
Push-Location $repoRoot
try {
    git add -A
    git commit -m "Release $tag"
    git tag $tag
    git push origin master
    git push origin $tag

    & $gh release create $tag $apkDest --title $tag --notes $Notes
} finally {
    Pop-Location
    Remove-Item $apkDest -Force -ErrorAction SilentlyContinue
}

Write-Host "==> Release $tag published: https://github.com/alidroid123/IEC61850-apk/releases/tag/$tag"
