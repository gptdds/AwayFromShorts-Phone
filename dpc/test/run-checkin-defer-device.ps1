param([string]$Serial='emulator-5592',[string]$Sdk='D:\afs-android\sdk',[string]$Version='1.91.83')
$ErrorActionPreference='Stop'
if($Serial -notlike 'emulator-*'){throw 'Disposable emulator required'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOut=Join-Path $taskRoot '../../archive/dumps/afs-checkin-defer-device'
New-Item -ItemType Directory -Force "$taskOut/classes","$taskOut/dex" | Out-Null
$taskBt=Get-ChildItem "$Sdk/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$taskAndroid="$Sdk/platforms/android-34/android.jar"
$taskClasses="$taskRoot/build/classes.jar"
$taskSigning=@{keystore="$taskRoot/keystore/afs.keystore";alias='afs';password='afsguard'}
function Run([string]$File,[string[]]$Arguments){& $File @Arguments;if($LASTEXITCODE -ne 0){throw "$File failed"}}
Run "$taskBt/aapt2.exe" @('link','-o',"$taskOut/base.apk",'-I',$taskAndroid,'--manifest',"$PSScriptRoot/device/CheckinDeferManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Run (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$taskAndroid;$taskClasses",'-d',"$taskOut/classes","$PSScriptRoot/device/CheckinDeferDeviceTest.java")
Run (Get-Command jar).Source @('cf',"$taskOut/classes.jar",'-C',"$taskOut/classes",'.')
Run "$taskBt/d8.bat" @('--lib',$taskAndroid,'--classpath',$taskClasses,'--min-api','24','--output',"$taskOut/dex","$taskOut/classes.jar")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip=[IO.Compression.ZipFile]::Open("$taskOut/base.apk",'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,"$taskOut/dex/classes.dex",'classes.dex') | Out-Null
$taskZip.Dispose()
Run "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOut/base.apk","$taskOut/aligned.apk")
$env:AFS_QA_SIGN_PASS=$taskSigning.password
try{
    Run "$taskBt/apksigner.bat" @('sign','--ks',$taskSigning.keystore,'--ks-key-alias',$taskSigning.alias,'--ks-pass','env:AFS_QA_SIGN_PASS','--key-pass','env:AFS_QA_SIGN_PASS','--out',"$taskOut/qa.apk","$taskOut/aligned.apk")
    Run "$taskBt/apksigner.bat" @('sign','--ks',$taskSigning.keystore,'--ks-key-alias',$taskSigning.alias,'--ks-pass','env:AFS_QA_SIGN_PASS','--key-pass','env:AFS_QA_SIGN_PASS','--out',"$taskOut/target.apk","$taskRoot/../dist/$Version/afs-guard.apk")
}
finally{Remove-Item Env:AFS_QA_SIGN_PASS}
Run "$Sdk/platform-tools/adb.exe" @('-s',$Serial,'install','-r',"$taskOut/target.apk")
Run "$Sdk/platform-tools/adb.exe" @('-s',$Serial,'install','-r',"$taskOut/qa.apk")
try{
    $taskResult=& "$Sdk/platform-tools/adb.exe" -s $Serial shell am instrument -w 'com.afs.guard.deferqa/com.afs.guard.CheckinDeferDeviceTest'
    $taskResult | Write-Output
    if(($taskResult -join "`n") -notmatch 'CHECKIN DEFER: \d+ checks passed'){throw 'Checkin integration failed'}
}finally{& "$Sdk/platform-tools/adb.exe" -s $Serial uninstall com.afs.guard.deferqa | Out-Null}
