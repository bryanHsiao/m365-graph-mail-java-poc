# 情資系統 Graph PoC：用 Java 程式依序測試 取 token → 寄信 → 列信 → 讀信解析
# 先執行 setup\setup-m365-poc.ps1 產生設定檔。用法（Windows PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File .\run-test.ps1
# 測試信預設寄給專用信箱自己（設定檔的 testTo），等幾秒後再讀出來解析。
param(
    [string]$Config = "",
    [string]$OutDir = "",
    [int]$WaitSeconds = 30
)
$ErrorActionPreference = "Stop"

# 預設都在專案的 local\ 底下
if (-not $Config) { $Config = Join-Path $PSScriptRoot "local\graph-poc.properties" }
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot "local\out" }

$jar = Join-Path $PSScriptRoot "build\graph-poc.jar"
if (-not (Test-Path -LiteralPath $Config)) { throw "找不到設定檔 $Config，請先執行 setup\setup-m365-poc.ps1" }
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host "還沒建置，先執行 build.ps1"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "建置失敗" }
}

function Invoke-Cli([string]$title, [string[]]$cliArgs) {
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
    & java -jar $jar $Config @cliArgs
    if ($LASTEXITCODE -ne 0) {
        throw "$title 失敗。若是 ErrorAccessDenied，表示 Exchange 授權還沒生效（最長約 2 小時），稍後再執行一次"
    }
}

Write-Host "Java 版本（Domino 11 是 Java 8）："
& java -version

Invoke-Cli "1. 取得 token" @("token")
Invoke-Cli "2. 寄測試信（中文主旨、HTML 表格、中文檔名附件）" @("send")
Write-Host "等 $WaitSeconds 秒讓信送達……"
Start-Sleep -Seconds $WaitSeconds
Invoke-Cli "3. 列出收件匣" @("list")
Invoke-Cli "4. 讀信並解析（原始信存成 .eml 到 $OutDir，不搬移）" @("read", $OutDir)

Write-Host ""
Write-Host "全部完成。確認解析結果沒問題後，可執行下面指令把信搬到「已處理」資料夾：" -ForegroundColor Green
Write-Host "java -jar `"$jar`" `"$Config`" process `"$OutDir`""
