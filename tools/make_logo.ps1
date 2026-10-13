# Draws the mod's 256 x 256 logo (src/main/resources/mana_awakened.png): the sigil motif of the mod's icon, a dark disc with a gold
# ring of runes, a violet ring, two crossed squares of light and the glowing mana core in the middle.
# Usage (repo root): powershell -ExecutionPolicy Bypass -File tools\make_logo.ps1 [out.png]
param([string]$Out = "src\main\resources\mana_awakened.png")
Add-Type -AssemblyName System.Drawing
$size = 256
$c = $size / 2.0
$bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)

function Circle([double]$r) { return New-Object System.Drawing.RectangleF ([float]($c - $r)), ([float]($c - $r)), ([float](2 * $r)), ([float](2 * $r)) }
function Ring([double]$r, [System.Drawing.Color]$color, [double]$width) {
  $pen = New-Object System.Drawing.Pen $color, ([float]$width)
  $g.DrawEllipse($pen, (Circle $r)); $pen.Dispose()
}
function Glow([double]$r, [System.Drawing.Color]$inner, [System.Drawing.Color]$outer) {
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse((Circle $r))
  $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush $path
  $brush.CenterColor = $inner
  $brush.SurroundColors = @($outer)
  $g.FillPath($brush, $path); $brush.Dispose(); $path.Dispose()
}
function Square([double]$half, [double]$degrees, [System.Drawing.Color]$color, [double]$width) {
  $pts = New-Object 'System.Drawing.PointF[]' 4
  for ($i = 0; $i -lt 4; $i++) {
    $a = ($degrees + 45 + 90 * $i) * [Math]::PI / 180.0
    $pts[$i] = New-Object System.Drawing.PointF ([float]($c + [Math]::Cos($a) * $half * [Math]::Sqrt(2))), ([float]($c + [Math]::Sin($a) * $half * [Math]::Sqrt(2)))
  }
  $pen = New-Object System.Drawing.Pen $color, ([float]$width)
  $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Miter
  $g.DrawPolygon($pen, $pts); $pen.Dispose()
}
$rgb = { param($a, $r, $gg, $b) [System.Drawing.Color]::FromArgb($a, $r, $gg, $b) }

# the disc and its dark rim
$disc = New-Object System.Drawing.SolidBrush (& $rgb 255 14 16 38)
$g.FillEllipse($disc, (Circle 124)); $disc.Dispose()
Ring 123 (& $rgb 255 34 38 74) 3
Glow 118 (& $rgb 255 26 30 70) (& $rgb 255 12 13 32)

# the gold ring with its rune ticks
Ring 104 (& $rgb 255 255 196 64) 5
$tick = New-Object System.Drawing.Pen (& $rgb 255 255 216 102), 2.5
for ($i = 0; $i -lt 36; $i++) {
  $a = $i * 10 * [Math]::PI / 180.0
  $inner = if ($i % 3 -eq 0) { 108 } else { 110 }
  $g.DrawLine($tick, [float]($c + [Math]::Cos($a) * $inner), [float]($c + [Math]::Sin($a) * $inner), [float]($c + [Math]::Cos($a) * 116), [float]($c + [Math]::Sin($a) * 116))
}
$tick.Dispose()

# the violet ring and a faint inner one
Ring 90 (& $rgb 255 168 92 255) 5
Ring 80 (& $rgb 120 140 120 255) 1.5

# two crossed squares of light
Square 58 12 (& $rgb 255 96 214 255) 4
Square 58 -33 (& $rgb 230 120 170 255) 3.5
Square 40 57 (& $rgb 150 150 230 255) 2

# the core: a soft halo and a bright centre
Glow 64 (& $rgb 150 120 190 255) (& $rgb 0 40 60 160)
Glow 34 (& $rgb 255 245 252 255) (& $rgb 0 120 200 255)
Glow 14 (& $rgb 255 255 255 255) (& $rgb 160 220 240 255)

$g.Dispose()
$target = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path (Get-Location).Path $Out }
$bmp.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"wrote $target ($size x $size)"
