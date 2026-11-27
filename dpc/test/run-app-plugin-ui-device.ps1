param(
    [string]$Serial,
    [string]$Adb = 'D:\afs-android\sdk\platform-tools\adb.exe',
    [string]$Sdk = 'C:\Users\nj\.afs-android\sdk',
    [string]$SigningConfig
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildTools = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$classesJar = Join-Path $root 'build\classes.jar'
$out = Join-Path $root 'build\app-plugin-ui-qa'
$expectedOut = [IO.Path]::GetFullPath((Join-Path $root 'build\app-plugin-ui-qa'))
if ([IO.Path]::GetFullPath($out) -ne $expectedOut -or
    !$expectedOut.StartsWith([IO.Path]::GetFullPath($root) + [IO.Path]::DirectorySeparatorChar)) {
    throw 'Refusing to clean an unexpected QA output path'
}
if (!$SigningConfig) {
    $SigningConfig = Join-Path $env:LOCALAPPDATA `
        'AwayFromShorts\Signing\phone-release-v1.json'
}
if (!(Test-Path -LiteralPath $SigningConfig)) {
    throw "Release signing config not found: $SigningConfig"
}
$signing = Get-Content -Raw -Encoding UTF8 -LiteralPath $SigningConfig |
    ConvertFrom-Json

if (!$Serial) {
    $lines = & $Adb devices
    $Serial = ($lines | Where-Object { $_ -match '^\S+\s+device$' } |
        Select-Object -First 1) -split '\s+' | Select-Object -First 1
}
if (!$Serial) { throw 'No connected Android device' }

if (Test-Path -LiteralPath $out) {
    Remove-Item -LiteralPath $out -Recurse -Force
}
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& (Join-Path $buildTools 'aapt2.exe') link `
    -o "$out\base.apk" -I $androidJar `
    --manifest "$PSScriptRoot/device/AppPluginUiManifest.xml" `
    --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

& (Get-Command javac).Source --release 8 -encoding UTF-8 `
    -cp "$androidJar;$classesJar" -d "$out\classes" `
    "$PSScriptRoot/device/AppPluginUiDeviceTest.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

& (Get-Command jar).Source cf "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& (Join-Path $buildTools 'd8.bat') --lib $androidJar `
    --classpath $classesJar --min-api 24 --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

@'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(sys.argv[2], 'classes.dex')
'@ | Set-Content -LiteralPath "$out\add-dex.py" -Encoding ascii
& python "$out\add-dex.py" "$out\base.apk" "$out\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw 'adding classes.dex failed' }

& (Join-Path $buildTools 'zipalign.exe') -f -p 4 `
    "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$oldPass = [Environment]::GetEnvironmentVariable('AFS_APP_PLUGIN_QA_PASS')
try {
    $env:AFS_APP_PLUGIN_QA_PASS = [string]$signing.password
    & (Join-Path $buildTools 'apksigner.bat') sign `
        --ks ([string]$signing.keystore) `
        --ks-key-alias ([string]$signing.alias) `
        --ks-pass env:AFS_APP_PLUGIN_QA_PASS `
        --key-pass env:AFS_APP_PLUGIN_QA_PASS `
        --out "$out\qa.apk" "$out\aligned.apk"
} finally {
    [Environment]::SetEnvironmentVariable(
        'AFS_APP_PLUGIN_QA_PASS', $oldPass, 'Process')
}
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& $Adb -s $Serial uninstall com.afs.guard.pluginqa | Out-Null
& $Adb -s $Serial install -r "$out\qa.apk"
if ($LASTEXITCODE -ne 0) { throw 'QA APK install failed' }

$instrumentOutput = & $Adb -s $Serial shell am instrument -w `
    'com.afs.guard.pluginqa/com.afs.guard.pluginqa.AppPluginUiDeviceTest' 2>&1
$instrumentExit = $LASTEXITCODE
$instrumentOutput | Write-Output
$instrumentText = $instrumentOutput -join "`n"
if ($instrumentExit -ne 0 -or
    $instrumentText -notmatch 'App plugin UI: \d+ checks passed' -or
    $instrumentText -match 'FAILED|AssertionError|INSTRUMENTATION_FAILED') {
    throw 'App plugin UI instrumentation failed'
}
& $Adb -s $Serial uninstall com.afs.guard.pluginqa | Out-Null
