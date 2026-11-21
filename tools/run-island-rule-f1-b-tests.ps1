# SPDX-License-Identifier: GPL-3.0-only
# ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
param([switch]$CompileOnly, [switch]$Capture, [switch]$Preview, [switch]$Editor, [switch]$Pack, [switch]$PackSaf)
$ErrorActionPreference = 'Stop'
chcp 65001 > $null
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repository = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repository -Parent
$jdk = Join-Path $workspace 'EnvTools\jdk-21.0.13+9'
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle-local'
$env:TEMP = Join-Path $env:GRADLE_USER_HOME 'tmp'
$env:TMP = $env:TEMP
$destination = Join-Path $env:GRADLE_USER_HOME ('f1-b-jvm-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $destination,$env:TEMP | Out-Null
function Select-CachedJar($group, $artifact, $version) {
    $path = Join-Path $env:GRADLE_USER_HOME "caches\modules-2\files-2.1\$group\$artifact\$version"
    $jar = Get-ChildItem -LiteralPath $path -Filter "$artifact-$version.jar" -File -Recurse | Select-Object -First 1
    if (!$jar) { throw "Missing cached dependency $artifact-$version" }
    $jar.FullName
}
$junit = Select-CachedJar 'junit' 'junit' '4.13.2'
$hamcrest = Select-CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'
$json = Select-CachedJar 'org.json' 'json' '20240303'
$xposed = Select-CachedJar 'de.robv.android.xposed' 'api' '82'
$sdk = Join-Path $workspace 'EnvTools\Android\Sdk\platforms\android-35\android.jar'
$resources = Join-Path $repository 'app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar'
$config = Join-Path $repository 'app\build\generated\source\buildConfig\debug\com\hyperos3\focusrestore\BuildConfig.java'
foreach ($required in @($sdk,$resources,$config)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing existing compiler input $required" }
}
$classes = @('IslandRuleRepositoryTest','IslandRuleBatchTest','IslandRuleAccessTest','IslandRuleProjectionTest','IslandRulePublisherTest','IslandRuleSetLoaderTest','IslandRuleCodecTest','IslandRuleSelectorTest','IslandNotificationTypeTest','IslandRuleExtractorTest','IslandRulePostProcessorTest','IslandRuleExecutorTest','IslandRuleTextEngineTest','IslandRuleDiagnosticHistoryTest','IslandC2BoundaryTest','IslandC2IntegrationTest','HookRegistrationTest','InitialHookModeTest')
if ($Capture -or $Preview -or $Editor) {
    $classes += @('IslandCaptureRuntimeTest','IslandCaptureDocumentWriterTest','IslandCaptureSessionTest','IslandExportGeneratorTest','IslandExportBoundaryTest','IslandD2AdapterTest','IslandD2IntegrationTest','RomFocusVisibilityTest')
}
if ($Preview -or $Editor) { $classes += 'IslandCapturePreviewTest' }
if ($Editor) { $classes += @('IslandCaptureEditorTest','IslandRulePostSaveTest','OS3IslandPromptRuleTest','WeakIdentityMapTest','IslandRuleManagerListTest','IslandSavedRuleFieldsTest') }
if ($Pack -or $PackSaf) { $classes += @('IslandRulePackTest','IslandRulePackRepositoryTest') }
if ($PackSaf) { $classes += 'IslandRulePackSafTest' }
$mainSources = Get-ChildItem -LiteralPath (Join-Path $repository 'app\src\main\java') -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName }
$sources = @($mainSources) + @($config)
if (!$CompileOnly) {
    $sources += Join-Path $repository 'app\src\test\java\com\hyperos3\focusrestore\IslandRuleDiagnosticHistory.java'
    $sources += $classes | ForEach-Object { Join-Path $repository "app\src\test\java\com\hyperos3\focusrestore\$_.java" }
}
$argFile = Join-Path $destination 'sources.txt'
[IO.File]::WriteAllLines($argFile, [string[]]($sources | ForEach-Object { '"' + ($_ -replace '\\','/') + '"' }), [Text.UTF8Encoding]::new($false))
& "$jdk\bin\javac.exe" '-J-Dfile.encoding=UTF-8' --release 8 -encoding UTF-8 -cp "$json;$sdk;$xposed;$resources;$junit;$hamcrest" -d $destination "@$argFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($CompileOnly) { exit 0 }
Push-Location $repository
try {
    $names = $classes | ForEach-Object { "com.hyperos3.focusrestore.$_" }
    Write-Output ('JUnit classes: ' + ($classes -join ', '))
    & "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' "-Djava.io.tmpdir=$env:TEMP" -cp "$destination;$json;$sdk;$xposed;$resources;$junit;$hamcrest" org.junit.runner.JUnitCore @names
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
