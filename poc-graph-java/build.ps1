# Graph 郵件 PoC：編譯、跑離線測試、打包 build\graph-poc.jar
# Windows PowerShell 5.1 可直接執行：
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
# javax.mail.jar：預設先找 lib\javax.mail.jar，沒有再找 Notes 的 jvm\lib\ext；也可用 -MailJar 指定
# Notes.jar：有的話一併編譯 Domino agent（GraphNotes、GraphReadAgent、GraphSendAgent），沒有就略過，命令列工具照樣能用
param(
    [string]$NotesExt = "C:\HCL\NotesR1202\jvm\lib\ext",
    [string]$MailJar = "",
    [string]$NotesJar = ""
)
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$testClasses = Join-Path $build "test-classes"

if (-not $MailJar) {
    $localMail = Join-Path $root "lib\javax.mail.jar"
    if (Test-Path -LiteralPath $localMail) { $MailJar = $localMail } else { $MailJar = Join-Path $NotesExt "javax.mail.jar" }
}
if (-not $NotesJar) { $NotesJar = Join-Path $NotesExt "Notes.jar" }
if (-not (Test-Path -LiteralPath $MailJar)) {
    throw "找不到 javax.mail.jar（$MailJar）。請下載到 lib\javax.mail.jar，或用 -MailJar 指定路徑，見 README"
}
$withDomino = Test-Path -LiteralPath $NotesJar
$dominoOnly = @("GraphNotes.java", "GraphReadAgent.java", "GraphSendAgent.java")

if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
New-Item -ItemType Directory -Path $classes | Out-Null
New-Item -ItemType Directory -Path $testClasses | Out-Null

$srcFiles = @(Get-ChildItem -LiteralPath (Join-Path $root "src") -Filter *.java)
if ($withDomino) {
    $cp = "$NotesJar;$MailJar"
} else {
    Write-Host "沒有 Notes.jar（$NotesJar），略過 Domino agent 類別：$($dominoOnly -join '、')"
    $srcFiles = @($srcFiles | Where-Object { $dominoOnly -notcontains $_.Name })
    $cp = $MailJar
}
$src = @($srcFiles | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -cp $cp -d $classes $src
if ($LASTEXITCODE -ne 0) { throw "編譯 src 失敗" }

$test = @(Get-ChildItem -LiteralPath (Join-Path $root "test") -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -cp "$classes;$MailJar" -d $testClasses $test
if ($LASTEXITCODE -ne 0) { throw "編譯 test 失敗" }

Write-Host "== 離線測試 =="
& java -cp "$classes;$testClasses;$MailJar" OfflineTest
if ($LASTEXITCODE -ne 0) { throw "離線測試失敗" }

# 可直接 java -jar 執行；javax.mail.jar 複製到同一個資料夾，由 Class-Path 載入
$manifest = Join-Path $build "MANIFEST.MF"
[IO.File]::WriteAllText($manifest, "Main-Class: GraphPocCli`r`nClass-Path: javax.mail.jar`r`n", (New-Object Text.UTF8Encoding($false)))
& jar cfm (Join-Path $build "graph-poc.jar") $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "打包 jar 失敗" }
Copy-Item -LiteralPath $MailJar -Destination (Join-Path $build "javax.mail.jar")
Write-Host "已產生 $(Join-Path $build 'graph-poc.jar')（執行：java -jar build\graph-poc.jar <設定檔> <指令>）"
