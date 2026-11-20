$ErrorActionPreference='Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
  $files=@(& git ls-files)
  if($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tracked files' }
  $bad=@($files | Where-Object { $_ -match '(^|/)(\.env|[^/]+\.(log|dump|sql\.gz))$' })
  if($bad.Count) { throw 'Forbidden environment/log/dump file tracked' }
  $patterns=@('gh[pousr]_[A-Za-z0-9]{30,}','github_pat_[A-Za-z0-9_]{40,}','-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----','AKIA[A-Z0-9]{16}')
  foreach($file in $files) {
    $content=Get-Content -LiteralPath $file -Raw
    foreach($pattern in $patterns) { if($content -match $pattern) { throw "Potential secret in tracked file: $file (value withheld)" } }
  }
  Write-Output "PASS: $($files.Count) tracked files scanned; no forbidden env/log/dump or recognized credential pattern"
} finally { Pop-Location }
