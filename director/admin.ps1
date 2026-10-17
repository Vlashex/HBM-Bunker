param(
    [ValidateSet('start','stop','status')][string]$Action='start',
    [string]$ServerRoot=(Join-Path $PSScriptRoot '../../Dead-Earth-Server-3.2.0'),
    [string]$Catalog,
    [ValidateRange(1,65535)][int]$Port=8765,
    [switch]$NoOpen
)
$ErrorActionPreference='Stop'
try {
    $serverPath=(Resolve-Path -LiteralPath $ServerRoot).ProviderPath
    $baseUrl="http://127.0.0.1:$Port"
    $hash=[Security.Cryptography.SHA256]::Create()
    try { $digest=$hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($serverPath.ToLowerInvariant())) }
    finally { $hash.Dispose() }
    $instance=([BitConverter]::ToString($digest).Replace('-','').ToLowerInvariant()).Substring(0,16)
    $runtime=Join-Path $serverPath 'director/runtime'
    $stopFile=Join-Path $runtime 'admin.stop'
    function Get-AdminHealth {
        try { return Invoke-RestMethod -Uri "$baseUrl/api/health" -TimeoutSec 1 }
        catch { return $null }
    }
    $health=Get-AdminHealth
    if($health -and ($health.product -ne 'dead-earth-admin-v1' -or $health.instance_id -ne $instance)) { throw 'This port belongs to another application or server. Choose another -Port.' }
    if($Action -eq 'status') {
        if($health) { Write-Host "DEAD EARTH admin is running: $baseUrl" } else { Write-Host 'DEAD EARTH admin is stopped.' }
        exit 0
    }
    if($Action -eq 'stop') {
        if($health) { New-Item -ItemType File -Path $stopFile -Force | Out-Null; Write-Host 'Admin stop requested. Minecraft and AI keep running.' }
        else { Write-Host 'DEAD EARTH admin is already stopped.' }
        exit 0
    }
    if(-not $health) {
        $python=Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
        if(-not (Test-Path -LiteralPath $python -PathType Leaf)) {
            $deployment=Get-Content -LiteralPath (Join-Path $serverPath 'deployment.json') -Raw | ConvertFrom-Json
            $python=$deployment.python
        }
        if(-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw 'Python runtime was not found. Prepare director/.venv or deployment.json.' }
        if(-not $Catalog) {
            $Catalog=Join-Path $PSScriptRoot '../pack/config/dead-earth/director/catalog.json'
            if(-not (Test-Path -LiteralPath $Catalog -PathType Leaf)) { $Catalog=Join-Path $serverPath 'config/dead-earth/director/catalog.json' }
        }
        $catalogPath=(Resolve-Path -LiteralPath $Catalog).ProviderPath
        New-Item -ItemType Directory -Path $runtime -Force | Out-Null
        if(Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile }
        $env:PYTHONUTF8='1'
        $launchArgs=@('-u','-m','dead_earth_director.admin','--server-root',('"'+$serverPath+'"'),'--catalog',('"'+$catalogPath+'"'),'--port',$Port)
        $child=Start-Process -FilePath $python -ArgumentList $launchArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'admin.stdout.log') -RedirectStandardError (Join-Path $runtime 'admin.stderr.log') -PassThru
        for($i=0;$i -lt 30;$i++) {
            Start-Sleep -Milliseconds 200
            $health=Get-AdminHealth
            if($health) { break }
            if($child.HasExited) { throw 'Admin could not start. See director/runtime/admin.stderr.log in the server folder.' }
        }
        if(-not $health -or $health.product -ne 'dead-earth-admin-v1' -or $health.instance_id -ne $instance) { throw 'Admin startup was not confirmed. See director/runtime/admin.stderr.log.' }
    }
    Write-Host "DEAD EARTH admin: $baseUrl"
    if(-not $NoOpen) { Start-Process $baseUrl }
} catch { Write-Host ('DEAD EARTH admin: '+$_.Exception.Message); exit 1 }
