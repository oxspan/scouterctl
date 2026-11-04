param([Parameter(Mandatory = $true)][string]$Request)
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ScaleProcessMemory {
    [StructLayout(LayoutKind.Sequential)]
    public struct Counters {
        public uint Size, PageFaultCount;
        public UIntPtr PeakWorkingSetSize, WorkingSetSize, QuotaPeakPagedPoolUsage, QuotaPagedPoolUsage;
        public UIntPtr QuotaPeakNonPagedPoolUsage, QuotaNonPagedPoolUsage, PagefileUsage, PeakPagefileUsage;
    }
    [DllImport("psapi.dll", SetLastError = true)]
    public static extern bool GetProcessMemoryInfo(IntPtr handle, ref Counters counters, uint size);
}
'@
$inputData = Get-Content -Raw -LiteralPath $Request -Encoding UTF8 | ConvertFrom-Json
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $inputData.java
$startInfo.Arguments = ($inputData.arguments | ForEach-Object {
    '"' + ([regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1')) + '"'
}) -join ' '
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$startInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
$clock = [System.Diagnostics.Stopwatch]::StartNew()
try {
    if (-not $process.Start()) { throw 'CLI did not start' }
    $handle = $process.Handle
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'CLI exceeded 120 seconds' }
    $clock.Stop()
    $counters = New-Object ScaleProcessMemory+Counters
    $counters.Size = [System.Runtime.InteropServices.Marshal]::SizeOf($counters)
    if (-not [ScaleProcessMemory]::GetProcessMemoryInfo($handle, [ref]$counters, $counters.Size)) {
        throw "GetProcessMemoryInfo failed: $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($inputData.output, $stdout.GetAwaiter().GetResult(), $utf8)
    [System.IO.File]::WriteAllText($inputData.error, $stderr.GetAwaiter().GetResult(), $utf8)
    $result = @{ exit = $process.ExitCode; elapsedMs = $clock.ElapsedMilliseconds;
        peakBytes = $counters.PeakWorkingSetSize.ToUInt64(); memorySource = 'Win32 GetProcessMemoryInfo peak working set (bytes)' }
    [System.IO.File]::WriteAllText($inputData.metrics, ($result | ConvertTo-Json -Compress), $utf8)
} finally {
    $process.Dispose()
}
