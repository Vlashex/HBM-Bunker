param([string]$InstallDirectory=(Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop'))
$ErrorActionPreference='Stop'
$repository=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$install=(Resolve-Path -LiteralPath $InstallDirectory).ProviderPath
$docker=Join-Path $install 'resources\bin\docker.exe'
$icon=Join-Path $install 'Docker Desktop.exe'
foreach($binary in @($docker,$icon)) {
    if(-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw 'Docker executable is missing.' }
    $signature=Get-AuthenticodeSignature -LiteralPath $binary
    if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Docker Inc') { throw 'A valid Docker Inc signature is required.' }
}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$backup=Join-Path $repository ('work\docker-desktop-shortcuts\'+$stamp)
New-Item -ItemType Directory -Path $backup | Out-Null
$shell=New-Object -ComObject WScript.Shell
$paths=@(
    (Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) 'Docker Desktop.lnk'),
    (Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Docker Desktop.lnk')
)
$index=0
foreach($path in $paths) {
    if(-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $shortcut=$shell.CreateShortcut($path)
    if($shortcut.TargetPath -ne $icon -and $shortcut.TargetPath -ne $docker) { throw ('Unrelated shortcut: '+$path) }
    Copy-Item -LiteralPath $path -Destination (Join-Path $backup ($index.ToString()+'-Docker Desktop.lnk'))
    $shortcut.TargetPath=$docker
    $shortcut.Arguments='desktop start --detach'
    $shortcut.WorkingDirectory=$install
    $shortcut.IconLocation=$icon+',0'
    $shortcut.Description='Start Docker Desktop through its official Desktop CLI.'
    $shortcut.WindowStyle=7
    $shortcut.Save()
    $saved=$shell.CreateShortcut($path)
    if($saved.TargetPath -ne $docker -or $saved.Arguments -ne 'desktop start --detach') { throw 'Shortcut verification failed.' }
    $index++
}
Write-Host ('Updated '+$index+' Docker Desktop shortcuts; original links saved in '+$backup)
