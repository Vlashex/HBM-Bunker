import json,os,subprocess,unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

@unittest.skipUnless(os.name=='nt','Windows launcher policy')
class DockerBackendTests(unittest.TestCase):
    def test_explicit_backend_and_existing_model_cache_drive_selection(self):
        helper=ROOT/'docker-backend.ps1'
        self.assertTrue(helper.is_file(),'Docker backend helper is missing')
        script="$ErrorActionPreference='Stop'; . '"+str(helper).replace("'","''")+"'; @("+";".join([
            "Select-DirectorBackend auto $true $true $false $true",
            "Select-DirectorBackend auto $true $true $true $false",
            "Select-DirectorBackend auto $true $false $false $false",
            "Select-DirectorBackend auto $false $true $false $false",
            "Select-DirectorBackend windows $true $true $false $true",
            "Select-DirectorBackend wsl $true $true $true $false",
        ])+") | ConvertTo-Json -Compress"
        result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',script],capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout),['wsl','windows','windows','wsl','windows','wsl'])

    def test_unavailable_explicit_backend_and_no_daemon_fail_closed(self):
        helper=ROOT/'docker-backend.ps1'
        self.assertTrue(helper.is_file(),'Docker backend helper is missing')
        script="$ErrorActionPreference='Stop'; . '"+str(helper).replace("'","''")+"'; $failed=0; foreach($call in @({Select-DirectorBackend auto $false $false $false $false},{Select-DirectorBackend windows $false $true $false $true},{Select-DirectorBackend wsl $true $false $true $false})){try{& $call}catch{$failed++}}; if($failed -ne 3){exit 9}"
        result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',script],capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)

if __name__=='__main__':unittest.main()