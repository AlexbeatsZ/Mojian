param(
    [string]$AndroidDrawableDirectory = (Join-Path $PSScriptRoot '..\..\android\app\src\main\res\drawable'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\src\Notein-win\Themes\IconResources.xaml')
)

$files = Get-ChildItem -LiteralPath $AndroidDrawableDirectory -Filter 'ic_fluent_*_24_regular.xml' | Sort-Object Name
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"')
$lines.Add('                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">')
$lines.Add('    <!-- Generated from the Android Mojian Fluent vector resources. Do not edit by hand. -->')
foreach ($file in $files) {
    [xml]$document = Get-Content -Raw -LiteralPath $file.FullName
    $paths = @($document.vector.path)
    $pathData = @($paths | ForEach-Object { $_.pathData }) -join ' '
    if ([string]::IsNullOrWhiteSpace($pathData)) { continue }
    $name = $file.BaseName -replace '^ic_fluent_', '' -replace '_24_regular$', ''
    $escaped = [System.Security.SecurityElement]::Escape($pathData)
    $lines.Add("    <Geometry x:Key=`"Fluent_$name`">$escaped</Geometry>")
    $literalColors = @($paths | ForEach-Object { $_.fillColor } | Where-Object { $_ -match '^#[0-9A-Fa-f]{6,8}$' } | Select-Object -Unique)
    if ($literalColors.Count -gt 1) {
        $lines.Add("    <DrawingImage x:Key=`"FluentDrawing_$name`">")
        $lines.Add('        <DrawingImage.Drawing>')
        $lines.Add('            <DrawingGroup>')
        foreach ($path in $paths) {
            if ($path.fillColor -notmatch '^#[0-9A-Fa-f]{6,8}$') { continue }
            $part = [System.Security.SecurityElement]::Escape([string]$path.pathData)
            $lines.Add("                <GeometryDrawing Brush=`"$($path.fillColor)`" Geometry=`"$part`" />")
        }
        $lines.Add('            </DrawingGroup>')
        $lines.Add('        </DrawingImage.Drawing>')
        $lines.Add('    </DrawingImage>')
    }
}
$lines.Add('    <Geometry x:Key="Fluent_chevron_down">M7 9l5 5 5-5z</Geometry>')
$lines.Add('    <Geometry x:Key="Fluent_chevron_right">M9 7l5 5-5 5z</Geometry>')
$lines.Add('</ResourceDictionary>')

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedOutput)) | Out-Null
[System.IO.File]::WriteAllLines($resolvedOutput, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Generated $($files.Count) Android Fluent icons at $resolvedOutput"
