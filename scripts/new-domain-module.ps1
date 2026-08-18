[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{2,99}$')][string]$ModuleKey,
  [Parameter(Mandatory)][string]$ModuleName,
  [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$')][string]$BasePackage,
  [Parameter(Mandatory)][string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
$templateRoot = Join-Path $PSScriptRoot '..\templates\domain-module'
$resolvedTemplate = (Resolve-Path -LiteralPath $templateRoot).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputRoot)
if ($resolvedOutput -eq [System.IO.Path]::GetPathRoot($resolvedOutput)) {
  throw 'OutputRoot không được là filesystem root.'
}

$moduleRoot = Join-Path $resolvedOutput ('modules\' + $ModuleKey)
if (Test-Path -LiteralPath $moduleRoot) {
  throw "Module đã tồn tại: $moduleRoot"
}

$packagePath = $BasePackage.Replace('.', [System.IO.Path]::DirectorySeparatorChar)
$backendTarget = Join-Path $resolvedOutput ('backend\src\main\java\' + $packagePath)
$migrationTarget = Join-Path $resolvedOutput ('backend\src\main\resources\db\migration\' + $ModuleKey)
$frontendTarget = Join-Path $resolvedOutput ('frontend\app\business\' + $ModuleKey)
$migrationVersion = Get-Date -Format 'yyyyMMddHHmm'
$sqlKey = $ModuleKey.Replace('-', '_')

[System.IO.Directory]::CreateDirectory($moduleRoot) | Out-Null
[System.IO.Directory]::CreateDirectory($backendTarget) | Out-Null
[System.IO.Directory]::CreateDirectory($migrationTarget) | Out-Null
[System.IO.Directory]::CreateDirectory($frontendTarget) | Out-Null

function Expand-Template([string]$Source, [string]$Target) {
  $content = [System.IO.File]::ReadAllText($Source)
  $content = $content.Replace('__MODULE_KEY__', $ModuleKey)
  $content = $content.Replace('__MODULE_KEY_SQL__', $sqlKey)
  $content = $content.Replace('__MODULE_NAME__', $ModuleName)
  $content = $content.Replace('__BASE_PACKAGE__', $BasePackage)
  $content = $content.Replace('__MIGRATION_VERSION__', $migrationVersion)
  [System.IO.File]::WriteAllText($Target, $content, [System.Text.UTF8Encoding]::new($false))
}

Expand-Template (Join-Path $resolvedTemplate 'module-manifest.yaml.template') (Join-Path $moduleRoot 'module-manifest.yaml')
Expand-Template (Join-Path $resolvedTemplate 'backend\DomainModule.java.template') (Join-Path $backendTarget 'DomainModule.java')
Expand-Template (Join-Path $resolvedTemplate 'backend\DomainAdapter.java.template') (Join-Path $backendTarget 'DomainAdapter.java')
Expand-Template (Join-Path $resolvedTemplate 'backend\V__MIGRATION_VERSION____MODULE_KEY_SQL____baseline.sql.template') (Join-Path $migrationTarget ("V${migrationVersion}__${sqlKey}__baseline.sql"))
Expand-Template (Join-Path $resolvedTemplate 'frontend\page.tsx.template') (Join-Path $frontendTarget 'page.tsx')

Write-Output "Đã tạo module $ModuleKey tại $resolvedOutput"
Write-Output 'Cần thay __PERMISSION_RESOURCE__, __RESOURCE_TYPE__, __RESOURCE_NAME__ và hoàn thiện TODO trước khi commit.'
