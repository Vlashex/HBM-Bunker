param([string]$Docker, [ValidateRange(5,180)][int]$TimeoutSeconds=120)
$ErrorActionPreference='Stop'

function Test-DesktopEngine([string]$Docker) {
    $probe=New-Object System.Diagnostics.Process
    $probe.StartInfo=New-Object System.Diagnostics.ProcessStartInfo
    $probe.StartInfo.FileName=$Docker
    $probe.StartInfo.Arguments='--context desktop-linux version --format "{{.Server.Version}}"'
    $probe.StartInfo.UseShellExecute=$false
    $probe.StartInfo.CreateNoWindow=$true
    $probe.StartInfo.RedirectStandardOutput=$true
    $probe.StartInfo.RedirectStandardError=$true
    try {
        [void]$probe.Start()
        if(-not $probe.WaitForExit(4000)) {$probe.Kill();return $false}
        return ($probe.ExitCode -eq 0 -and $probe.StandardOutput.ReadToEnd().Trim() -match '^\d+\.')
    } catch {return $false} finally {$probe.Dispose()}
}

function Invoke-DesktopStart([string]$Docker) {
    $launch=New-Object System.Diagnostics.Process
    $launch.StartInfo=New-Object System.Diagnostics.ProcessStartInfo
    $launch.StartInfo.FileName=$Docker
    $launch.StartInfo.Arguments='desktop start --detach'
    $launch.StartInfo.UseShellExecute=$false
    $launch.StartInfo.CreateNoWindow=$true
    $launch.StartInfo.RedirectStandardOutput=$true
    $launch.StartInfo.RedirectStandardError=$true
    try {
        [void]$launch.Start()
        if(-not $launch.WaitForExit(10000)) {$launch.Kill();return $false}
        return ($launch.ExitCode -eq 0)
    } catch {return $false} finally {$launch.Dispose()}
}

function Start-VerifiedDockerBackend([string]$Docker) {
    $resources=Split-Path -Parent (Split-Path -Parent $Docker)
    $backend=Join-Path $resources 'com.docker.backend.exe'
    if(-not (Test-Path -LiteralPath $backend -PathType Leaf)) {throw 'Docker Desktop backend is missing.'}
    $signature=Get-AuthenticodeSignature -LiteralPath $backend
    if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Docker Inc(?:,|$)') {
        throw 'A valid Docker Inc signature is required for the backend fallback.'
    }
    $existing=Get-CimInstance Win32_Process -Filter "Name='com.docker.backend.exe'" |
        Where-Object {$_.ExecutablePath -eq $backend}
    if($existing) {return}
    $logs=Join-Path $env:LOCALAPPDATA 'DEAD-EARTH\docker-start'
    [void](New-Item -ItemType Directory -Path $logs -Force)
    $stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ')
    Start-Process -FilePath $backend -WorkingDirectory $resources -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs ($stamp+'.out.log')) `
        -RedirectStandardError (Join-Path $logs ($stamp+'.err.log')) | Out-Null
}

function Ensure-DesktopEngine([string]$Docker, [int]$TimeoutSeconds=120) {
    if(Test-DesktopEngine $Docker) {return}
    if(-not (Invoke-DesktopStart $Docker) -or -not (Test-DesktopEngine $Docker)) {
        Write-Host 'Docker Desktop launcher unavailable; starting verified installed backend.'
        Start-VerifiedDockerBackend $Docker
    }
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if(Test-DesktopEngine $Docker) {return}
        Start-Sleep -Milliseconds 1000
    } while([DateTime]::UtcNow -lt $deadline)
    throw 'Docker Desktop engine did not become ready before timeout.'
}

if($MyInvocation.InvocationName -ne '.') {
    try {
        if(-not $Docker) {
            $perUser=Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
            if(Test-Path -LiteralPath $perUser -PathType Leaf) {$Docker=$perUser}
            else {$Docker=(Get-Command docker.exe -ErrorAction Stop).Source}
        }
        if(-not [IO.Path]::IsPathRooted($Docker)) {$Docker=(Get-Command $Docker -CommandType Application -ErrorAction Stop).Source}
        Ensure-DesktopEngine -Docker $Docker -TimeoutSeconds $TimeoutSeconds
        Write-Host 'Docker Desktop engine is ready.'
        exit 0
    } catch {Write-Error $_.Exception.Message;exit 1}
}
