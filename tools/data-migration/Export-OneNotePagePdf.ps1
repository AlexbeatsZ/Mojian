param(
    [Parameter(Mandatory = $true)]
    [string]$SourceId,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$oneNote = New-Object -ComObject OneNote.Application
$oneNote.Publish($SourceId, [IO.Path]::GetFullPath($OutputPath), 3, '')

