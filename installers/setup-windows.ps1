$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$NodeVersion = 'v22.23.0'
$NodePlat    = 'win-x64'   # the x64 build also runs fine on Windows-on-ARM via emulation
$RepoZip     = 'https://github.com/mrcobbert/bedwarsQOL/archive/refs/heads/main.zip'
$Work        = Join-Path $env:USERPROFILE '.bedwarsqol-setup'

function Say($m) { Write-Host "`n$m" -ForegroundColor Cyan }
function Ok($m)  { Write-Host $m -ForegroundColor Green }
function Die($m) {
  Write-Host "`nSetup failed: $m`n" -ForegroundColor Red
  Write-Host 'Ask in the Discord for help and copy the red text above.'
  exit 1
}

New-Item -ItemType Directory -Force -Path $Work | Out-Null
Set-Location $Work

# 1. Private Node.js
$NodeDir = Join-Path $Work "node-$NodeVersion-$NodePlat"
if (-not (Test-Path (Join-Path $NodeDir 'node.exe'))) {
  Say 'Downloading a private copy of Node.js (one-time, ~30 MB)...'
  $zip = Join-Path $Work 'node.zip'
  try { Invoke-WebRequest -UseBasicParsing "https://nodejs.org/dist/$NodeVersion/node-$NodeVersion-$NodePlat.zip" -OutFile $zip }
  catch { Die 'could not download Node.js (check your internet connection)' }
  Expand-Archive -Path $zip -DestinationPath $Work -Force
  Remove-Item $zip
}
$env:Path = "$NodeDir;" + $env:Path
$npm = Join-Path $NodeDir 'npm.cmd'
$npx = Join-Path $NodeDir 'npx.cmd'
Ok "Node.js ready ($(& (Join-Path $NodeDir 'node.exe') -v))."

# 2. Download the Worker source
Say 'Downloading the BedwarsQOL stats backend...'
$srcZip = Join-Path $Work 'source.zip'
try { Invoke-WebRequest -UseBasicParsing $RepoZip -OutFile $srcZip } catch { Die 'could not download the mod source' }
$ext = Join-Path $Work 'src-extract'
if (Test-Path $ext) { Remove-Item $ext -Recurse -Force }
Expand-Archive -Path $srcZip -DestinationPath $ext -Force
Remove-Item $srcZip
$worker = Get-ChildItem -Path $ext -Recurse -Directory -Filter 'stats-worker' |
          Where-Object { $_.FullName -match 'server\\stats-worker$' } | Select-Object -First 1
if (-not $worker) { Die 'could not find the worker folder in the download' }
Set-Location $worker.FullName
Ok 'Source ready.'

# 3. Install dependencies
Say 'Installing (this can take a minute)...'
& $npm install --no-fund --no-audit
if ($LASTEXITCODE -ne 0) { Die 'npm install failed' }
Ok 'Installed.'

# 4. Log in to Cloudflare (opens a browser)
$who = (& $npx wrangler whoami 2>&1 | Out-String)
if ($who -match 'not authenticated|not logged|wrangler login') {
  Say 'A browser window will open - log in or sign up for Cloudflare, then click "Allow".'
  & $npx wrangler login
  if ($LASTEXITCODE -ne 0) { Die 'Cloudflare login was cancelled' }
}
Ok 'Logged in to Cloudflare.'

# 5. Deploy
Say 'Deploying your stats backend... (if asked to pick a workers.dev name, type anything)'
$deploy = (& $npx wrangler deploy 2>&1 | Out-String)
Write-Host $deploy
if ($LASTEXITCODE -ne 0) { Die 'deploy failed' }

$m = [regex]::Match($deploy, 'https://[a-zA-Z0-9._-]+\.workers\.dev')
if (-not $m.Success) { Die 'deployed, but could not read the URL from the output above' }
$cmd = "/bedwarsqol statsurl $($m.Value)"
try { Set-Clipboard -Value $cmd } catch {}

# Done
Write-Host ''
Write-Host '======================================================' -ForegroundColor Green
Write-Host '  DONE!  Your stats backend is live.'                   -ForegroundColor Green
Write-Host '======================================================' -ForegroundColor Green
Write-Host ''
Write-Host 'In Minecraft, paste this into chat (it is already on your clipboard):'
Write-Host ''
Write-Host "   $cmd" -ForegroundColor Yellow
Write-Host ''
Write-Host 'Then turn on Hypixel Stats in the mod settings (press Right Shift).'
Write-Host ''
