param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [string]$AdbPath = "adb",
    [int]$Rounds = 6,
    [long]$BytesPerRound = 50000000,
    [int]$ConnectTimeoutSeconds = 10,
    [int]$MaxTimeSeconds = 180
)

$ErrorActionPreference = "Stop"
if ($Rounds -lt 1 -or $Rounds -gt 100) {
    throw "Rounds must be between 1 and 100"
}
if ($BytesPerRound -lt 1 -or $BytesPerRound -gt 100000000) {
    throw "BytesPerRound must be between 1 and 100000000"
}
if (Test-Path -LiteralPath $OutputPath) {
    throw "Refusing to overwrite existing load-test evidence: $OutputPath"
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}

$allSuccessful = $true
for ($round = 1; $round -le $Rounds; $round++) {
    # Android 8's standalone curl CA bundle can be older than Chrome's. -k
    # affects certificate verification only; the transfer still uses TLS and
    # remains suitable for stressing the WWG data path.
    $remoteCommand = (
        "curl -4 -k -L -sS --connect-timeout $ConnectTimeoutSeconds " +
        "--max-time $MaxTimeSeconds -o /dev/null " +
        "-w CODE=%{http_code},BYTES=%{size_download},SPEED=%{speed_download},TIME=%{time_total} " +
        "https://speed.cloudflare.com/__down?bytes=$BytesPerRound"
    )
    $output = & $AdbPath "-s" $Serial "shell" $remoteCommand 2>&1
    $exitCode = $LASTEXITCODE
    $text = (($output | Out-String).Trim())
    $match = [regex]::Match(
        $text,
        "CODE=(?<code>\d+),BYTES=(?<bytes>\d+),SPEED=(?<speed>[0-9.]+),TIME=(?<time>[0-9.]+)"
    )

    $httpCode = if ($match.Success) { [int]$match.Groups["code"].Value } else { 0 }
    $bytes = if ($match.Success) { [long]$match.Groups["bytes"].Value } else { 0 }
    $speed = if ($match.Success) { [double]$match.Groups["speed"].Value } else { 0.0 }
    $seconds = if ($match.Success) { [double]$match.Groups["time"].Value } else { 0.0 }
    $successful = $exitCode -eq 0 -and $httpCode -eq 200 -and $bytes -eq $BytesPerRound
    if (-not $successful) {
        $allSuccessful = $false
    }

    $record = [ordered]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("o")
        serial = $Serial
        round = $round
        requested_bytes = $BytesPerRound
        received_bytes = $bytes
        http_code = $httpCode
        speed_bytes_per_second = $speed
        elapsed_seconds = $seconds
        adb_exit_code = $exitCode
        successful = $successful
        error = if ($successful) { "" } else { $text }
    }
    ($record | ConvertTo-Json -Compress) | Add-Content -LiteralPath $OutputPath -Encoding UTF8
}

if (-not $allSuccessful) {
    throw "One or more device load-test rounds failed; inspect $OutputPath"
}
