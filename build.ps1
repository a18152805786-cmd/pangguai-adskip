# 不用 Gradle 的最小构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 工具在 E:\DevTools（JDK 17、build-tools 35.0.1、android-35 平台）
param(
    [string]$SigningConfig = $(if ($env:PGADSKIP_SIGNING_CONFIG) { $env:PGADSKIP_SIGNING_CONFIG } else { 'E:\DevTools\pgadskip-signing.properties' })
)

$ErrorActionPreference = 'Stop'
$tools = 'E:\DevTools'
# 配置按 key=value 读取，反斜杠保持原样，Windows 路径不需要转义。
function Assert-OutsideRepo([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $repo = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
    if ($full.Equals($repo, [StringComparison]::OrdinalIgnoreCase) -or
        $full.StartsWith($repo + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw '签名配置和密钥库必须放在仓库外。'
    }
    return $full
}
$SigningConfig = Assert-OutsideRepo $SigningConfig
if (-not (Test-Path -LiteralPath $SigningConfig -PathType Leaf)) {
    throw "缺少签名配置：$SigningConfig。请在仓库外创建 UTF-8 的 key=value 文件，填写 storeFile、storePassword、keyAlias、keyPassword；使用现有密钥库，不会自动生成密钥。详见 README 的构建一节。"
}
$signing = @{}
foreach ($line in [IO.File]::ReadAllLines($SigningConfig)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    $pos = $line.IndexOf('=')
    if ($pos -lt 1) { throw '签名配置格式错误，请使用 key=value。' }
    $key = $line.Substring(0, $pos).Trim()
    if ($key -notin @('storeFile', 'storePassword', 'keyAlias', 'keyPassword') -or $signing.ContainsKey($key)) {
        throw '签名配置包含未知或重复的字段。'
    }
    $signing[$key] = $line.Substring($pos + 1)
}
foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if ([string]::IsNullOrEmpty($signing[$key])) { throw "签名配置缺少字段：$key" }
}
if (-not [IO.Path]::IsPathRooted($signing.storeFile)) { throw 'storeFile 必须使用仓库外的绝对路径。' }
$keystore = Assert-OutsideRepo $signing.storeFile
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) { throw "密钥库不存在：$keystore。请提供现有密钥库，不会自动生成。" }

$env:JAVA_HOME = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$bt = "$tools\bt\android-15"
$jar = "$tools\plat\android-35\android.jar"
# aapt2 打不开带中文的路径，所以复制到纯英文目录里构建，最后把 apk 拷回 build\
$stage = [IO.Path]::GetFullPath("$tools\pgadskip-stage")
if ($stage -ne 'E:\DevTools\pgadskip-stage') { throw '暂存目录超出预期范围。' }
if (Test-Path -LiteralPath $stage) {
    if ((Get-Item -LiteralPath $stage).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '暂存目录不能是链接。' }
    Remove-Item -LiteralPath $stage -Recurse -Force
}
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse "$PSScriptRoot\res", "$PSScriptRoot\src", "$PSScriptRoot\AndroidManifest.xml" $stage
# 根目录名单是唯一来源；副本和哈希只生成在构建暂存目录。
New-Item -ItemType Directory -Force "$stage\res\raw" | Out-Null
$hashes = @()
foreach ($name in @('blocklist', 'hide')) {
    Copy-Item -LiteralPath "$PSScriptRoot\$name.txt" -Destination "$stage\res\raw\$name.txt"
    $hash = (Get-FileHash -LiteralPath "$stage\res\raw\$name.txt" -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashes += "    <string name=`"${name}_hash`" translatable=`"false`">$hash</string>"
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText("$stage\res\values\builtin_lists.xml", "<resources>`n$($hashes -join "`n")`n</resources>`n", $utf8)
$src = $stage
$out = "$stage\out"
New-Item -ItemType Directory -Force "$out\res", "$out\gen", "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$src\res" -o "$out\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$bt\aapt2.exe" link -I $jar --manifest "$src\AndroidManifest.xml" -o "$out\unsigned.apk" "$out\res.zip" `
    --java "$out\gen" --min-sdk-version 26 --target-sdk-version 35 --auto-add-overlay; if ($LASTEXITCODE) { throw 'aapt2 link' }

$java = Get-ChildItem -Recurse "$src\src", "$out\gen" -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 --release 11 -nowarn -classpath $jar -d "$out\classes" @java; if ($LASTEXITCODE) { throw 'javac' }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --lib $jar --min-api 26 --output "$out\dex" @classes; if ($LASTEXITCODE) { throw 'd8' }

# 把 classes.dex 放进 apk
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$z.Dispose()

& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }

# 口令只短暂放入子进程环境，命令行和输出里都不带明文。
$oldStorePass = $env:PGADSKIP_STORE_PASSWORD
$oldKeyPass = $env:PGADSKIP_KEY_PASSWORD
try {
    $env:PGADSKIP_STORE_PASSWORD = $signing.storePassword
    $env:PGADSKIP_KEY_PASSWORD = $signing.keyPassword
    & "$bt\apksigner.bat" sign --ks $keystore --ks-key-alias $signing.keyAlias `
        --ks-pass env:PGADSKIP_STORE_PASSWORD --key-pass env:PGADSKIP_KEY_PASSWORD `
        --out "$out\pgadskip.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
} finally {
    $env:PGADSKIP_STORE_PASSWORD = $oldStorePass
    $env:PGADSKIP_KEY_PASSWORD = $oldKeyPass
    $signing.Clear()
}
& "$bt\apksigner.bat" verify "$out\pgadskip.apk"; if ($LASTEXITCODE) { throw 'apksigner verify' }
Write-Output 'APK signature verified'
New-Item -ItemType Directory -Force "$PSScriptRoot\build" | Out-Null
Copy-Item -Force "$out\pgadskip.apk" "$PSScriptRoot\build\pgadskip.apk"
Write-Output "OK $PSScriptRoot\build\pgadskip.apk $((Get-Item "$out\pgadskip.apk").Length) bytes"
