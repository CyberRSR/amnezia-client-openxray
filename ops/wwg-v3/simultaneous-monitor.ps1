param(
    [int]$DurationMinutes = 35,
    [int]$IntervalSeconds = 30,
    [string]$OutputPath = (Join-Path (Get-Location) "simultaneous-monitor.jsonl"),
    [string]$AdbPath = "adb",
    [Parameter(Mandatory = $true)]
    [string]$PhoneSerial,
    [string]$PhoneName = "phone",
    [Parameter(Mandatory = $true)]
    [string]$EmulatorSerial,
    [string]$EmulatorName = "emulator"
)

$ErrorActionPreference = "Continue"
$devices = @(
    @{ name = $PhoneName; serial = $PhoneSerial },
    @{ name = $EmulatorName; serial = $EmulatorSerial }
)
$deadline = (Get-Date).ToUniversalTime().AddMinutes($DurationMinutes)
$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}

function Invoke-AdbText([string]$serial, [string[]]$arguments) {
    $result = & $AdbPath "-s" $serial @arguments 2>$null
    return (($result | Out-String).Trim())
}

function Get-DeviceSample($device) {
    $address = Invoke-AdbText $device.serial @("shell", "ip", "-brief", "address", "show", "tun0")
    $ping = Invoke-AdbText $device.serial @("shell", "ping", "-c", "1", "-W", "2", "1.1.1.1")
    $service = Invoke-AdbText $device.serial @(
        "shell", "pidof", "org.amnezia.vpn.debugx:amneziaWwgService"
    )
    $events = Invoke-AdbText $device.serial @(
        "logcat", "-d", "-s", "Wwg:I", "*:S"
    ) | Select-String -Pattern "WWG_EVENT" | Select-Object -Last 30
    $eventText = (($events | ForEach-Object { $_.Line }) -join "`n")
    [ordered]@{
        device = $device.name
        serial = $device.serial
        tun = $address
        ping_ok = [bool]($ping -match "0% packet loss")
        service_pid = $service
        events = $eventText
    }
}

while ((Get-Date).ToUniversalTime() -lt $deadline) {
    $record = [ordered]@{
        timestamp_utc = (Get-Date).ToUniversalTime().ToString("o")
        devices = @($devices | ForEach-Object { Get-DeviceSample $_ })
    }
    ($record | ConvertTo-Json -Compress -Depth 6) | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds ([Math]::Max(5, [Math]::Min(60, $IntervalSeconds)))
}
