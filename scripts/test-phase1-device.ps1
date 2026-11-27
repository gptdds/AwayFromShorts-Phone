param(
    [Parameter(Mandatory=$true)][string]$Artifacts,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$Sdk,
    [Parameter(Mandatory=$true)][string]$Python,
    [string]$Serial = 'emulator-5590',
    [switch]$RequireShizuku,
    [ValidateSet('dpc','blocker')][string[]]$Modules = @('dpc','blocker')
)
$ErrorActionPreference = 'Stop'
if ($Serial -notlike 'emulator-*') { throw 'Use the disposable Phase1 emulator' }
$sourceRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$avd = & $adb -s $Serial emu avd name
if (($avd -join ' ') -notmatch 'AFS_Phase1_API34') { throw 'Only AFS_Phase1_API34 may run these fixtures' }
$bt = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$android = Join-Path $Sdk 'platforms/android-34/android.jar'
$outputRoot = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$keystore = Join-Path $sourceRoot 'dpc/keystore/afs.keystore'
function Invoke-Checked { param([string]$File,[string[]]$Arguments) & $File @Arguments; if ($LASTEXITCODE -ne 0) { throw "$File failed: $LASTEXITCODE" } }
function Pack-Apk { param([string]$Folder)
    Invoke-Checked (Join-Path $bt 'zipalign.exe') @('-f','-p','4',"$Folder/base.apk","$Folder/aligned.apk")
    Invoke-Checked (Join-Path $bt 'apksigner.bat') @('sign','--ks',$keystore,'--ks-pass','pass:afsguard','--key-pass','pass:afsguard','--out',"$Folder/qa.apk","$Folder/aligned.apk")
}
$fixture = Join-Path $outputRoot 'fixture'
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.afs.phase1.fixture" android:versionCode="1"><application android:hasCode="false" android:label="Phase1 Fixture"><activity android:name="android.app.Activity" android:exported="true" /></application></manifest>
'@ | Set-Content -Encoding utf8 (Join-Path $fixture 'AndroidManifest.xml')
Invoke-Checked (Join-Path $bt 'aapt2.exe') @('link','-o',"$fixture/base.apk",'-I',$android,'--manifest',"$fixture/AndroidManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Pack-Apk $fixture
Invoke-Checked $adb @('-s',$Serial,'install','-r',"$fixture/qa.apk")
foreach ($module in $Modules) {
    $package = if ($module -eq 'dpc') {'com.afs.guard'} else {'com.afs.blocker'}
    $folder = Join-Path $outputRoot $module
    New-Item -ItemType Directory -Force "$folder/classes","$folder/dex" | Out-Null
    $classes = Join-Path $Artifacts "source/$module/build/classes.jar"
    $apk = if ($module -eq 'dpc') {Join-Path $Artifacts 'afs-guard.apk'} else {Join-Path $Artifacts 'source/afs-blocker.apk'}
    $ownerAlreadySet = $false
    if ($module -eq 'dpc') {
        $owners = & $adb -s $Serial shell dumpsys -t 5 device_policy
        if ($LASTEXITCODE -ne 0) { throw 'Unable to verify device owner before installation' }
        $ownerAlreadySet = ($owners -join ' ') -match 'Device Owner[\s\S]*com.afs.guard'
    }
    Invoke-Checked $adb @('-s',$Serial,'install','-r',$apk)
    if ($module -eq 'dpc') {
        if (!$ownerAlreadySet) {
            Invoke-Checked $adb @('-s',$Serial,'shell','dpm','set-device-owner','com.afs.guard/.AfsAdmin')
        }
        Invoke-Checked $adb @('-s',$Serial,'shell','appops','set',$package,'GET_USAGE_STATS','allow')
    }
    @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package.phase1qa" android:versionCode="1"><application android:label="Phase1 QA" /><instrumentation android:name="$package.Phase1DeviceTest" android:targetPackage="$package" android:functionalTest="true" /></manifest>
"@ | Set-Content -Encoding utf8 (Join-Path $folder 'AndroidManifest.xml')
    Invoke-Checked (Join-Path $bt 'aapt2.exe') @('link','-o',"$folder/base.apk",'-I',$android,'--manifest',"$folder/AndroidManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
    Invoke-Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$android;$classes",'-d',"$folder/classes",(Join-Path $sourceRoot "$module/test/device/Phase1DeviceTest.java"),(Join-Path $sourceRoot 'core/rules/src/deviceTest/java/com/afs/test/FailingContext.java'))
    Invoke-Checked (Get-Command jar).Source @('cf',"$folder/classes.jar",'-C',"$folder/classes",'.')
    Invoke-Checked (Join-Path $bt 'd8.bat') @('--lib',$android,'--classpath',$classes,'--min-api','24','--output',"$folder/dex","$folder/classes.jar")
    Invoke-Checked $Python @('-c','import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a",zipfile.ZIP_DEFLATED); z.write(sys.argv[2],"classes.dex"); z.close()',"$folder/base.apk","$folder/dex/classes.dex")
    Pack-Apk $folder
    Invoke-Checked $adb @('-s',$Serial,'install','-r',"$folder/qa.apk")
    try {
        $extra = if ($RequireShizuku -and $module -eq 'blocker') {@('-e','require_shizuku','true')} else {@()}
        $result = & $adb -s $Serial shell am instrument -w @extra "$package.phase1qa/$package.Phase1DeviceTest" 2>&1
        $result | Set-Content -Encoding utf8 (Join-Path $outputRoot "$module-instrumentation.log")
        $result | Write-Output
        $text = $result -join "`n"
        if ($LASTEXITCODE -ne 0 -or $text -notmatch 'PHASE1 .* DEVICE: \d+ checks passed' -or $text -match 'INSTRUMENTATION_FAILED|AssertionError|Exception:|Process crashed') {
            throw "$module device checks failed"
        }
    } finally { & $adb -s $Serial uninstall "$package.phase1qa" | Out-Null }
}
