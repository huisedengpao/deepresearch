param(
    [Parameter(Mandatory = $true)][string]$Question,
    [Parameter(Mandatory = $true)][string]$ThreadId,
    [Parameter(Mandatory = $true)][string]$OutPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Fresh workspace per run: the agent writes fixed filenames, so clear last run's artifacts first.
$workspace = 'D:\workspace'
New-Item -ItemType Directory -Path $workspace -Force | Out-Null
Remove-Item (Join-Path $workspace 'question.txt'), (Join-Path $workspace 'final_report.md'), (Join-Path $workspace 'critique.md') -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $workspace -Filter 'findings-*.md' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.Net.Http
$base = 'http://127.0.0.1:8080'
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(30)

function Post-Sse {
    param([string]$Path, [hashtable]$Body)
    $json = $Body | ConvertTo-Json -Depth 10 -Compress
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base$Path")
    $req.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    $sb = [System.Text.StringBuilder]::new()
    $confirms = @()
    $dropped = $false
    $resp = $null; $stream = $null; $reader = $null
    try {
        $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $stream = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if (-not $line.StartsWith('data:')) { continue }
            $payload = $line.Substring(5).Trim()
            if (-not $payload -or $payload -eq '{}') { continue }
            try { $j = $payload | ConvertFrom-Json } catch { continue }
            if ($null -eq $j.message) { continue }
            $m = $j.message
            if ($m.messageType -eq 'assistant' -and $j.chunk) { [void]$sb.Append($j.chunk) }
            if ($m.messageType -eq 'tool-confirm' -and $m.toolFeedback) { $confirms = @($m.toolFeedback) }
        }
    }
    catch {
        # /run_sse and /resume_sse are segmented: the server closes the stream
        # whenever this leg of the graph finishes. Treat that as a soft stop and
        # keep whatever arrived, instead of aborting the whole script.
        $dropped = $true
        Write-Host ("stream dropped on {0}: {1}" -f $Path, $_.Exception.Message)
    }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($stream) { $stream.Dispose() }
        if ($resp) { $resp.Dispose() }
    }
    return @{ Text = $sb.ToString(); Confirms = $confirms; Dropped = $dropped }
}

$all = [System.Text.StringBuilder]::new()
$run = Post-Sse -Path '/run_sse' -Body @{
    appName = 'research_agent'; threadId = $ThreadId; userId = 'web'
    newMessage = @{ messageType = 'user'; content = $Question }
}
[void]$all.Append($run.Text)
$written = @()
$reportPath = Join-Path $workspace 'final_report.md'
# The SSE legs are segmented, so "no pending approval" does not mean the run is
# over. Keep re-attaching until the report lands on disk (or we time out).
$deadline = (Get-Date).AddMinutes(25)
$idle = 0
$iter = 0
while ((Get-Date) -lt $deadline -and $iter -lt 60) {
    $iter++
    if (Test-Path $reportPath) { Write-Host 'final_report.md detected'; break }

    $fb = @()
    if (@($run.Confirms).Count -gt 0) {
        foreach ($f in $run.Confirms) {
            if ($f.arguments) {
                try { $a = $f.arguments | ConvertFrom-Json; if ($a.file_path) { $written += [string]$a.file_path } } catch {}
            }
            $fb += @{ id = $f.id; name = $f.name; arguments = $f.arguments; result = 'APPROVED' }
        }
        Write-Host ("approve {0} tool(s)" -f $fb.Count)
    }
    else {
        Start-Sleep -Seconds 10
    }

    $lengthBefore = $all.Length
    $run = Post-Sse -Path '/resume_sse' -Body @{
        appName = 'research_agent'; threadId = $ThreadId; userId = 'web'; toolFeedbacks = $fb
    }
    [void]$all.Append($run.Text)

    if ($all.Length -eq $lengthBefore -and @($run.Confirms).Count -eq 0) {
        $idle++
        Write-Host ("no progress on resume ({0}/6)" -f $idle)
        if ($idle -ge 6) { break }
    }
    else { $idle = 0 }
}

$report = $all.ToString()
# HITL is disabled on the server, so $written stays empty; the authoritative
# report is the file the writer agent put on disk.
if (Test-Path $reportPath) {
    $disk = Get-Content -Raw -Path $reportPath -Encoding UTF8
    if ($disk -and $disk.Length -gt $report.Length) { $report = $disk }
}
foreach ($p in $written) {
    $rp = $p
    if (-not [IO.Path]::IsPathRooted($rp)) { $rp = Join-Path (Get-Location) $rp }
    if (Test-Path $rp) {
        $c = Get-Content -Raw -Path $rp -Encoding UTF8
        if ($c -and $c.Length -gt $report.Length) { $report = $c }
    }
}
[IO.File]::WriteAllText($OutPath, $report, [Text.Encoding]::UTF8)
Write-Host ("REPORT_LEN={0}" -f $report.Length)
Write-Host ("WRITTEN_FILES={0}" -f ($written -join ','))
