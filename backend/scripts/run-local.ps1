# Run Viewshed backend without Docker (Windows)
# Usage:  .\scripts\run-local.ps1
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Test-Path .venv\Scripts\python.exe)) {
  py -3.12 -m venv .venv
  .\.venv\Scripts\python.exe -m pip install -U pip
  # Core API deps (skip rasterio/GDAL on Windows unless you have GDAL installed)
  .\.venv\Scripts\pip.exe install fastapi "uvicorn[standard]" python-multipart pydantic numpy shapely geojson
}

New-Item -ItemType Directory -Force -Path data, uploads | Out-Null
Write-Host "Starting http://127.0.0.1:8000  (docs: /docs)"
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
