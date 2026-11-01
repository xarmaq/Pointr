"""Renders the Pointr icon: a dark rounded tile whose dot grid swells into a blue glow,
echoing the app's trackpad. Writes the Windows .ico and Android launcher PNGs."""
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
S = 1024

def render(rounded=True, bg=True):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    if bg:
        tile = Image.new("RGBA", (S, S))
        d = ImageDraw.Draw(tile)
        for y in range(S):  # vertical gradient
            t = y / S
            c = (int(24 - 12 * t), int(29 - 14 * t), int(42 - 20 * t), 255)
            d.line([(0, y), (S, y)], fill=c)
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=230 if rounded else 0, fill=255)
        img.paste(tile, (0, 0), mask)

    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([300, 300, 724, 724], fill=(76, 141, 255, 150))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(120)))

    dots = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(dots)
    step, cx, cy = 92, S / 2, S / 2
    n = 9
    off = (S - step * (n - 1)) / 2
    for i in range(n):
        for j in range(n):
            x, y = off + i * step, off + j * step
            dist = math.hypot(x - cx, y - cy) / (S * 0.42)
            k = max(0.0, 1 - dist) ** 1.6
            r = 9 + 30 * k
            col = (int(120 + 135 * k * 0.6), int(150 + 105 * k * 0.5), 255, int(55 + 200 * k))
            if k < 0.02:
                col = (200, 210, 230, 45)
            d.ellipse([x - r, y - r, x + r, y + r], fill=col)
    img = Image.alpha_composite(img, dots)
    d = ImageDraw.Draw(img)
    d.ellipse([cx - 56, cy - 56, cx + 56, cy + 56], fill=(240, 246, 255, 255))
    return img

full = render()
ico = ROOT / "windows/Pointr.Server/app.ico"
full.save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

res = ROOT / "android/app/src/main/res"
for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    out = res / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    full.resize((px, px), Image.LANCZOS).save(out / "ic_launcher.png")
    sq = render(rounded=False)
    circ = Image.new("L", (S, S), 0)
    ImageDraw.Draw(circ).ellipse([0, 0, S - 1, S - 1], fill=255)
    round_img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    round_img.paste(sq, (0, 0), circ)
    round_img.resize((px, px), Image.LANCZOS).save(out / "ic_launcher_round.png")
full.resize((512, 512), Image.LANCZOS).save(ROOT / "tools/icon-512.png")
print("ok")
