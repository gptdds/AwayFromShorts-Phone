param([Parameter(Mandatory=$true)][string]$Serial,[ValidateSet('Signing','ScreenStats')][string]$Suite='Signing')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$bt=Join-Path $sdk 'build-tools/34.0.0'
$android=Join-Path $sdk 'platforms/android-34/android.jar'
$hostClasses=Join-Path $repo 'dpc/build/classes.jar'
$out=Join-Path (Split-Path $repo) ('archive/runs/'+(Get-Date -Format 'yyyy-MM-dd')+'-afs-plugin-signing')
New-Item -ItemType Directory -Force "$out/classes","$out/dex" | Out-Null
function Checked([string]$File,[string[]]$Arguments){
    & $File @Arguments
    if($LASTEXITCODE -ne 0){throw "Failed: $File ($LASTEXITCODE)"}
}
Checked "$bt/aapt2.exe" @('link','-o',"$out/base.apk",'-I',$android,'--manifest',"$PSScriptRoot/device/PluginSigningManifest.xml",'--min-sdk-version','28','--target-sdk-version','34')
Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$android;$hostClasses",'-d',"$out/classes","$PSScriptRoot/device/PluginSigningDeviceTest.java","$PSScriptRoot/device/ScreenStatsDeviceTest.java")
Checked (Get-Command jar).Source @('cf',"$out/classes.jar",'-C',"$out/classes",'.')
Checked "$bt/d8.bat" @('--lib',$android,'--classpath',$hostClasses,'--min-api','28','--output',"$out/dex","$out/classes.jar")
@'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2],'classes.dex')
'@ | Set-Content -LiteralPath "$out/add-dex.py" -Encoding ascii
Checked (Get-Command python).Source @("$out/add-dex.py","$out/base.apk","$out/dex/classes.dex")
Checked "$bt/zipalign.exe" @('-f','-p','4',"$out/base.apk","$out/aligned.apk")
$cfg=Get-Content -LiteralPath "$env:LOCALAPPDATA/AwayFromShorts/Signing/phone-release-v1.json" -Raw | ConvertFrom-Json
$savedPass=$env:AFS_PLUGIN_SIGNING_QA_PASS
try{
    $env:AFS_PLUGIN_SIGNING_QA_PASS=$cfg.password
    Checked "$bt/apksigner.bat" @('sign','--ks',$cfg.keystore,'--ks-key-alias',$cfg.alias,'--ks-pass','env:AFS_PLUGIN_SIGNING_QA_PASS','--key-pass','env:AFS_PLUGIN_SIGNING_QA_PASS','--out',"$out/qa.apk","$out/aligned.apk")
}finally{$env:AFS_PLUGIN_SIGNING_QA_PASS=$savedPass}
Checked "$sdk/platform-tools/adb.exe" @('-s',$Serial,'install','-r',"$out/qa.apk")
$target=if($Suite -eq 'Signing'){'com.afs.host.plugins.PluginSigningDeviceTest'}else{'com.afs.guard.ScreenStatsDeviceTest'}
$result=& "$sdk/platform-tools/adb.exe" -s $Serial shell am instrument -w "com.afs.guard.pluginsigningqa/$target"
$result | Tee-Object -FilePath "$out/result.log"
$pattern=if($Suite -eq 'Signing'){'Plugin signing: 9 checks passed'}else{'Screen stats: \d+ checks passed'}
if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch $pattern){throw 'Device checks did not pass'}
