# 本文件带 UTF-8 BOM：Windows PowerShell 5.1 读无 BOM 的 UTF-8 会按 ANSI(GBK) 解析，
# 中文注释会乱码甚至语法报错。PowerShell 7 有无 BOM 都能读，所以保留 BOM。
# 改这个文件时请用「UTF-8 with BOM」保存。

<#
.SYNOPSIS
    构建并发布 SPW 遥控插件：构建 -> 提交源码 -> 推送 -> 建 GitHub Release。

.DESCRIPTION
    一条命令走完整个发布流程，每一步都先做安全检查：

      1. 解析 build.gradle.kts 的 PluginId / PluginVersion（版本号唯一来源）
      2. 检查工作区是否干净（有未提交改动需加 -AllowDirty）
      3. 跑 build.ps1 出 .spmod 与 .zip
      4. 确认 tag v<version> 本地和远端都不存在（避免覆盖已发布版本）
      5. 提交、打 tag、推送分支与 tag
      6. 用 GitHub API 建 Release，上传 .spmod / .zip / SHA256SUMS.txt

    令牌只从环境变量 GITHUB_TOKEN 读取，不进命令行参数、不进 remote URL、
    不留在 .git/config。

.PARAMETER Remote
    git remote 名称，默认 origin。

.PARAMETER Repository
    目标仓库 owner/name，默认从 remote URL 推断。

.PARAMETER AllowDirty
    允许工作区有未提交改动（仍会把改动一起提交）。

.PARAMETER SkipPush
    只构建并提交，不推送、不建 Release（干跑用）。

.PARAMETER SkipBuild
    复用 build/libs 里已有产物，不重新构建。

.EXAMPLE
    $env:GITHUB_TOKEN = 'ghp_xxx'
    .\publish.ps1

.EXAMPLE
    $env:GITHUB_TOKEN = 'ghp_xxx'
    .\publish.ps1 -SkipPush
#>

