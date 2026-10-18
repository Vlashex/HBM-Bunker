function Select-DirectorBackend {
  param([ValidateSet('auto','windows','wsl')][string]$Requested,[bool]$WindowsReady,[bool]$WslReady,[bool]$WindowsHasModels,[bool]$WslHasModels)
  if($Requested -eq 'windows') { if($WindowsReady) { return 'windows' }; throw 'Windows Docker daemon is unavailable.' }
  if($Requested -eq 'wsl') { if($WslReady) { return 'wsl' }; throw 'WSL Docker daemon is unavailable.' }
  if($WslReady -and $WslHasModels) { return 'wsl' }
  if($WindowsReady -and $WindowsHasModels) { return 'windows' }
  if($WindowsReady) { return 'windows' }
  if($WslReady) { return 'wsl' }
  throw 'No reachable Docker daemon. Start Docker Desktop or the configured Ubuntu Docker service.'
}

function Test-DirectorCommand {
  param([string]$Executable,[string]$Arguments,[int]$TimeoutMilliseconds=8000)
  if(-not $Executable) { return $false }
  $process=New-Object Diagnostics.Process
  $process.StartInfo=New-Object Diagnostics.ProcessStartInfo
  $process.StartInfo.FileName=$Executable
  $process.StartInfo.Arguments=$Arguments
  $process.StartInfo.UseShellExecute=$false
  $process.StartInfo.CreateNoWindow=$true
  $process.StartInfo.RedirectStandardOutput=$true
  $process.StartInfo.RedirectStandardError=$true
  try {
    [void]$process.Start()
    if(-not $process.WaitForExit($TimeoutMilliseconds)) {
      $process.Kill(); [void]$process.WaitForExit(1000); return $false
    }
    return ($process.ExitCode -eq 0)
  } catch { return $false }
  finally { $process.Dispose() }
}
