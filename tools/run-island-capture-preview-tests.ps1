# SPDX-License-Identifier: GPL-3.0-only
# ImKani / FocusRestore / https://github.com/ImKani/FocusRestore
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-island-rule-f1-b-tests.ps1') -Preview
exit $LASTEXITCODE
