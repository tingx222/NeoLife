$ErrorActionPreference = 'Stop'
$sourceDir = Join-Path $PSScriptRoot '../frontend/hmdp'
$targetDir = 'D:\Osborn\redis\code\nginx-1.18.0\html\hmdp'
foreach ($relative in @('index.html','customer-service.html','shop-detail.html','info.html','shop-list.html','orders.html','js/common.js','js/footer.js')) {
    $targetFile = Join-Path $targetDir $relative
    if (Test-Path -LiteralPath $targetFile) {
        $backupFile = $targetFile + '.before-customer-service-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.bak'
        Copy-Item -LiteralPath $targetFile -Destination $backupFile
    }
    Copy-Item -LiteralPath (Join-Path $sourceDir $relative) -Destination $targetFile
}
