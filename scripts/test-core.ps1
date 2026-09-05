param([Parameter(Mandatory=$true)][string]$JavaHome, [switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$compiler = Join-Path $repo '.tools/kotlin/kotlinc/lib'
$jars = @('junit.jar','hamcrest.jar','json.jar') | ForEach-Object { Join-Path $repo ".tools/$_" }
$classPath = $jars -join ';'
$sources = @(
    "$repo/android/app/src/main/java/com/familyledger/app/domain/LedgerRules.kt",
    "$repo/android/app/src/main/java/com/familyledger/app/data/BackupCodec.kt",
    "$repo/android/app/src/test/java/com/familyledger/app/domain/LedgerRulesTest.kt",
    "$repo/android/app/src/test/java/com/familyledger/app/data/BackupCodecTest.kt"
)
$sources += Get-ChildItem "$repo/android/app/src/main/java/com/familyledger/app/domain" -Filter '*.kt' | Where-Object Name -ne 'LedgerRules.kt' | ForEach-Object FullName
$sources += @('XlsxCodec.kt','SpreadsheetImport.kt','ImportOriginCodec.kt','QuickEntryCodec.kt') | ForEach-Object { "$repo/android/app/src/main/java/com/familyledger/app/data/$_" } | Where-Object { Test-Path $_ }
$sources += Get-ChildItem "$repo/android/app/src/sharedTest" -Recurse -Filter '*.kt' | ForEach-Object FullName
$sources += "$repo/android/app/src/test/java/com/familyledger/app/data/SpreadsheetImportTest.kt"
$java = Join-Path $JavaHome 'bin/java.exe'
& $java -cp "$compiler/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home (Split-Path -Parent $compiler) -jvm-target 17 -classpath $classPath -d "$repo/.tools/core-tests.jar" @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($CompileOnly) { exit 0 }
& $java -cp "$repo/.tools/core-tests.jar;$compiler/*;$classPath" org.junit.runner.JUnitCore com.familyledger.app.domain.LedgerRulesTest com.familyledger.app.data.BackupCodecTest com.familyledger.app.data.SpreadsheetImportTest
exit $LASTEXITCODE
