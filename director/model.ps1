param(
  [ValidateSet('start','pull','status','stop')][string]$Action = 'status',
  [ValidatePattern('^[a-zA-Z0-9_.-]+$')][string]$Distribution = 'Ubuntu',
  [ValidateSet('auto','windows','wsl')][string]$Backend = 'auto'
)
$ErrorActionPreference = 'Stop'
$composePath = Join-Path $PSScriptRoot 'docker/compose.cpu.yaml'
$pin = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'models.lock.json') -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'docker-backend.ps1')
$dockerCommand = Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$wslCommand = Get-Command wsl.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$runtimePath = Join-Path $PSScriptRoot 'runtime'
$keeperPath = Join-Path $runtimePath 'wsl-keeper.json'
function Get-DirectorKeeper {
  if (-not (Test-Path -LiteralPath $keeperPath)) { return $null }
  try {
    $saved = Get-Content -LiteralPath $keeperPath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $saved.processId -ErrorAction Stop
    if ($process.ProcessName -eq 'wsl' -and $process.StartTime.ToUniversalTime().ToFileTimeUtc().ToString() -eq $saved.started -and $saved.distribution -eq $Distribution) {
      return $process
    }
  } catch { }
  return $null
}
function Start-DirectorKeeper {
  if (Get-DirectorKeeper) { return }
  [IO.Directory]::CreateDirectory($runtimePath) | Out-Null
  $process = Start-Process -FilePath $wslCommand.Source -ArgumentList @('-d',$Distribution,'-u','root','--','sleep','infinity') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath 'keeper.out.log') -RedirectStandardError (Join-Path $runtimePath 'keeper.err.log')
  Start-Sleep -Milliseconds 500
  if ($process.HasExited) { throw 'Unable to keep the WSL model service available.' }
  $saved = @{processId=$process.Id;started=$process.StartTime.ToUniversalTime().ToFileTimeUtc().ToString();distribution=$Distribution}
  [IO.File]::WriteAllText($keeperPath,($saved | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
}
function Stop-DirectorKeeper {
  $keeper = Get-DirectorKeeper
  if ($keeper) { Stop-Process -Id $keeper.Id -ErrorAction Stop }
}
function Start-DirectorWslEngine {
  Start-DirectorKeeper
  $probeArguments="-d $Distribution -u root -- docker info --format {{.ServerVersion}}"
  if(Test-DirectorCommand $wslCommand.Source $probeArguments 8000) { return }
  Write-Output 'Starting the existing Ubuntu Docker service.'
  # Use the installed service with its existing containerd socket/image store.
  # Do not create a parallel daemon when WSL/systemd itself is unhealthy.
  if(-not (Test-DirectorCommand $wslCommand.Source ("-d $Distribution -u root -- systemctl start docker") 45000)) {
    throw 'Ubuntu Docker service could not start. Check WSL/systemd; cached model data is preserved.'
  }
  if(-not (Test-DirectorCommand $wslCommand.Source $probeArguments 8000)) {
    throw 'Ubuntu Docker service started but its API is unavailable.'
  }
}
if($Backend -eq 'wsl' -and $Action -in @('start','pull')) {
  if(-not $wslCommand) { throw 'WSL executable was not found.' }
  Start-DirectorWslEngine
}
$windowsReady=$false; $wslReady=$false; $windowsHasModels=$false; $wslHasModels=$false
if($dockerCommand -and $Backend -ne 'wsl') {
  $windowsReady=Test-DirectorCommand $dockerCommand.Source 'info --format {{.ServerVersion}}'
  if($windowsReady) { $windowsHasModels=Test-DirectorCommand $dockerCommand.Source 'volume inspect dead-earth-director-models' }
}
if($wslCommand -and $Backend -ne 'windows') {
  $wslReady=Test-DirectorCommand $wslCommand.Source ("-d $Distribution -u root -- docker info --format {{.ServerVersion}}") 45000
  if($wslReady) { $wslHasModels=Test-DirectorCommand $wslCommand.Source ("-d $Distribution -u root -- docker volume inspect dead-earth-director-models") }
}
# A cold WSL daemon can hold the existing pinned cache even when its API is not running.
if ($Backend -eq 'auto' -and $Action -in @('start','pull') -and -not $windowsReady -and -not $wslReady -and $wslCommand) {
  try { Start-DirectorWslEngine; $wslReady=$true; $wslHasModels=Test-DirectorCommand $wslCommand.Source ("-d $Distribution -u root -- docker volume inspect dead-earth-director-models") }
  catch { Write-Output 'The existing Ubuntu Docker service is unavailable; no Docker settings or model cache were changed.' }
}
$selectedBackend=Select-DirectorBackend $Backend $windowsReady $wslReady $windowsHasModels $wslHasModels
Write-Output ('Director Docker backend: '+$selectedBackend)
if ($selectedBackend -eq 'wsl') {
  if ($Action -in @('start','pull')) { Start-DirectorKeeper }
  $linuxCompose = & $wslCommand.Source -d $Distribution -u root -- wslpath -u ($composePath.Replace('\','/'))
  if ($LASTEXITCODE -ne 0) { throw 'Ubuntu WSL compose path is unavailable.' }
}
function Invoke-DirectorDocker([string[]]$DockerArguments) {
  $previousPreference=$ErrorActionPreference
  try {
    $ErrorActionPreference='Continue'
    if ($selectedBackend -eq 'windows') { & $dockerCommand.Source compose -f $composePath @DockerArguments }
    else { & $wslCommand.Source -d $Distribution -u root -- docker compose -f $linuxCompose @DockerArguments }
    $operationExit=$LASTEXITCODE
  } finally { $ErrorActionPreference=$previousPreference }
  if ($operationExit -ne 0) { throw ('Director Docker operation failed on backend '+$selectedBackend+'. See the preceding Docker error.') }
}
function Assert-DirectorPin {
  $tags=Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 10
  $matching=@($tags.models | Where-Object { $_.name -eq $pin.model -and $_.digest -eq $pin.manifest_sha256 })
  if($matching.Count -ne 1) { throw ('Pinned CPU model is missing or differs from models.lock.json on backend '+$selectedBackend+'. Use -Action pull only to prepare the pinned model explicitly.') }
}
function Wait-DirectorApi {
  $until = [DateTime]::UtcNow.AddSeconds(30)
  do {
    try { $null = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/version' -TimeoutSec 2; return }
    catch { Start-Sleep -Milliseconds 500 }
  } while ([DateTime]::UtcNow -lt $until)
  throw 'Local model API did not become ready.'
}
switch ($Action) {
  'start' { Invoke-DirectorDocker -DockerArguments @('up','-d','--pull','never'); Wait-DirectorApi; Assert-DirectorPin; Write-Output 'Pinned CPU model is ready.' }
  'pull' {
    Invoke-DirectorDocker -DockerArguments @('up','-d','--pull','missing'); Wait-DirectorApi
    $body = @{ model = $pin.model; stream = $false } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/pull' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
    $tags = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 10
    $matching = @($tags.models | Where-Object { $_.name -eq $pin.model -and $_.digest -eq $pin.manifest_sha256 })
    if ($matching.Count -ne 1) { throw 'Model revision differs from models.lock.json. Host will refuse this revision.' }
    Write-Output 'Pinned CPU model is ready.'
  }
  'status' { Invoke-DirectorDocker -DockerArguments @('ps') }
  'stop' {
    Invoke-DirectorDocker -DockerArguments @('stop')
    if ($selectedBackend -eq 'wsl') { Stop-DirectorKeeper }
  }
}
