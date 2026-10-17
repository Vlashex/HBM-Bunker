param(
  [ValidateSet('start','pull','status','stop')][string]$Action = 'status',
  [ValidatePattern('^[a-zA-Z0-9_.-]+$')][string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$composePath = Join-Path $PSScriptRoot 'docker/compose.cpu.yaml'
$pin = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'models.lock.json') -Raw | ConvertFrom-Json
$dockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue
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
  $process = Start-Process -FilePath (Get-Command wsl).Source -ArgumentList @('-d',$Distribution,'-u','root','--','sleep','infinity') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath 'keeper.out.log') -RedirectStandardError (Join-Path $runtimePath 'keeper.err.log')
  Start-Sleep -Milliseconds 500
  if ($process.HasExited) { throw 'Unable to keep the WSL model service available.' }
  $saved = @{processId=$process.Id;started=$process.StartTime.ToUniversalTime().ToFileTimeUtc().ToString();distribution=$Distribution}
  [IO.File]::WriteAllText($keeperPath,($saved | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
}
function Stop-DirectorKeeper {
  $keeper = Get-DirectorKeeper
  if ($keeper) { Stop-Process -Id $keeper.Id -ErrorAction Stop }
}
if (-not $dockerCommand) {
  if ($Action -in @('start','pull')) { Start-DirectorKeeper }
  $linuxCompose = & wsl -d $Distribution -- wslpath -u ($composePath.Replace('\','/'))
  if ($LASTEXITCODE -ne 0) { throw 'Ubuntu WSL is unavailable.' }
}
function Invoke-DirectorDocker([string[]]$DockerArguments) {
  if ($dockerCommand) { & $dockerCommand.Source compose -f $composePath @DockerArguments }
  else { & wsl -d $Distribution -u root -- docker compose -f $linuxCompose @DockerArguments }
  if ($LASTEXITCODE -ne 0) { throw 'Director Docker operation failed.' }
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
  'start' { Invoke-DirectorDocker -DockerArguments @('up','-d'); Wait-DirectorApi }
  'pull' {
    Invoke-DirectorDocker -DockerArguments @('up','-d'); Wait-DirectorApi
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
    if (-not $dockerCommand) { Stop-DirectorKeeper }
  }
}
