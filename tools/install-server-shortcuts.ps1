param([string]$Destination=[Environment]::GetFolderPath('DesktopDirectory'))
$ErrorActionPreference='Stop'
$repository=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
if(-not (Test-Path -LiteralPath $Destination -PathType Container)) { throw 'Shortcut destination must be an existing directory.' }
$shell=New-Object -ComObject WScript.Shell
$items=@(
    @{Name='DEAD EARTH - Start Server'; Bat='start-dead-earth.bat'; Icon='server-start.ico'; Description='Start the Minecraft server, Docker and the local AI director.'},
    @{Name='DEAD EARTH - Stop Server'; Bat='stop-dead-earth.bat'; Icon='server-stop.ico'; Description='Request a normal server shutdown with world saving.'}
)
foreach($item in $items) {
    $target=Join-Path $repository $item.Bat
    $icon=Join-Path $repository ('launcher-icons/'+$item.Icon)
    if(-not (Test-Path -LiteralPath $target -PathType Leaf) -or -not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw 'Launcher or icon is missing.' }
    $path=Join-Path $Destination ($item.Name+'.lnk')
    $shortcut=$shell.CreateShortcut($path)
    if((Test-Path -LiteralPath $path) -and $shortcut.TargetPath -and $shortcut.TargetPath -ne $target) {
        throw ('Refusing to replace a shortcut pointing elsewhere: '+$path)
    }
    $shortcut.TargetPath=$target
    $shortcut.WorkingDirectory=$repository
    $shortcut.Arguments=''
    $shortcut.Description=$item.Description
    $shortcut.IconLocation=$icon+',0'
    $shortcut.WindowStyle=1
    $shortcut.Save()
    $saved=$shell.CreateShortcut($path)
    if($saved.TargetPath -ne $target -or $saved.WorkingDirectory -ne $repository -or $saved.IconLocation -ne ($icon+',0')) { throw 'Saved shortcut verification failed.' }
    Write-Host ('Created: '+$path)
}
