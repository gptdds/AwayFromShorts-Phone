param(
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$Sdk,
    [Parameter(Mandatory=$true)][string]$Bash,
    [Parameter(Mandatory=$true)][string]$Python
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
$outputRoot = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputRoot) { throw 'Use a fresh output directory' }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$snapshot = Join-Path $outputRoot 'source'
foreach ($folder in @('dpc','blocker','plugin-api','core/style-api','core/rules','style/liquidglass','style/fluidgradient','style/spatial3d','docs')) {
    & robocopy (Join-Path $sourceRoot $folder) (Join-Path $snapshot $folder) /E /XD build .gradle .git /XF *.apk *.log /NFL /NDL /NJH /NJS /NP *> $null
    if ($LASTEXITCODE -gt 7) { throw "Snapshot failed: $folder" }
}
$env:AFS_SDK = $Sdk.Replace('\','/')
$env:AFS_PYTHON = $Python.Replace('\','/')
$env:AFS_PREFILL = '0'
$env:AFS_RELEASE = '0'
$env:AFS_RECOVERY = '0'
$env:AFS_PLUGIN_TEST_MODE = '0'
foreach ($name in @('AFS_KEYSTORE','AFS_KEY_ALIAS','AFS_STORE_PASS','AFS_KEY_PASS')) {
    Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
}
$env:AFS_APK_OUTPUT = (Join-Path $outputRoot 'afs-guard.apk').Replace('\','/')
$results = @()
foreach ($module in @('dpc','blocker')) {
    foreach ($phase in @('build','test')) {
        $script = if ($phase -eq 'build') {'build.sh'} else {'test/run.sh'}
        $log = Join-Path $outputRoot "$module-$phase.log"
        Push-Location (Join-Path $snapshot $module)
        try {
            & $Bash $script *> $log
            $exitCode = $LASTEXITCODE
        } finally { Pop-Location }
        $results += @{module=$module;phase=$phase;exitCode=$exitCode;log=$log}
        $results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $outputRoot 'results.json')
        Write-Output "$module $phase exit=$exitCode"
        if ($exitCode -ne 0) {
            Get-Content -LiteralPath $log -Tail 35
            exit $exitCode
        }
    }
}
Copy-Item -LiteralPath (Join-Path $snapshot 'afs-blocker.apk') -Destination (Join-Path $outputRoot 'afs-blocker.apk')
