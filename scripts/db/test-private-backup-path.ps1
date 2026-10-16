[CmdletBinding()]
param([switch]$CheckActiveUmask)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restored-warranty-rows.ps1')

if ($CheckActiveUmask) {
    Assert-PrivateUnixUmask
    Write-Host '[OK] Active process umask is 0077.'
    return
}

function Assert-Rejected {
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $rejected = $false
    try { & $Action }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Unsafe backup path or mode was accepted.' }
}

if (-not (Test-PrivateUnixMetadata -OwnerUid '1000' -CurrentUid '1000' -OctalMode '0700' -IsDirectory $true) -or
    -not (Test-PrivateUnixMetadata -OwnerUid '1000' -CurrentUid '1000' -OctalMode '0600' -IsDirectory $false) -or
    -not (Test-PrivateUnixUmaskValue -OctalUmask '0077') -or
    -not (Test-SafeUnixAncestorMetadata -OwnerUid '0' -CurrentUid '1000' -ChildOwnerUid '1000' -OctalMode '1777') -or
    -not (Test-SafeUnixAncestorMetadata -OwnerUid '0' -CurrentUid '1000' -ChildOwnerUid '1000' -OctalMode '0755')) {
    throw 'Private Unix metadata was rejected.'
}
foreach ($case in @(
    @{ owner = '1001'; current = '1000'; mode = '0700'; directory = $true },
    @{ owner = '1000'; current = '1000'; mode = '0755'; directory = $true },
    @{ owner = '1000'; current = '1000'; mode = '0770'; directory = $true },
    @{ owner = '1000'; current = '1000'; mode = '0644'; directory = $false },
    @{ owner = '1000'; current = '1000'; mode = '0700'; directory = $false },
    @{ owner = '1000'; current = '1000'; mode = '4600'; directory = $false },
    @{ owner = '1000'; current = '1000'; mode = 'garbage'; directory = $true }
)) {
    if (Test-PrivateUnixMetadata -OwnerUid $case.owner -CurrentUid $case.current -OctalMode $case.mode -IsDirectory $case.directory) {
        throw "Unsafe Unix metadata was accepted: $($case.mode)."
    }
}
foreach ($mask in @('0022', '0000', '0072', 'invalid')) {
    if (Test-PrivateUnixUmaskValue -OctalUmask $mask) { throw "Unsafe Unix umask was accepted: $mask." }
}
foreach ($case in @(
    @{ owner = '1001'; child = '1000'; mode = '0750' },
    @{ owner = '0'; child = '1000'; mode = '0777' },
    @{ owner = '0'; child = '1001'; mode = '1777' },
    @{ owner = '1001'; child = '1000'; mode = '1777' }
)) {
    if (Test-SafeUnixAncestorMetadata -OwnerUid $case.owner -CurrentUid '1000' -ChildOwnerUid $case.child -OctalMode $case.mode) {
        throw "Unsafe Unix ancestor was accepted: owner=$($case.owner), mode=$($case.mode)."
    }
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    $nonce = [Guid]::NewGuid().ToString('N')
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-private-path-test-' + $nonce)
    try {
        New-Item -ItemType Directory -Path $testRoot | Out-Null
        & chmod 700 -- $testRoot
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare private Unix test directory.' }
        Assert-PrivateWarrantyDirectory -Path $testRoot

        $file = Join-Path $testRoot 'synthetic.txt'
        [IO.File]::WriteAllText($file, 'synthetic-only')
        & chmod 600 -- $file
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare private Unix test file.' }
        Assert-PrivateWarrantyDirectory -Path $file

        & chmod 644 -- $file
        if ($LASTEXITCODE -ne 0) { throw 'Could not change synthetic Unix file mode.' }
        Assert-Rejected { Assert-PrivateWarrantyDirectory -Path $file }
        & chmod 600 -- $file
        & chmod 755 -- $testRoot
        if ($LASTEXITCODE -ne 0) { throw 'Could not change synthetic Unix directory mode.' }
        Assert-Rejected { Assert-PrivateWarrantyDirectory -Path $testRoot }
        & chmod 700 -- $testRoot

        $link = Join-Path $testRoot 'linked.txt'
        New-Item -ItemType SymbolicLink -Path $link -Target $file | Out-Null
        Assert-Rejected { Assert-PrivateWarrantyDirectory -Path $link }
        $linkedDirectory = Join-Path $testRoot 'linked-directory'
        New-Item -ItemType SymbolicLink -Path $linkedDirectory -Target $testRoot | Out-Null
        Assert-Rejected { Assert-NoReparseAncestor -Path (Join-Path $linkedDirectory 'synthetic.txt') }
        $brokenLink = Join-Path $testRoot 'broken.txt'
        New-Item -ItemType SymbolicLink -Path $brokenLink -Target (Join-Path $testRoot 'missing.txt') | Out-Null
        Assert-Rejected { Assert-PrivateWarrantyDirectory -Path $brokenLink }
        $shared = Join-Path $testRoot 'shared'
        $privateChild = Join-Path $shared 'private'
        New-Item -ItemType Directory -Path $privateChild -Force | Out-Null
        & chmod 777 -- $shared
        & chmod 700 -- $privateChild
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare shared-writable ancestor test.' }
        Assert-Rejected { Assert-NoReparseAncestor -Path $privateChild }
        & chmod 1777 -- $shared
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare sticky ancestor test.' }
        Assert-NoReparseAncestor -Path $privateChild
        Write-Host '[OK] Unix private owner/mode/link checks passed with synthetic files.'
    }
    finally {
        $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('/') + '/'
        $resolved = [IO.Path]::GetFullPath($testRoot)
        if ($resolved.StartsWith($prefix, [StringComparison]::Ordinal) -and
            (Split-Path -Leaf $resolved) -ceq ('buildflow-private-path-test-' + $nonce)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
else { Write-Host '[OK] Cross-platform privacy policy tests passed; Unix filesystem checks run in Linux CI.' }
