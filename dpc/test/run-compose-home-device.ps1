param([string]$Serial = 'emulator-5560')
$ErrorActionPreference = 'Stop'
if ($Serial -notlike 'emulator-*') { throw 'Use a disposable emulator for the Compose migration tests.' }
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskAdb = Join-Path $env:USERPROFILE '.afs-android/sdk/platform-tools/adb.exe'
Push-Location $taskRepo
$taskOldGradleHome = $env:GRADLE_USER_HOME
try {
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.afs-android/gradle-home'
    & ./gradlew.bat :core:model:test :app:exportComposeArtifacts --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Compose build or unit tests failed.' }
    foreach ($apk in @('app-debug.apk', 'app-debug-androidTest.apk')) {
        & $taskAdb -s $Serial install --no-incremental -r -t (Join-Path $taskRepo "build/compose-phase2/$apk")
        if ($LASTEXITCODE -ne 0) { throw "Could not install $apk" }
    }
    $taskResult = & $taskAdb -s $Serial shell am instrument -w -r -e class com.afs.guard.SharedHomeStyleTest com.afs.guard.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $taskResult | Tee-Object -FilePath (Join-Path $taskRepo 'build/compose-phase2/device-test.txt')
    if ($LASTEXITCODE -ne 0 -or ($taskResult -join "`n") -notmatch 'OK \(1 test\)' -or ($taskResult -join "`n") -match 'FAILURES|Process crashed|INSTRUMENTATION_FAILED') { throw 'Compose device test failed.' }
} finally { $env:GRADLE_USER_HOME = $taskOldGradleHome; Pop-Location }
