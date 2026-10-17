# Records every HTTP request between the processes, in mitmweb's browser UI.
#
# mitmweb takes over the ports everyone already uses and passes each request on to the real app, which runs on a
# back-door port instead (1 in front):
#
#   :8080 -> petstore-api   :18080      (gateway -> API)
#   :9000 -> petstore-auth  :19000      (browser, mcp-remote and gateway -> authorization server)
#   :8081 -> petstore-mcp   :18081      (mcp-remote -> gateway)
#
# Nothing else changes: the issuer, the gateway's URLs and the Claude Desktop config still say 8080/9000/8081, so
# every call goes through the recorder. keep_host_header stops mitmweb rewriting Host to the back-door port; the
# gateway builds its resource_metadata URL from Host, and petstore-auth its /login redirect, so without it clients
# would be sent around the recorder.
#
# Not visible: the browser's final GET to mcp-remote's own listener (127.0.0.1:3334/oauth/callback). The 302 that
# sends it there is recorded.
#
# Each flow's comment says which process sent it (labels.py), for example "mcp-remote [claude-ai] pid 4242".
#
# The recording contains passwords, codes and tokens. It is saved under build/trace/, which git ignores.
#
# Usage, from the repo root:  .\scripts\trace\trace.ps1     (Ctrl+C to stop; then restart the apps normally)

$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
$mitmweb = Join-Path $env:APPDATA 'Python\Python313\Scripts\mitmweb.exe'
if (-not (Test-Path $mitmweb)) { $mitmweb = 'mitmweb' }

$busy = 8080, 9000, 8081 | Where-Object { Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue }
if ($busy) {
    Write-Host "Ports in use: $($busy -join ', '). Stop the apps first (Ctrl+C in their windows)." -ForegroundColor Red
    exit 1
}

$out = Join-Path $repo 'build\trace'
New-Item -ItemType Directory -Force $out | Out-Null
$flows = Join-Path $out ("flows-{0:yyyyMMdd-HHmmss}.mitm" -f (Get-Date))

Write-Host @"
Start the apps on their back-door ports, each in its own window, from $repo :

  java -Xmx256m -jar petstore-api\build\libs\petstore-api-0.0.1-SNAPSHOT.jar --server.port=18080
  java -Xmx256m -jar petstore-auth\build\libs\petstore-auth-0.0.1-SNAPSHOT.jar --server.port=19000
  java -Xmx256m -jar petstore-mcp\build\libs\petstore-mcp-0.0.1-SNAPSHOT.jar --server.port=18081

Then quit and reopen Claude Desktop. The mitmweb UI opens in your browser (http://127.0.0.1:8085).
Recording to $flows
"@ -ForegroundColor Cyan

& $mitmweb `
    --mode 'reverse:http://127.0.0.1:18080@localhost:8080' `
    --mode 'reverse:http://127.0.0.1:19000@localhost:9000' `
    --mode 'reverse:http://127.0.0.1:18081@localhost:8081' `
    --set keep_host_header=true `
    --set web_port=8085 `
    --set web_columns=index --set web_columns=timestamp --set web_columns=method --set web_columns=path `
    --set web_columns=status --set web_columns=time --set web_columns=comment `
    -s (Join-Path $PSScriptRoot 'sse_passthrough.py') `
    -s (Join-Path $PSScriptRoot 'labels.py') `
    -w $flows
