# Сборка debug-APK приложения агента.
#
# Пути к инструментам заданы явно: в системе стоит Java 8, и Gradle,
# запущенный без указания JAVA_HOME, взял бы её и упал — Android Gradle
# Plugin требует 17.

$ErrorActionPreference = 'Stop'

$JDK    = 'C:\Users\USER\tools\jdk-17.0.20+8'
$GRADLE = 'C:\Users\USER\tools\gradle-8.11.1\bin\gradle.bat'
# SDK лежит рядом с остальным инструментом, а не в AppData: каталог tools
# точно виден и из среды сборки, и из обычного сеанса пользователя.
$SDK    = 'C:\Users\USER\tools\android-sdk'

if (-not (Test-Path $JDK))    { throw "нет JDK 17: $JDK" }
if (-not (Test-Path $GRADLE)) { throw "нет Gradle: $GRADLE" }
if (-not (Test-Path $SDK))    { throw "нет Android SDK: $SDK" }

$env:JAVA_HOME       = $JDK
$env:ANDROID_HOME    = $SDK
$env:ANDROID_SDK_ROOT = $SDK

Set-Location $PSScriptRoot
& $GRADLE @args

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $мб = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host ""
    Write-Host "APK: $apk ($мб МБ)"
}
