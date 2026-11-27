param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateSet('before','after')][string]$Stage,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$Sdk,
    [Parameter(Mandatory=$true)][string]$Python,
    [string]$SigningConfig
)
$ErrorActionPreference='Stop'
if($Serial -like 'emulator-*'){throw 'This audit expects an explicitly selected physical phone'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput=[IO.Path]::GetFullPath($Output)
$taskAdb=Join-Path $Sdk 'platform-tools/adb.exe'
$taskBt=Join-Path $Sdk 'build-tools/34.0.0'
$taskAndroid=Join-Path $Sdk 'platforms/android-34/android.jar'
if(!$SigningConfig){$SigningConfig=Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json'}
$taskConfig=Get-Content -Raw -Encoding UTF8 -LiteralPath $SigningConfig | ConvertFrom-Json
New-Item -ItemType Directory -Force "$taskOutput/classes","$taskOutput/dex" | Out-Null
function Invoke-Checked {param([string]$File,[string[]]$Arguments) & $File @Arguments;if($LASTEXITCODE -ne 0){throw "$File failed: $LASTEXITCODE"}}
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.afs.guard.phase1phoneqa" android:versionCode="1"><application android:label="AFS Phone Audit" /><instrumentation android:name="com.afs.guard.Phase1PhoneAudit" android:targetPackage="com.afs.guard" android:functionalTest="true" /></manifest>
'@ | Set-Content -Encoding utf8 (Join-Path $taskOutput 'AndroidManifest.xml')
Invoke-Checked "$taskBt/aapt2.exe" @('link','-o',"$taskOutput/base.apk",'-I',$taskAndroid,'--manifest',"$taskOutput/AndroidManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Invoke-Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',$taskAndroid,'-d',"$taskOutput/classes","$PSScriptRoot/device/Phase1PhoneAudit.java")
Invoke-Checked (Get-Command jar).Source @('cf',"$taskOutput/classes.jar",'-C',"$taskOutput/classes",'.')
Invoke-Checked "$taskBt/d8.bat" @('--lib',$taskAndroid,'--min-api','24','--output',"$taskOutput/dex","$taskOutput/classes.jar")
Invoke-Checked $Python @('-c','import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a",zipfile.ZIP_DEFLATED); z.write(sys.argv[2],"classes.dex"); z.close()',"$taskOutput/base.apk","$taskOutput/dex/classes.dex")
Invoke-Checked "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOutput/base.apk","$taskOutput/aligned.apk")
$taskOldPass=[Environment]::GetEnvironmentVariable('AFS_PHONE_QA_PASS','Process')
try {
    $env:AFS_PHONE_QA_PASS=$taskConfig.password
    Invoke-Checked "$taskBt/apksigner.bat" @('sign','--ks',$taskConfig.keystore,'--ks-key-alias',$taskConfig.alias,'--ks-pass','env:AFS_PHONE_QA_PASS','--key-pass','env:AFS_PHONE_QA_PASS','--out',"$taskOutput/qa.apk","$taskOutput/aligned.apk")
} finally {[Environment]::SetEnvironmentVariable('AFS_PHONE_QA_PASS',$taskOldPass,'Process')}
Invoke-Checked $taskAdb @('-s',$Serial,'install','-r',"$taskOutput/qa.apk")
try {
    $taskResult=& $taskAdb -s $Serial shell am instrument -w -e stage $Stage 'com.afs.guard.phase1phoneqa/com.afs.guard.Phase1PhoneAudit' 2>&1
    $taskExit=$LASTEXITCODE
    $taskResult | Set-Content -Encoding utf8 (Join-Path $taskOutput 'instrumentation.log')
    $taskResult | Write-Output
    $taskText=$taskResult -join "`n"
    if($taskExit -ne 0 -or $taskText -notmatch 'PHASE1 PHONE .*: \d+ checks passed' -or $taskText -match 'AssertionError|INSTRUMENTATION_FAILED|Process crashed|Exception:'){throw 'Phone audit failed'}
    $taskExport=Join-Path $taskOutput 'export'
    [IO.Directory]::CreateDirectory($taskExport) | Out-Null
    $taskFiles=@('local-backup.zip','audit.json')
    if($Stage -eq 'after'){$taskFiles+=@('rules-backup.json','diagnostics.txt')}
    foreach($taskFile in $taskFiles){
        Invoke-Checked $taskAdb @('-s',$Serial,'pull',"/sdcard/Android/data/com.afs.guard/files/phase1-$Stage/$taskFile",(Join-Path $taskExport $taskFile))
    }
} finally {& $taskAdb -s $Serial uninstall com.afs.guard.phase1phoneqa | Out-Null}
