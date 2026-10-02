[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputDirectory, [int]$MaxPages=0)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath($OutputDirectory)
$taskManifest=[IO.File]::ReadAllText((Join-Path $taskRoot 'manifest.json')) | ConvertFrom-Json
$taskOneNote=New-Object -ComObject OneNote.Application
$taskEnrichment=Join-Path $taskRoot 'enrichment'
[IO.Directory]::CreateDirectory($taskEnrichment) | Out-Null
function Visit-Pages($sections) { foreach($section in $sections) { $section.pages; Visit-Pages $section.children } }
function Number-Attribute($node,$name,$default) {
    if($null -eq $node -or -not $node.HasAttribute($name)) { return $default }
    return [double]::Parse($node.GetAttribute($name),[Globalization.CultureInfo]::InvariantCulture)
}
$taskCount=0; $taskFiles=0; $taskWarnings=0
foreach($taskNotebook in $taskManifest.notebooks) {
    foreach($taskPage in (Visit-Pages $taskNotebook.sections)) {
        $taskJson=Join-Path $taskRoot $taskPage.json
        if(-not (Test-Path -LiteralPath $taskJson)) { continue }
        if($MaxPages -gt 0 -and $taskCount -ge $MaxPages) { break }
        $taskCount++
        $taskXml=''; $taskOneNote.GetPageContent($taskPage.sourceId,[ref]$taskXml,0,1)
        [xml]$taskDoc=$taskXml
        $taskManager=[Xml.XmlNamespaceManager]::new($taskDoc.NameTable)
        $taskManager.AddNamespace('one',$taskDoc.DocumentElement.NamespaceURI)
        $taskImages=[Collections.Generic.List[object]]::new()
        foreach($taskImage in $taskDoc.SelectNodes('//one:Image',$taskManager)) {
            $taskPosition=$taskImage.SelectSingleNode('./one:Position',$taskManager)
            $taskSize=$taskImage.SelectSingleNode('./one:Size',$taskManager)
            if($null -eq $taskPosition -or $null -eq $taskSize) { continue }
            $taskImages.Add([ordered]@{
                isPrintout=($taskImage.GetAttribute('isPrintOut') -eq 'true')
                zOrder=[long](Number-Attribute $taskPosition 'z' -1)
                sourceObjectId=$taskImage.GetAttribute('objectID')
                x=(Number-Attribute $taskPosition 'x' 0)*96/72
                y=(Number-Attribute $taskPosition 'y' 0)*96/72
                width=(Number-Attribute $taskSize 'width' 240)*96/72
                height=(Number-Attribute $taskSize 'height' 120)*96/72
            })
        }
        $taskObjects=[Collections.Generic.List[object]]::new()
        $taskFileIndex=0
        foreach($taskFile in $taskDoc.SelectNodes('//one:InsertedFile',$taskManager)) {
            $taskCache=$taskFile.GetAttribute('pathCache')
            $taskName=$taskFile.GetAttribute('preferredName')
            if([string]::IsNullOrWhiteSpace($taskCache) -or -not [IO.File]::Exists($taskCache)) {
                $taskWarnings++; continue
            }
            $taskExtension=[IO.Path]::GetExtension($taskName).ToLowerInvariant()
            if($taskExtension -notmatch '^\.[a-z0-9]{1,10}$') { $taskExtension='.bin' }
            $taskAsset='cache-attachment-'+$taskFileIndex+$taskExtension
            $taskFileIndex++
            $taskAssets=Join-Path $taskRoot ('assets\'+$taskPage.id)
            [IO.Directory]::CreateDirectory($taskAssets) | Out-Null
            $taskDestination=Join-Path $taskAssets $taskAsset
            if(-not [IO.File]::Exists($taskDestination)) { [IO.File]::Copy($taskCache,$taskDestination,$false) }
            $taskFiles++
            $taskPosition=$taskFile.SelectSingleNode('./one:Position',$taskManager)
            if($null -eq $taskPosition) { $taskPosition=$taskFile.SelectSingleNode('ancestor::one:Outline[1]/one:Position',$taskManager) }
            $taskObjects.Add([ordered]@{
                id='object-onenote-cache-'+$taskPage.id+'-'+$taskFileIndex
                kind='Attachment'; displayName=$taskName; assetName=$taskAsset
                mimeType=$(if($taskExtension -eq '.pdf') {'application/pdf'} else {'application/octet-stream'})
                x=(Number-Attribute $taskPosition 'x' 18)*96/72; y=(Number-Attribute $taskPosition 'y' 12)*96/72
                width=280; height=64; zOrder=[long](Number-Attribute $taskPosition 'z' 0)
            })
        }
        # Keep metadata separate: the native point/path export can exceed 1 GB.
        # PowerShell must not deserialize and rewrite that entire corpus again.
        $taskMetadata=[ordered]@{version=1;images=$taskImages;objects=$taskObjects}
        [IO.File]::WriteAllText((Join-Path $taskEnrichment ($taskPage.id+'.json')),($taskMetadata | ConvertTo-Json -Depth 8 -Compress),[Text.UTF8Encoding]::new($false))
        Write-Output "ONENOTE_ENRICH_PAGE $taskCount ok"
    }
    if($MaxPages -gt 0 -and $taskCount -ge $MaxPages) { break }
}
[IO.File]::WriteAllText((Join-Path $taskRoot 'enrichment-report.json'),([ordered]@{pages=$taskCount;embeddedFiles=$taskFiles;missingCacheFiles=$taskWarnings}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "ONENOTE_ENRICH_DONE pages=$taskCount files=$taskFiles missing=$taskWarnings"
