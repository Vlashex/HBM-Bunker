param([string]$Destination=[Environment]::GetFolderPath('DesktopDirectory'))
$ErrorActionPreference='Stop'
$repository=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
if(-not (Test-Path -LiteralPath $Destination -PathType Container)) { throw 'Shortcut destination must exist.' }
$shell=New-Object -ComObject WScript.Shell
$items=@(
 @{Name='DEAD EARTH SRP - Start Server'; Bat='legacy/START-SERVER.bat'; Icon='server-start.ico'; Description='Start the complete DEAD EARTH SRP Docker Desktop stack.'},
 @{Name='DEAD EARTH SRP - Stop Server'; Bat='legacy/STOP-SERVER.bat'; Icon='server-stop.ico'; Description='Save and stop the DEAD EARTH SRP Docker Desktop stack.'}
)
foreach($item in $items) {
 $target=Join-Path $repository $item.Bat
 $icon=Join-Path $repository ('launcher-icons/'+$item.Icon)
 if(-not (Test-Path -LiteralPath $target -PathType Leaf) -or -not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw 'Launcher or icon missing.' }
 $path=Join-Path $Destination ($item.Name+'.lnk')
 $shortcut=$shell.CreateShortcut($path)
 if((Test-Path -LiteralPath $path) -and $shortcut.TargetPath -and $shortcut.TargetPath -ne $target) { throw ('Refusing to replace another shortcut: '+$path) }
 $shortcut.TargetPath=$target
 $shortcut.WorkingDirectory=$repository
 $shortcut.Arguments=''
 $shortcut.Description=$item.Description
 $shortcut.IconLocation=$icon+',0'
 $shortcut.WindowStyle=1
 $shortcut.Save()
 $saved=$shell.CreateShortcut($path)
 if($saved.TargetPath -ne $target -or $saved.WorkingDirectory -ne $repository) { throw 'Shortcut verification failed.' }
 Write-Host ('Created: '+$path)
}
