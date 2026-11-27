param([string]$Serial = 'emulator-5560')
$ErrorActionPreference = 'Stop'
if ($Serial -notlike 'emulator-*') { throw 'Use a disposable emulator.' }
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskAdb = Join-Path $env:USERPROFILE '.afs-android/sdk/platform-tools/adb.exe'
function Run-Test([string]$Method) {
    $result = & $taskAdb -s $Serial shell am instrument -w -r -e class "com.afs.guard.StylePersistenceTest#$Method" com.afs.guard.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Persistence $Method failed" }
}
Run-Test 'writeBeforeRestart'
& $taskAdb -s $Serial shell am force-stop com.afs.guard
Run-Test 'readAfterRestartAndRestore'
