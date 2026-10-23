param([string]$Maven='mvn',[string]$Repository=(Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/build/m2'))
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
  & $Maven "-Dmaven.repo.local=$Repository" clean package
  if($LASTEXITCODE -ne 0){throw '編譯或測試失敗'}
  $version=([xml](Get-Content pom.xml -Raw)).project.version
  New-Item -ItemType Directory -Force output | Out-Null
  Copy-Item target/tokaShop-$version.jar output -Force
  $shared=Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/output'
  New-Item -ItemType Directory -Force $shared | Out-Null
  Copy-Item output/tokaShop-$version.jar $shared -Force
  Get-FileHash output/tokaShop-$version.jar
} finally {Pop-Location}
