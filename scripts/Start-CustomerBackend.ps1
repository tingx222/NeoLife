param([ValidateRange(1024,65535)][int]$Port = 8083)
$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifact = Join-Path $projectDirectory 'target/hm-dianping-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $artifact)) { throw 'Run mvn -DskipTests package first.' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use; no existing process was stopped."
}
$logDirectory = Join-Path $projectDirectory 'tmp'
New-Item -ItemType Directory -Force $logDirectory | Out-Null
$process = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', ('"' + $artifact + '"'), "--server.port=$Port") -WorkingDirectory $projectDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'customer-backend.log') -RedirectStandardError (Join-Path $logDirectory 'customer-backend-error.log') -PassThru
@{ pid=$process.Id; port=$Port; started=(Get-Date -Format o) } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $logDirectory 'customer-backend-process.json')
Write-Output "Backend started: PID $($process.Id), port $Port."
