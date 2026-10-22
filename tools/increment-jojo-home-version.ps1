param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$properties = @{}
if (Test-Path -LiteralPath $Path) {
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
            $properties[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
}

$currentName = if ($properties.ContainsKey('versionName')) { $properties['versionName'] } else { '0.2.0' }
$parts = $currentName -split '\.' | ForEach-Object {
    $number = 0
    if (-not [int]::TryParse($_, [ref]$number)) { $number = 0 }
    $number
}
while ($parts.Count -lt 3) { $parts += 0 }
if ($parts[0] -eq 0 -and $parts[1] -eq 0) {
    # Start the new sequence after the last 0.0.x build.
    $parts[1] = 2
    $parts[2] = 1
} elseif ($parts[2] -ge 100) {
    $parts[1] = [int]$parts[1] + 1
    $parts[2] = 1
} else {
    $parts[2] = [int]$parts[2] + 1
}
$nextName = '{0}.{1}.{2}' -f $parts[0], $parts[1], $parts[2]
$currentCode = 0
if ($properties.ContainsKey('versionCode')) { [int]::TryParse($properties['versionCode'], [ref]$currentCode) | Out-Null }
$nextCode = [Math]::Max(1, $currentCode + 1)

$content = "versionName=$nextName`r`nversionCode=$nextCode`r`n"
[System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $Path).Path, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output "VERSION=$nextName"
Write-Output "CODE=$nextCode"
