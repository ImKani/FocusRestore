# SPDX-License-Identifier: GPL-3.0-only
# ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
# C1/C2-1/C2-2/C2-3 纯 JVM 回归；不调用 Gradle 或构建 APK。
$ErrorActionPreference = 'Stop'
chcp 65001 > $null
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repository = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repository -Parent
$env:JAVA_HOME = Join-Path $workspace 'EnvTools\jdk-21.0.13+9'
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle-local'
$env:TEMP = Join-Path $env:GRADLE_USER_HOME 'tmp'
$env:TMP = $env:TEMP
$destination = Join-Path $env:GRADLE_USER_HOME 'c2-3-jvm-20261118'
New-Item -ItemType Directory -Force -Path $destination,$env:TEMP | Out-Null
$junit = Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\junit\junit\4.13.2\8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12\junit-4.13.2.jar'
$hamcrest = Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar'
$sources = @('IslandRule.java','IslandRuleSet.java','MatchClause.java','ExtractSpec.java','DisplaySpec.java','IslandRulePaths.java','IslandRuleCodec.java','IslandRuleSetLoader.java','IslandRuleSetCache.java','IslandRuleContext.java','IslandRuleSelector.java','IslandRuleSelection.java','IslandRuleMatchValidator.java','IslandCaptureRecord.java','IslandCaptureSession.java') | ForEach-Object {
    Join-Path $repository "app\src\main\java\com\hyperos3\focusrestore\$_"
}
$tests = @('IslandRuleCodecTest.java','IslandRuleSetLoaderTest.java','IslandRuleSelectorTest.java','IslandCaptureSessionTest.java') | ForEach-Object {
    Join-Path $repository "app\src\test\java\com\hyperos3\focusrestore\$_"
}
& "$env:JAVA_HOME\bin\javac.exe" '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' --release 8 -encoding UTF-8 -cp "$junit;$hamcrest" -d $destination @sources @tests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location $repository
try {
    & "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' "-Djava.io.tmpdir=$env:TEMP" -cp "$destination;$junit;$hamcrest" org.junit.runner.JUnitCore com.hyperos3.focusrestore.IslandRuleCodecTest com.hyperos3.focusrestore.IslandRuleSetLoaderTest com.hyperos3.focusrestore.IslandRuleSelectorTest com.hyperos3.focusrestore.IslandCaptureSessionTest
    $resultCode = $LASTEXITCODE
} finally { Pop-Location }
exit $resultCode
