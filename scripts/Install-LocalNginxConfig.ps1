param([ValidateRange(1024,65535)][int]$BackendPort = 8081)
$ErrorActionPreference = 'Stop'
$nginxDirectory = 'D:\Osborn\redis\code\nginx-1.18.0'
$configFile = Join-Path $nginxDirectory 'conf/nginx.conf'
$backupFile = $configFile + '.before-course-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.bak'
Copy-Item -LiteralPath $configFile -Destination $backupFile
$content = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../frontend/nginx.conf') -Raw
$content.Replace('server 127.0.0.1:8081 ', "server 127.0.0.1:$BackendPort ") | Set-Content -Encoding UTF8 $configFile
try {
    & (Join-Path $nginxDirectory 'nginx.exe') -t -p ($nginxDirectory + '/') -c conf/nginx.conf
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration validation failed' }
    & (Join-Path $nginxDirectory 'nginx.exe') -s reload -p ($nginxDirectory + '/') -c conf/nginx.conf
    if ($LASTEXITCODE -ne 0) { throw 'Nginx reload failed' }
} catch {
    Copy-Item -LiteralPath $backupFile -Destination $configFile
    throw
}
