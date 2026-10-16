param([string]$PythonPath = 'python')
$ErrorActionPreference = 'Stop'
$pythonCommand = Get-Command $PythonPath -CommandType Application -ErrorAction Stop
& $pythonCommand.Source -c 'import sys; assert sys.version_info[:2] == (3, 12), "Python 3.12 is required"'
if ($LASTEXITCODE -ne 0) { throw 'Select Python 3.12 with -PythonPath.' }
$environmentPath = Join-Path $PSScriptRoot '.venv'
if (-not (Test-Path -LiteralPath $environmentPath)) {
    & $pythonCommand.Source -m venv $environmentPath
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create project environment.' }
}
$projectPython = Join-Path $environmentPath 'Scripts/python.exe'
$dependencyLock = Join-Path $PSScriptRoot 'requirements-windows-py312.lock'
& $projectPython -m pip --isolated install --require-hashes --index-url https://pypi.org/simple -r $dependencyLock
if ($LASTEXITCODE -ne 0) { throw 'Dependency verification failed.' }
Write-Host 'Dead Earth MCP environment is ready.'
