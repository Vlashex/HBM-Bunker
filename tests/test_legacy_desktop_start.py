"""Cold Docker Desktop bootstrap regression without starting real processes."""
import subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class DesktopBootstrap(unittest.TestCase):
 def run_ps(self,script):
  result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',"$ErrorActionPreference='Stop'; try {"+script+"; exit 0} catch {Write-Error $_.Exception.Message; exit 1}"],capture_output=True,text=True,timeout=20)
  self.assertEqual(result.returncode,0,result.stdout+result.stderr)
 def test_ready_engine_skips_launcher(self):
  helper=ROOT/'legacy/docker/Start-Desktop.ps1'
  self.assertTrue(helper.is_file(),'Cold-start helper is missing')
  self.run_ps(". '"+str(helper)+r"'; function Test-DesktopEngine { return $true }; function Invoke-DesktopStart { throw 'must not start' }; Ensure-DesktopEngine -Docker 'C:\fake\docker.exe'")
 def test_missing_registry_uses_signed_backend_and_waits(self):
  helper=ROOT/'legacy/docker/Start-Desktop.ps1'
  self.assertTrue(helper.is_file(),'Cold-start helper is missing')
  self.run_ps(". '"+str(helper)+r"'; $script:checks=0; $script:starts=0; function Test-DesktopEngine {$script:checks++;return ($script:checks -gt 1)}; function Invoke-DesktopStart {return $false}; function Start-VerifiedDockerBackend {$script:starts++}; Ensure-DesktopEngine -Docker 'C:\fake\docker.exe'; if($script:starts -ne 1){throw 'backend fallback absent'}")
 def test_successful_detach_with_no_engine_also_uses_verified_fallback(self):
  helper=ROOT/'legacy/docker/Start-Desktop.ps1'
  self.run_ps(". '"+str(helper)+r"'; $script:checks=0; $script:starts=0; function Test-DesktopEngine {$script:checks++;return ($script:checks -gt 2)}; function Invoke-DesktopStart {return $true}; function Start-VerifiedDockerBackend {$script:starts++}; Ensure-DesktopEngine -Docker 'C:/fake/docker.exe'; if($script:starts -ne 1){throw 'successful CLI hid missing engine'}")
 def test_invalid_signature_cannot_launch_backend(self):
  helper=ROOT/'legacy/docker/Start-Desktop.ps1'
  self.assertTrue(helper.is_file(),'Cold-start helper is missing')
  self.run_ps(". '"+str(helper)+r"'; function Test-Path {return $true}; function Get-AuthenticodeSignature {return @{Status='NotSigned';SignerCertificate=@{Subject='O=Other'}}}; function Start-Process {throw 'unverified launch'}; try {Start-VerifiedDockerBackend -Docker 'C:\fake\resources\bin\docker.exe'; throw 'accepted unsigned'} catch {if($_.Exception.Message -notlike '*Docker Inc signature*'){throw}}")
if __name__=='__main__':unittest.main()