[CmdletBinding()]
param(
    [string]$Remote = 'origin',
    [string]$Repository = '',
    [switch]$AllowDirty,
    [switch]$SkipPush,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$Here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

# 生成给 Windows 用的文本文件时，5.1 需要 BOM 才能被记事本/浏览器识别为 UTF-8。
$Utf8Bom = New-Object System.Text.UTF8Encoding($true)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Push-Location $Here
try {
    # ---------------------------------------------------------------- helpers
    function Fail([string]$Message) {
        Write-Host "ERROR: $Message" -ForegroundColor Red
        exit 1
    }
    function Info([string]$Message) { Write-Host $Message -ForegroundColor Cyan }
    function Ok([string]$Message) { Write-Host $Message -ForegroundColor Green }
    function Warn([string]$Message) { Write-Host $Message -ForegroundColor Yellow }
    function Dim([string]$Message) { Write-Host $Message -ForegroundColor DarkGray }

    function Invoke-Git {
        param([string[]]$GitArgs, [switch]$AllowFail)
        $out = & git @GitArgs 2>&1
        $code = $LASTEXITCODE
        if ($code -ne 0 -and -not $AllowFail) {
            Dim ($out | Out-String)
            Fail ("git " + ($GitArgs -join ' ') + " 失败（exit $code）")
        }
        [PSCustomObject]@{ Code = $code; Output = (($out | Out-String).Trim()) }
    }

    # ------------------------------------------------- 1. read plugin version
    Info '== 1/6 读取插件版本 =='
    $buildFile = Join-Path $Here 'build.gradle.kts'
    if (-not (Test-Path $buildFile)) { Fail '找不到 build.gradle.kts' }
    $buildText = Get-Content -LiteralPath $buildFile -Raw

    $mId = [regex]::Match($buildText, 'PluginId\s*=\s*"([^"]+)"')
    $mVer = [regex]::Match($buildText, 'PluginVersion\s*=\s*"([^"]+)"')
    $mCls = [regex]::Match($buildText, 'PluginClass\s*=\s*"([^"]+)"')
    if (-not $mId.Success) { Fail 'build.gradle.kts 里找不到 spmod 的 PluginId' }
    if (-not $mVer.Success) { Fail 'build.gradle.kts 里找不到 spmod 的 PluginVersion' }

    $pluginId = $mId.Groups[1].Value
    $version = $mVer.Groups[1].Value
    $pluginClass = if ($mCls.Success) { $mCls.Groups[1].Value } else { '(unknown)' }
    $tag = "v$version"
    $spmodName = "plugin-$pluginId-$version.spmod"
    $zipName = "spw-remote-$version.zip"

    Write-Host "  PluginId      : $pluginId"
    Write-Host "  PluginVersion : $version"
    Write-Host "  PluginClass   : $pluginClass"
    Write-Host "  tag           : $tag"
    Write-Host "  spmod         : $spmodName"

    # --------------------------------------------------- 2. repository state
    Info '== 2/6 检查仓库状态 =='
    if (-not (Test-Path (Join-Path $Here '.git'))) { Fail '当前目录不是 git 仓库（缺 .git）' }

    $branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Output
    if ($branch -eq 'HEAD') { Fail '处于 detached HEAD，先切回分支' }
    Write-Host "  branch        : $branch"

    $dirty = (Invoke-Git @('status', '--porcelain')).Output
    if ($dirty) {
        if (-not $AllowDirty) {
            Dim $dirty
            Fail '工作区有未提交改动。确认无误后加 -AllowDirty 重跑。'
        }
        Warn '  工作区有未提交改动，将一并提交：'
        Dim $dirty
    }

    if (-not $Repository) {
        $url = (Invoke-Git @('remote', 'get-url', $Remote)).Output
        $mRepo = [regex]::Match($url, 'github\.com[/:]([^/]+/[^/]+?)(\.git)?$')
        if (-not $mRepo.Success) { Fail "无法从 remote $Remote 的 URL 推断仓库，请显式传 -Repository owner/name" }
        $Repository = $mRepo.Groups[1].Value
    }
    Write-Host "  repository    : $Repository"

    $localTag = Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/tags/$tag") -AllowFail
    if ($localTag.Code -eq 0) { Fail "本地已存在 tag $tag。改 build.gradle.kts 的 PluginVersion 再发布。" }
    $remoteTag = Invoke-Git @('ls-remote', '--tags', $Remote, "refs/tags/$tag") -AllowFail
    if ($remoteTag.Code -eq 0 -and $remoteTag.Output) { Fail "远端 $Remote 已存在 tag $tag，不能重复发布。" }

    # -------------------------------------------------------------- 3. build
    Info '== 3/6 构建 =='
    $libsDir = Join-Path $Here 'build\libs'
    if ($SkipBuild) {
        Warn '  -SkipBuild：复用 build\libs 现有产物'
    } else {
        & (Join-Path $Here 'build.ps1')
        if ($LASTEXITCODE -ne 0) { Fail "build.ps1 失败（exit $LASTEXITCODE）" }
    }

    $spmodPath = Join-Path $libsDir $spmodName
    $zipPath = Join-Path $libsDir $zipName
    foreach ($p in @($spmodPath, $zipPath)) {
        if (-not (Test-Path $p)) {
            Fail "产物缺失：$p （检查 build.gradle.kts 的 PluginId/PluginVersion 是否与产物名一致）"
        }
    }

    $hashSpmod = (Get-FileHash -LiteralPath $spmodPath -Algorithm SHA256).Hash.ToLower()
    $hashZip = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLower()
    if ($hashSpmod -ne $hashZip) { Fail '.spmod 与 .zip 的 SHA-256 不一致，产物有问题' }
    Write-Host "  sha256        : $hashSpmod"
    Write-Host "  size          : $([math]::Round((Get-Item $spmodPath).Length / 1KB, 1)) KB"

    # 一致性检查：包内 MANIFEST 必须与 build.gradle.kts 声明一致
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $pkg = [System.IO.Compression.ZipFile]::OpenRead($spmodPath)
    try {
        $entry = $pkg.Entries | Where-Object { $_.FullName -eq 'classes/META-INF/MANIFEST.MF' }
        if (-not $entry) { Fail '包内找不到 classes/META-INF/MANIFEST.MF' }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        $manifest = $reader.ReadToEnd(); $reader.Close()
    }
    finally { $pkg.Dispose() }

    if ($manifest -notmatch [regex]::Escape("Plugin-Id: $pluginId")) { Fail '包内 MANIFEST 的 Plugin-Id 与 build.gradle.kts 不一致' }
    if ($manifest -notmatch [regex]::Escape("Plugin-Version: $version")) { Fail '包内 MANIFEST 的 Plugin-Version 与 build.gradle.kts 不一致' }
    if ($pluginClass -ne '(unknown)' -and $manifest -notmatch [regex]::Escape("Plugin-Class: $pluginClass")) { Fail '包内 MANIFEST 的 Plugin-Class 与 build.gradle.kts 不一致' }
    Ok '  MANIFEST 校验通过（Plugin-Id / Plugin-Version / Plugin-Class 一致）'
    Info '  包内 MANIFEST：'
    ($manifest -split "`r?`n") | Where-Object { $_ } | ForEach-Object { Dim "    $_" }

    # ------------------------------------------------------- 4. commit and tag
    Info '== 4/6 提交并打 tag =='
    Invoke-Git @('add', '-A') | Out-Null
    $staged = (Invoke-Git @('diff', '--cached', '--name-only')).Output
    if ($staged) {
        Dim '  将提交：'
        ($staged -split "`r?`n") | Where-Object { $_ } | ForEach-Object { Dim "    $_" }
        Invoke-Git @('commit', '-m', "发布 $version") | Out-Null
        $head = (Invoke-Git @('rev-parse', '--short', 'HEAD')).Output
        Ok "  commit $head"
    } else {
        Dim '  没有需要提交的改动'
    }

    $newTag = Invoke-Git @('tag', '-a', $tag, '-m', "Release $version") -AllowFail
    if ($newTag.Code -ne 0) { Fail "打 tag $tag 失败：$($newTag.Output)" }
    Ok "  tag $tag"

    if ($SkipPush) {
        Warn '  -SkipPush：到此为止，没有推送。手动推送命令：'
        Dim "    git push $Remote $branch"
        Dim "    git push $Remote $tag"
        exit 0
    }

    # ---------------------------------------------------------------- 5. push
    Info '== 5/6 推送 =='
    $token = $env:GITHUB_TOKEN
    if (-not $token) { Fail '未设置环境变量 GITHUB_TOKEN（推送与建 Release 都需要）' }

    # 一次性 Authorization 头：令牌不进 remote URL，也不写进 .git/config
    $basic = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("x-access-token:$token"))
    $authHeader = "AUTHORIZATION: basic $basic"
    $pushUrl = "https://github.com/$Repository.git"
    $gitAuth = @('-c', "http.https://github.com/.extraheader=$authHeader")

    & git @gitAuth push $pushUrl "refs/heads/${branch}:refs/heads/${branch}" 2>&1 | Out-String | ForEach-Object { Dim $_ }
    if ($LASTEXITCODE -ne 0) { Fail '推送分支失败' }
    Ok "  分支 $branch 已推送"

    & git @gitAuth push $pushUrl "refs/tags/${tag}:refs/tags/${tag}" 2>&1 | Out-String | ForEach-Object { Dim $_ }
    if ($LASTEXITCODE -ne 0) { Fail '推送 tag 失败' }
    Ok "  tag $tag 已推送"

    # ------------------------------------------------------------- 6. release
    Info '== 6/6 创建 GitHub Release =='
    $headers = @{
        Authorization = "token $token"
        'User-Agent'  = 'spw-remote-plugin-publish'
        Accept        = 'application/vnd.github+json'
    }

    # SHA256SUMS.txt 随 Release 一起发，方便用户核对下载到的包
    $sumsFile = Join-Path $libsDir 'SHA256SUMS.txt'
    $sumsText = "$hashSpmod  $spmodName`n$hashZip  $zipName`n"
    [System.IO.File]::WriteAllText($sumsFile, $sumsText, $Utf8Bom)

    # 说明正文用单引号 here-string，里面的 ` 原样保留（markdown 代码块要连续三个反引号）
    $notesBody = @'

## 安装

1. 下载 `plugin-io.github.motian16.spwremote-__VERSION__.spmod`（或内容完全相同的
   `spw-remote-__VERSION__.zip`）
2. Salt Player → 创意工坊 → 本地导入 → 选择该文件
3. **在创意工坊里启用它**（导入 ≠ 启用）。启用后 toast 会显示配对码和可连地址

## 校验

```text
__SUMS__
```

## 说明

- 插件 ID `__PLUGINID__`，版本 `__VERSION__`
- `.spmod` 就是标准 zip，两个附件内容完全相同，可按 SHA-256 互相验证
- 功能、协议、测试方法见仓库 README

'@
    $notes = $notesBody.Replace('__VERSION__', $version).
        Replace('__PLUGINID__', $pluginId).
        Replace('__SUMS__', $sumsText.TrimEnd())

    $payload = @{
        tag_name               = $tag
        name                   = "v$version"
        body                   = $notes
        draft                  = $false
        prerelease             = $false
        generate_release_notes = $false
    } | ConvertTo-Json -Compress

    # 正文含中文，显式写成 UTF-8 文件再交给 -InFile，避免 5.1 按 ANSI 发送
    $payloadFile = Join-Path $env:TEMP "spw-release-$version.json"
    [System.IO.File]::WriteAllText($payloadFile, $payload, $Utf8NoBom)

    try {
        $release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repository/releases" `
            -Headers $headers -ContentType 'application/json; charset=utf-8' `
            -InFile $payloadFile -TimeoutSec 120
    }
    finally { Remove-Item $payloadFile -Force -ErrorAction SilentlyContinue }
    Ok "  Release 已创建：$($release.html_url)"

    $assets = @(
        @{ Path = $spmodPath; Name = $spmodName; Type = 'application/zip' },
        @{ Path = $zipPath; Name = $zipName; Type = 'application/zip' },
        @{ Path = $sumsFile; Name = 'SHA256SUMS.txt'; Type = 'text/plain' }
    )
    foreach ($asset in $assets) {
        $uploadUrl = ($release.upload_url -replace '\{\?name,label\}', '') + '?name=' + [uri]::EscapeDataString($asset.Name)
        $res = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers `
            -ContentType $asset.Type -InFile $asset.Path -TimeoutSec 600
        Ok "  上传 $($asset.Name)"
        Dim "    $($res.browser_download_url)"
    }

    Write-Host ''
    Ok "发布完成：$($release.html_url)"
    Warn '  记得确认仓库 topic 里有 salt-player-plugins'
}
finally {
    Pop-Location
}
