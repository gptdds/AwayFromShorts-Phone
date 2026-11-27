param([string]$Apk,[string]$Output)
$ErrorActionPreference='Stop'
$config=Get-Content -Raw -Encoding UTF8 (Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json') | ConvertFrom-Json
$sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$buildTools=Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$previous=[Environment]::GetEnvironmentVariable('AFS_TEST_SIGN_PASS','Process')
try {
    $env:AFS_TEST_SIGN_PASS=$config.password
    & (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $config.keystore --ks-key-alias $config.alias --ks-pass env:AFS_TEST_SIGN_PASS --key-pass env:AFS_TEST_SIGN_PASS --out $Output $Apk
    if($LASTEXITCODE -ne 0){throw 'Instrumentation signing failed'}
    & (Join-Path $buildTools.FullName 'apksigner.bat') verify $Output
    if($LASTEXITCODE -ne 0){throw 'Instrumentation signature verification failed'}
} finally { [Environment]::SetEnvironmentVariable('AFS_TEST_SIGN_PASS',$previous,'Process') }
