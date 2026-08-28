[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [switch]$Resume,
    [ValidateRange(0, 1000000)]
    [int]$MaxPages = 0,
    [ValidateRange(10, 3600)]
    [int]$PdfTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName System.Web

function New-SafeId {
    param([string]$Prefix, [string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha.ComputeHash($bytes)
        $suffix = -join ($hash[0..11] | ForEach-Object { $_.ToString('x2') })
        return "$Prefix-$suffix"
    }
    finally {
        $sha.Dispose()
    }
}

function Get-DoubleAttribute {
    param([Xml.XmlElement]$Node, [string]$Name, [double]$Default = 0)
    if ($null -eq $Node -or -not $Node.HasAttribute($Name)) { return $Default }
    $value = 0.0
    if ([double]::TryParse($Node.GetAttribute($Name), [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
        return $value
    }
    return $Default
}

function Convert-HtmlToPlainText {
    param([string]$Html)
    if ([string]::IsNullOrWhiteSpace($Html)) { return '' }
    $value = [regex]::Replace($Html, '(?i)<br\s*/?>', "`n")
    $value = [regex]::Replace($value, '(?i)</(?:p|div|li)>', "`n")
    $value = [regex]::Replace($value, '<[^>]+>', '')
    return [Web.HttpUtility]::HtmlDecode($value).Trim()
}

function Get-ColorHex {
    param([Windows.Media.Color]$Color)
    return '#{0:X2}{1:X2}{2:X2}{3:X2}' -f $Color.A, $Color.R, $Color.G, $Color.B
}

function Export-PagePdfWithTimeout {
    param(
        [string]$SourceId,
        [string]$OutputPath,
        [int]$TimeoutSeconds
    )
    $helper = Join-Path $PSScriptRoot 'Export-OneNotePagePdf.ps1'
    $temporary = "$OutputPath.partial.pdf"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $helper,
        '-SourceId', $SourceId,
        '-OutputPath', $temporary
    )
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -PassThru -WindowStyle Hidden
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill()
        $process.WaitForExit()
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        return $false
    }
    if ($process.ExitCode -eq 0 -and (Test-Path -LiteralPath $temporary)) {
        Move-Item -LiteralPath $temporary -Destination $OutputPath -Force
        return $true
    }
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    return $false
}

function Convert-InkDrawing {
    param(
        [Xml.XmlElement]$InkNode,
        [Xml.XmlNamespaceManager]$NamespaceManager,
        [System.Collections.Generic.List[object]]$Warnings
    )
    $dataNode = $InkNode.SelectSingleNode('./one:Data', $NamespaceManager)
    $positionNode = $InkNode.SelectSingleNode('./one:Position', $NamespaceManager)
    if ($null -eq $dataNode -or $null -eq $positionNode) { return @() }
    try {
        $bytes = [Convert]::FromBase64String($dataNode.InnerText)
        $stream = [IO.MemoryStream]::new($bytes, $false)
        try { $collection = [Windows.Ink.StrokeCollection]::new($stream) }
        finally { $stream.Dispose() }
    }
    catch {
        $Warnings.Add([ordered]@{ kind = 'ink-decode'; message = $_.Exception.Message })
        return @()
    }
    if ($collection.Count -eq 0) { return @() }

    $allPoints = @($collection | ForEach-Object { $_.StylusPoints })
    $minX = ($allPoints | ForEach-Object { $_.X } | Measure-Object -Minimum).Minimum
    $minY = ($allPoints | ForEach-Object { $_.Y } | Measure-Object -Minimum).Minimum
    $anchorX = (Get-DoubleAttribute $positionNode 'x') * 96.0 / 72.0
    $anchorY = (Get-DoubleAttribute $positionNode 'y') * 96.0 / 72.0
    $result = [Collections.Generic.List[object]]::new()
    foreach ($stroke in $collection) {
        $elapsed = 0
        $points = [Collections.Generic.List[object]]::new()
        foreach ($point in $stroke.StylusPoints) {
            $points.Add([ordered]@{
                x = [Math]::Round($anchorX + $point.X - $minX, 4)
                y = [Math]::Round($anchorY + $point.Y - $minY, 4)
                pressure = [Math]::Round([Math]::Max(0.01, [Math]::Min(1.0, $point.PressureFactor)), 5)
                elapsedMilliseconds = $elapsed
            })
            $elapsed += 8
        }
        $attributes = $stroke.DrawingAttributes
        $result.Add([ordered]@{
            id = New-SafeId 'stroke-onenote' ($InkNode.GetAttribute('objectID') + ':' + $result.Count)
            kind = $(if ($attributes.IsHighlighter) { 'Highlighter' } else { 'Pen' })
            colorHex = Get-ColorHex $attributes.Color
            widthMm = [Math]::Round([Math]::Max($attributes.Width, $attributes.Height) * 25.4 / 96.0, 4)
            opacityPercent = $(if ($attributes.IsHighlighter) { 32 } else { [Math]::Round($attributes.Color.A * 100.0 / 255.0) })
            points = $points
        })
    }
    return $result
}

function Export-PageContent {
    param(
        [object]$OneNote,
        [Xml.XmlElement]$HierarchyPage,
        [string]$PageId,
        [string]$PageJsonPath,
        [string]$AssetDirectory
    )
    $content = ''
    $OneNote.GetPageContent($HierarchyPage.GetAttribute('ID'), [ref]$content, 7, 1)
    [xml]$document = $content
    $manager = [Xml.XmlNamespaceManager]::new($document.NameTable)
    $manager.AddNamespace('one', $document.DocumentElement.NamespaceURI)
    $warnings = [Collections.Generic.List[object]]::new()

    $strokes = [Collections.Generic.List[object]]::new()
    foreach ($ink in $document.SelectNodes('//one:InkDrawing', $manager)) {
        foreach ($stroke in (Convert-InkDrawing $ink $manager $warnings)) { $strokes.Add($stroke) }
    }

    $textItems = [Collections.Generic.List[object]]::new()
    foreach ($outline in $document.SelectNodes('//one:Outline', $manager)) {
        $position = $outline.SelectSingleNode('./one:Position', $manager)
        $size = $outline.SelectSingleNode('./one:Size', $manager)
        if ($null -eq $position) { continue }
        $parts = [Collections.Generic.List[string]]::new()
        foreach ($textNode in $outline.SelectNodes('.//one:T', $manager)) {
            $plain = Convert-HtmlToPlainText $textNode.InnerText
            if (-not [string]::IsNullOrWhiteSpace($plain)) { $parts.Add($plain) }
        }
        $plainText = ($parts -join "`n").Trim()
        if ([string]::IsNullOrWhiteSpace($plainText)) { continue }
        $textItems.Add([ordered]@{
            id = New-SafeId 'text-onenote' ($PageId + ':' + $textItems.Count)
            x = [Math]::Round((Get-DoubleAttribute $position 'x') * 96.0 / 72.0, 4)
            y = [Math]::Round((Get-DoubleAttribute $position 'y') * 96.0 / 72.0, 4)
            width = [Math]::Round([Math]::Max(80, (Get-DoubleAttribute $size 'width' 320) * 96.0 / 72.0), 4)
            height = [Math]::Round([Math]::Max(36, (Get-DoubleAttribute $size 'height' 60) * 96.0 / 72.0), 4)
            text = $plainText
            fontSize = 16
        })
    }

    [IO.Directory]::CreateDirectory($AssetDirectory) | Out-Null
    $objects = [Collections.Generic.List[object]]::new()
    foreach ($image in $document.SelectNodes('//one:Image', $manager)) {
        $dataNode = $image.SelectSingleNode('./one:Data', $manager)
        $position = $image.SelectSingleNode('./one:Position', $manager)
        $size = $image.SelectSingleNode('./one:Size', $manager)
        if ($null -eq $dataNode -or $null -eq $position -or $null -eq $size) { continue }
        try { $bytes = [Convert]::FromBase64String($dataNode.InnerText) }
        catch { $warnings.Add([ordered]@{ kind = 'image-decode'; message = $_.Exception.Message }); continue }
        $format = $dataNode.GetAttribute('format').ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($format)) { $format = 'png' }
        if ($format -notin @('png', 'jpg', 'jpeg', 'gif', 'bmp', 'tif', 'tiff')) { $format = 'bin' }
        $name = ('image-{0:D4}.{1}' -f $objects.Count, $format)
        [IO.File]::WriteAllBytes((Join-Path $AssetDirectory $name), $bytes)
        $objects.Add([ordered]@{
            id = New-SafeId 'object-onenote' ($PageId + ':image:' + $objects.Count)
            kind = 'Image'
            x = [Math]::Round((Get-DoubleAttribute $position 'x') * 96.0 / 72.0, 4)
            y = [Math]::Round((Get-DoubleAttribute $position 'y') * 96.0 / 72.0, 4)
            width = [Math]::Round((Get-DoubleAttribute $size 'width' 240) * 96.0 / 72.0, 4)
            height = [Math]::Round((Get-DoubleAttribute $size 'height' 120) * 96.0 / 72.0, 4)
            assetName = $name
            mimeType = $(if ($format -eq 'jpg') { 'image/jpeg' } else { "image/$format" })
        })
    }

    foreach ($file in $document.SelectNodes('//one:InsertedFile', $manager)) {
        $dataNode = $file.SelectSingleNode('./one:Data', $manager)
        $position = $file.SelectSingleNode('./one:Position', $manager)
        if ($null -eq $dataNode -or $null -eq $position) { continue }
        try { $bytes = [Convert]::FromBase64String($dataNode.InnerText) }
        catch { $warnings.Add([ordered]@{ kind = 'attachment-decode'; message = $_.Exception.Message }); continue }
        $preferredName = $file.GetAttribute('preferredName')
        $extension = [IO.Path]::GetExtension($preferredName)
        if ([string]::IsNullOrWhiteSpace($extension)) { $extension = '.bin' }
        $name = ('attachment-{0:D4}{1}' -f $objects.Count, $extension)
        [IO.File]::WriteAllBytes((Join-Path $AssetDirectory $name), $bytes)
        $objects.Add([ordered]@{
            id = New-SafeId 'object-onenote' ($PageId + ':attachment:' + $objects.Count)
            kind = 'Attachment'
            x = [Math]::Round((Get-DoubleAttribute $position 'x') * 96.0 / 72.0, 4)
            y = [Math]::Round((Get-DoubleAttribute $position 'y') * 96.0 / 72.0, 4)
            width = 280
            height = 64
            assetName = $name
            displayName = $preferredName
            mimeType = 'application/octet-stream'
        })
    }

    $payload = [ordered]@{
        version = 1
        pageId = $PageId
        title = $HierarchyPage.GetAttribute('name')
        createdAt = $HierarchyPage.GetAttribute('dateTime')
        updatedAt = $HierarchyPage.GetAttribute('lastModifiedTime')
        strokes = $strokes
        textItems = $textItems
        objects = $objects
        warnings = $warnings
    }
    [IO.File]::WriteAllText($PageJsonPath, ($payload | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    return [ordered]@{ strokes = $strokes.Count; textItems = $textItems.Count; objects = $objects.Count; warnings = $warnings.Count }
}

function Convert-SectionNode {
    param(
        [object]$OneNote,
        [Xml.XmlElement]$Node,
        [Xml.XmlNamespaceManager]$Manager,
        [string]$PagesDirectory,
        [string]$PdfDirectory,
        [string]$AssetsDirectory,
        [System.Collections.Generic.List[object]]$PageQueue
    )
    $sourceId = $Node.GetAttribute('ID')
    $sectionId = New-SafeId 'section-onenote' $sourceId
    $result = [ordered]@{ id = $sectionId; title = $Node.GetAttribute('name'); pages = @(); children = @() }
    if ($Node.LocalName -eq 'Section') {
        $pages = [Collections.Generic.List[object]]::new()
        foreach ($page in $Node.SelectNodes('./one:Page', $Manager)) {
            $pageId = New-SafeId 'page-onenote' $page.GetAttribute('ID')
            $descriptor = [ordered]@{
                id = $pageId
                sourceId = $page.GetAttribute('ID')
                title = $page.GetAttribute('name')
                createdAt = $page.GetAttribute('dateTime')
                updatedAt = $page.GetAttribute('lastModifiedTime')
                json = "pages/$pageId.json"
                pdf = "pdf/$pageId.pdf"
            }
            $pages.Add($descriptor)
            $PageQueue.Add([ordered]@{ hierarchy = $page; descriptor = $descriptor })
        }
        $result.pages = $pages
    }
    foreach ($child in $Node.SelectNodes('./one:SectionGroup|./one:Section', $Manager)) {
        $result.children += Convert-SectionNode $OneNote $child $Manager $PagesDirectory $PdfDirectory $AssetsDirectory $PageQueue
    }
    return $result
}

$root = [IO.Path]::GetFullPath($OutputDirectory)
$pagesDirectory = Join-Path $root 'pages'
$pdfDirectory = Join-Path $root 'pdf'
$assetsDirectory = Join-Path $root 'assets'
[IO.Directory]::CreateDirectory($pagesDirectory) | Out-Null
[IO.Directory]::CreateDirectory($pdfDirectory) | Out-Null
[IO.Directory]::CreateDirectory($assetsDirectory) | Out-Null

$oneNote = New-Object -ComObject OneNote.Application
$hierarchy = ''
$oneNote.GetHierarchy('', 4, [ref]$hierarchy)
[xml]$hierarchyDocument = $hierarchy
$hierarchyManager = [Xml.XmlNamespaceManager]::new($hierarchyDocument.NameTable)
$hierarchyManager.AddNamespace('one', $hierarchyDocument.DocumentElement.NamespaceURI)

$pageQueue = [Collections.Generic.List[object]]::new()
$notebooks = [Collections.Generic.List[object]]::new()
foreach ($notebook in $hierarchyDocument.SelectNodes('//one:Notebook', $hierarchyManager)) {
    $sections = [Collections.Generic.List[object]]::new()
    foreach ($child in $notebook.SelectNodes('./one:SectionGroup|./one:Section', $hierarchyManager)) {
        $sections.Add((Convert-SectionNode $oneNote $child $hierarchyManager $pagesDirectory $pdfDirectory $assetsDirectory $pageQueue))
    }
    $notebooks.Add([ordered]@{
        id = New-SafeId 'notebook-onenote' $notebook.GetAttribute('ID')
        sourceId = $notebook.GetAttribute('ID')
        title = $notebook.GetAttribute('name')
        sections = $sections
    })
}

$completed = 0
$failed = 0
$totals = [ordered]@{ strokes = 0; textItems = 0; objects = 0; warnings = 0 }
foreach ($item in $pageQueue) {
    if ($MaxPages -gt 0 -and $completed -ge $MaxPages) { break }
    $completed++
    $descriptor = $item.descriptor
    $pageJsonPath = Join-Path $root $descriptor.json
    $pagePdfPath = Join-Path $root $descriptor.pdf
    $pageAssetDirectory = Join-Path $assetsDirectory $descriptor.id
    try {
        if (-not ($Resume -and (Test-Path -LiteralPath $pageJsonPath))) {
            $stats = Export-PageContent $oneNote $item.hierarchy $descriptor.id $pageJsonPath $pageAssetDirectory
            foreach ($key in @('strokes', 'textItems', 'objects', 'warnings')) { $totals[$key] += $stats[$key] }
        }
        if (-not ($Resume -and (Test-Path -LiteralPath $pagePdfPath))) {
            if (-not (Export-PagePdfWithTimeout $descriptor.sourceId $pagePdfPath $PdfTimeoutSeconds)) {
                $descriptor.pdfError = "PDF export exceeded $PdfTimeoutSeconds seconds or failed."
            }
        }
        Write-Output ("ONENOTE_PAGE {0}/{1} ok" -f $completed, $pageQueue.Count)
    }
    catch {
        $failed++
        $descriptor.error = $_.Exception.Message
        Write-Output ("ONENOTE_PAGE {0}/{1} failed HRESULT=0x{2:X8}" -f $completed, $pageQueue.Count, ($_.Exception.HResult -band 0xffffffffL))
    }
}

$manifest = [ordered]@{
    version = 1
    source = 'onenote-desktop-com'
    exportedAt = [DateTime]::UtcNow.ToString('o')
    notebooks = $notebooks
    stats = [ordered]@{
        notebookCount = $notebooks.Count
        pageCount = $pageQueue.Count
        failedPages = $failed
        processedPages = $completed
        strokes = $totals.strokes
        textItems = $totals.textItems
        objects = $totals.objects
        warnings = $totals.warnings
    }
}
[IO.File]::WriteAllText((Join-Path $root 'manifest.json'), ($manifest | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
Write-Output ("ONENOTE_DONE pages={0} failed={1}" -f $pageQueue.Count, $failed)
