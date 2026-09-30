<#
.SYNOPSIS
  Installs the AIFI Pull Agent as a Windows service and locks down its folders.
.DESCRIPTION
  - config\ (secrets, pull-agent.key), spool\ (studies in transit) and logs\ get an ACL
    without inheritance: only Administrators, SYSTEM and the service account.
  - WinSW reads <serviceaccount> from aifi-pull-agent-service.xml at install time only. The block
    is injected, the service installed, and the original XML restored in a finally, so the
    password never stays on disk. The password is asked for here (or taken from
    AIFIPULL_SVC_PASSWORD for unattended installs), never passed on a command line.
  Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts in the ANSI code page.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$InstallDir,
    [Parameter(Mandatory=$true)][string]$ServiceUser
)
$ErrorActionPreference = 'Stop'
$InstallDir = (Resolve-Path -LiteralPath $InstallDir).Path
$xmlPath = Join-Path $InstallDir 'aifi-pull-agent-service.xml'
$exePath = Join-Path $InstallDir 'aifi-pull-agent-service.exe'
if (-not (Test-Path -LiteralPath $xmlPath)) { throw "Missing $xmlPath" }
if (-not (Test-Path -LiteralPath $exePath)) { throw "Missing $exePath" }

Write-Host "Restricting access to config, spool and logs..."
foreach ($sub in @('config', 'spool', 'logs')) {
    $dir = Join-Path $InstallDir $sub
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    # *S-1-5-32-544 = BUILTIN\Administrators, *S-1-5-18 = SYSTEM (language-independent SIDs)
    $grants = @('*S-1-5-32-544:(OI)(CI)F', '*S-1-5-18:(OI)(CI)F')
    if ($ServiceUser -ne 'LocalSystem') { $grants += "${ServiceUser}:(OI)(CI)M" }
    & icacls $dir /inheritance:r /grant:r $grants /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls failed on $dir (exit $LASTEXITCODE)." }
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$original  = [System.IO.File]::ReadAllText($xmlPath, $utf8NoBom)
try {
    if ($ServiceUser -ne 'LocalSystem') {
        $password = $env:AIFIPULL_SVC_PASSWORD
        if (-not $password) {
            $secure = Read-Host "Password for $ServiceUser" -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        }
        if (-not $password) { throw "No password given for $ServiceUser." }
        $doc = New-Object System.Xml.XmlDocument
        $doc.LoadXml($original)
        $svc = $doc.SelectSingleNode('/service')
        foreach ($n in @($svc.SelectNodes('serviceaccount'))) { [void]$svc.RemoveChild($n) }
        $acct = $doc.CreateElement('serviceaccount')
        $u = $doc.CreateElement('user');              $u.InnerText = $ServiceUser; [void]$acct.AppendChild($u)
        $p = $doc.CreateElement('password');          $p.InnerText = $password;    [void]$acct.AppendChild($p)
        $a = $doc.CreateElement('allowservicelogon'); $a.InnerText = 'true';       [void]$acct.AppendChild($a)
        [void]$svc.AppendChild($acct)
        $writer = New-Object System.IO.StreamWriter($xmlPath, $false, $utf8NoBom)
        try { $doc.Save($writer) } finally { $writer.Close() }
    }
    Write-Host "Installing service..."
    & $exePath install
    if ($LASTEXITCODE -ne 0) { throw "WinSW install failed (exit $LASTEXITCODE)." }
}
finally {
    [System.IO.File]::WriteAllText($xmlPath, $original, $utf8NoBom)
    Remove-Item -ErrorAction SilentlyContinue Env:\AIFIPULL_SVC_PASSWORD
}
Write-Host "Starting service..."
& $exePath start
if ($LASTEXITCODE -ne 0) { throw "Service start failed (exit $LASTEXITCODE). See $InstallDir\logs\." }
Write-Host "Service running. Log: $InstallDir\logs\pull-agent.0.log"
