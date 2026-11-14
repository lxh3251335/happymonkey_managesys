param([switch]$SkipBuild, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskLogs = Join-Path $taskRoot 'logs'
$taskState = Join-Path $taskRoot 'data'
New-Item -ItemType Directory -Force -Path $taskLogs, $taskState | Out-Null
$javaExe = (Get-Command java.exe -ErrorAction Stop).Source
$nodeExe = (Get-Command node.exe -ErrorAction Stop).Source
$jarPath = Join-Path $taskRoot 'backend\target\project-commerce-0.1.0.jar'
$webIndex = Join-Path $taskRoot 'frontend\dist\index.html'
$processState = Join-Path $taskState 'local-processes.json'

if (Test-Path -LiteralPath $processState) {
    $saved = Get-Content -Raw -LiteralPath $processState | ConvertFrom-Json
    $running = @($saved | Where-Object {
        $proc = Get-Process -Id $_.id -ErrorAction SilentlyContinue
        $proc -and $proc.StartTime.ToUniversalTime().Ticks -eq $_.started
    })
    if ($running.Count -eq 2) {
        Write-Host '服务已运行：http://127.0.0.1:5173'
        if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173' }
        return
    }
}
foreach ($port in 8080,5173) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $port 已被占用。请先检查已有服务，避免重复启动。"
    }
}
if (-not $SkipBuild) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $mavenExe = if ($mavenCommand) { $mavenCommand.Source } else { 'C:\Program Files\apache-maven-3.9.15\bin\mvn.cmd' }
    if (-not (Test-Path -LiteralPath $mavenExe)) { throw '请安装 Maven 3.9，并将 mvn.cmd 加入 PATH。' }
    Push-Location (Join-Path $taskRoot 'backend')
    try { & $mavenExe '-Dmaven.repo.local=../.m2' -q verify; if ($LASTEXITCODE -ne 0) { throw '后端验证失败' } } finally { Pop-Location }
    Push-Location (Join-Path $taskRoot 'frontend')
    try {
        & npm.cmd ci --cache ../.npm-cache --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $webIndex)) { throw '缺少构建产物，请不带 -SkipBuild 重新运行。' }
$env:IMPORT_SOURCE = if (Test-Path (Join-Path $taskRoot 'docs\source-seed.json')) { 'true' } else { 'false' }
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SOURCE_SEED = Join-Path $taskRoot 'docs\source-seed.json'
$backend = Start-Process -FilePath $javaExe -ArgumentList @('-Duser.timezone=Asia/Shanghai','-jar',('"'+$jarPath+'"')) -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs 'backend.out.log') -RedirectStandardError (Join-Path $taskLogs 'backend.err.log')
$vitePath = Join-Path $taskRoot 'frontend\node_modules\vite\bin\vite.js'
$env:API_TARGET = 'http://127.0.0.1:8080'
$frontend = Start-Process -FilePath $nodeExe -ArgumentList @(('"'+$vitePath+'"'),'preview','--configLoader','native','--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory (Join-Path $taskRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs 'frontend.out.log') -RedirectStandardError (Join-Path $taskLogs 'frontend.err.log')
@($backend,$frontend) | ForEach-Object { @{ id=$_.Id; started=$_.StartTime.ToUniversalTime().Ticks } } | ConvertTo-Json | Set-Content -LiteralPath $processState -Encoding utf8
$ready = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    try { $health=Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 1; if ($health.status -eq 'UP') { $ready=$true; break } } catch {}
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw "后端尚未就绪，请检查 $taskLogs 中的日志。" }
Write-Host '本地服务已启动：http://127.0.0.1:5173'
Write-Host '开发账户：admin / DevAdmin2026!（仅用于本机，请登录后修改）'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173' }
