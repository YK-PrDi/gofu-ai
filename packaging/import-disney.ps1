$BASE_URL = "http://localhost:5020"
$ROOT = "C:\Users\20739\Desktop\kaipin\" + (Get-ChildItem "C:\Users\20739\Desktop\kaipin" -Directory | Select-Object -First 1).Name

Write-Host "Importing Disney assets..." -ForegroundColor Cyan
$totalOk = 0
$totalFail = 0

Get-ChildItem -Path $ROOT -Directory | ForEach-Object {
    $tag = $_.Name
    $images = Get-ChildItem -Path $_.FullName -File | Where-Object { $_.Extension -in ".jpg", ".jpeg", ".png", ".webp" }
    if ($images.Count -eq 0) { Write-Host "  Skip $tag (empty)"; return }

    Write-Host "  Tag=$tag count=$($images.Count)..." -NoNewline
    $ok = 0
    $fail = 0

    foreach ($img in $images) {
        try {
            $boundary = [System.Guid]::NewGuid().ToString()
            $CRLF = "`r`n"
            $fileBytes = [System.IO.File]::ReadAllBytes($img.FullName)
            $mime = if ($img.Extension -eq ".png") { "image/png" } else { "image/jpeg" }
            $enc = [System.Text.Encoding]::UTF8

            $headerPart = "--$boundary$CRLF" +
                "Content-Disposition: form-data; name=`"files`"; filename=`"$($img.Name)`"$CRLF" +
                "Content-Type: $mime$CRLF$CRLF"
            $tagPart = "$CRLF--$boundary$CRLF" +
                "Content-Disposition: form-data; name=`"tag`"$CRLF$CRLF" +
                "$tag$CRLF--$boundary--$CRLF"

            $body = $enc.GetBytes($headerPart) + $fileBytes + $enc.GetBytes($tagPart)

            $resp = Invoke-RestMethod `
                -Uri "$BASE_URL/api/disney/import" `
                -Method POST `
                -ContentType "multipart/form-data; boundary=$boundary" `
                -Body $body

            $ok += $resp.imported
            $fail += $resp.failed
        }
        catch {
            $fail++
        }
    }

    $totalOk += $ok
    $totalFail += $fail
    $color = if ($fail -eq 0) { "Green" } else { "Yellow" }
    Write-Host " ok=$ok fail=$fail" -ForegroundColor $color
}

Write-Host ""
Write-Host "Done: ok=$totalOk fail=$totalFail" -ForegroundColor Cyan
Write-Host "Tags:"
$r = Invoke-RestMethod "$BASE_URL/api/disney/tags"
$r.tags | ForEach-Object { Write-Host ("  " + $_.tag + ": " + $_.count) }
