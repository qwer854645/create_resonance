$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$gradle = "f:\others\_tools\gradle-9.4.1\bin\gradle.bat"
$project = "f:\others\create_harmonics_unofficial"

Set-Location $project
& $gradle --stop
& $gradle :neoforge:runClient "-Dorg.gradle.java.home=$env:JAVA_HOME"
