param([ValidateRange(1024,65535)][int]$BackendPort = 8081)
$ErrorActionPreference = 'Stop'
$nginxDirectory = 'D:\Osborn\redis\code\nginx-1.18.0'
$configFile = Join-Path $nginxDirectory 'conf/nginx.conf'
$backupFile = $configFile + '.before-course-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.bak'
Copy-Item -LiteralPath $configFile -Destination $backupFile
$sourceFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../frontend/nginx.conf'))
$content = [IO.File]::ReadAllText($sourceFile, [Text.Encoding]::UTF8)
$content = $content.TrimStart([char]0xFEFF).Replace('server 127.0.0.1:8081 ', "server 127.0.0.1:$BackendPort ")
# Windows PowerShell 5.1 writes a BOM with Set-Content -Encoding UTF8.
# Nginx treats that BOM as part of its first directive; write BOM-less UTF-8 explicitly.
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configFile, $content, $utf8WithoutBom)
try {
    & (Join-Path $nginxDirectory 'nginx.exe') -t -p ($nginxDirectory + '/') -c conf/nginx.conf
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration validation failed' }
    $nginxExecutable = Join-Path $nginxDirectory 'nginx.exe'
    $running = @(Get-Process -Name nginx -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
        & $nginxExecutable -s reload -p ($nginxDirectory + '/') -c conf/nginx.conf
        if ($LASTEXITCODE -ne 0) { throw 'Nginx reload failed; check the PID file and running instance.' }
        Write-Output 'Nginx configuration reloaded.'
    } else {
        $nginxProcess = Start-Process -FilePath $nginxExecutable -WorkingDirectory $nginxDirectory -ArgumentList @('-p', ($nginxDirectory + '/'), '-c', 'conf/nginx.conf') -WindowStyle Hidden -PassThru
        if ($nginxProcess.WaitForExit(1500) -and $nginxProcess.ExitCode -ne 0) {
            throw 'Nginx startup failed; check logs/error.log.'
        }
        Write-Output 'Nginx started with the updated configuration.'
    }
} catch {
    Copy-Item -LiteralPath $backupFile -Destination $configFile
    throw
}
