# 不用 Gradle 的最小构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 工具在 E:\DevTools（JDK 17、build-tools 35.0.1、android-35 平台）
$ErrorActionPreference = 'Stop'
$tools = 'E:\DevTools'
$env:JAVA_HOME = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$bt = "$tools\bt\android-15"
$jar = "$tools\plat\android-35\android.jar"
$keystore = "$tools\pgadskip.jks"   # 签名密钥放在仓库外
# aapt2 打不开带中文的路径，所以复制到纯英文目录里构建，最后把 apk 拷回 build\
$stage = "$tools\pgadskip-stage"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse "$PSScriptRoot\res", "$PSScriptRoot\src", "$PSScriptRoot\AndroidManifest.xml" $stage
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

if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -alias pgadskip -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass pgadskip -keypass pgadskip -dname 'CN=pgadskip' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:pgadskip --key-pass pass:pgadskip `
    --out "$out\pgadskip.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
New-Item -ItemType Directory -Force "$PSScriptRoot\build" | Out-Null
Copy-Item -Force "$out\pgadskip.apk" "$PSScriptRoot\build\pgadskip.apk"
Write-Output "OK $PSScriptRoot\build\pgadskip.apk $((Get-Item "$out\pgadskip.apk").Length) bytes"
