$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $taskRoot 'data\local-processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有本脚本登记的服务。'; return }
foreach ($entry in (Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json)) {
    $taskProcess = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq $entry.started) {
        Stop-Process -Id $taskProcess.Id
    }
}
Remove-Item -LiteralPath $statePath
Write-Host '已停止本项目登记的本地服务，数据库文件保留。'
