import math
import os
from PIL import Image, ImageDraw, ImageFilter

def generate_master_icon(size=2048):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))

    # 1. Base Squircle / Rounded App Icon Container
    pad = int(size * 120 / 2048)
    rad = int(size * 440 / 2048)
    bbox = [pad, pad, size - pad, size - pad]

    bg_mask = Image.new('L', (size, size), 0)
    bg_draw = ImageDraw.Draw(bg_mask)
    bg_draw.rounded_rectangle(bbox, radius=rad, fill=255)

    # Background gradient: deep dark obsidian with subtle violet undertone (#221C2B to #100D15)
    bg_gradient = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    bg_gdraw = ImageDraw.Draw(bg_gradient)
    for y in range(pad, size - pad):
        t = (y - pad) / (size - 2 * pad)
        r = int(34 * (1 - t) + 16 * t)
        g = int(28 * (1 - t) + 13 * t)
        b = int(44 * (1 - t) + 21 * t)
        bg_gdraw.line([(pad, y), (size - pad, y)], fill=(r, g, b, 255))

    bg = Image.composite(bg_gradient, Image.new('RGBA', (size, size), (0, 0, 0, 0)), bg_mask)

    # Outer border glow
    border_layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    border_draw = ImageDraw.Draw(border_layer)
    border_draw.rounded_rectangle(bbox, radius=rad, outline=(218, 178, 233, 90), width=int(size * 16 / 2048))

    img = Image.alpha_composite(img, bg)
    img = Image.alpha_composite(img, border_layer)

    # 2. Notebook Page in background
    nb_w = int(size * 960 / 2048)
    nb_h = int(size * 1260 / 2048)
    nb_layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    nb_draw = ImageDraw.Draw(nb_layer)

    nb_x1 = (size - nb_w) // 2
    nb_y1 = (size - nb_h) // 2 - int(size * 20 / 2048)
    nb_x2 = nb_x1 + nb_w
    nb_y2 = nb_y1 + nb_h

    # Notebook shadow
    nb_shadow = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    nbs_draw = ImageDraw.Draw(nb_shadow)
    nbs_draw.rounded_rectangle([nb_x1, nb_y1 + int(size * 30 / 2048), nb_x2, nb_y2 + int(size * 30 / 2048)], radius=int(size * 100 / 2048), fill=(0, 0, 0, 140))
    nb_shadow = nb_shadow.filter(ImageFilter.GaussianBlur(int(size * 40 / 2048)))
    img = Image.alpha_composite(img, nb_shadow)

    # Notebook card
    nb_draw.rounded_rectangle([nb_x1, nb_y1, nb_x2, nb_y2], radius=int(size * 100 / 2048), fill=(38, 31, 50, 240), outline=(218, 178, 233, 110), width=int(size * 10 / 2048))

    # Page accent header band
    nb_draw.rounded_rectangle([nb_x1 + int(size * 36 / 2048), nb_y1 + int(size * 36 / 2048), nb_x2 - int(size * 36 / 2048), nb_y1 + int(size * 120 / 2048)], radius=int(size * 40 / 2048), fill=(218, 178, 233, 40))

    # Ruled lines on notebook
    for ly in range(nb_y1 + int(size * 240 / 2048), nb_y2 - int(size * 120 / 2048), int(size * 130 / 2048)):
        nb_draw.line([(nb_x1 + int(size * 80 / 2048), ly), (nb_x2 - int(size * 80 / 2048), ly)], fill=(218, 178, 233, 50), width=int(size * 8 / 2048))

    img = Image.alpha_composite(img, nb_layer)

    # 3. Dynamic Fountain Pen Nib
    NIB_W = int(size * 640 / 2048)
    NIB_H = int(size * 1040 / 2048)
    nib_canvas = Image.new('RGBA', (NIB_W, NIB_H), (0, 0, 0, 0))
    ncx = NIB_W // 2

    top_y = int(NIB_H * 60 / 1040)
    shoulder_y = int(NIB_H * 400 / 1040)
    tip_y = int(NIB_H * 960 / 1040)
    top_w = int(NIB_W * 340 / 640)
    shoulder_w = int(NIB_W * 520 / 640)

    nib_poly = [
        (ncx - top_w // 2, top_y),
        (ncx + top_w // 2, top_y),
        (ncx + shoulder_w // 2, shoulder_y),
        (ncx + int(NIB_W * 40 / 640), tip_y - int(NIB_H * 70 / 1040)),
        (ncx, tip_y),
        (ncx - int(NIB_W * 40 / 640), tip_y - int(NIB_H * 70 / 1040)),
        (ncx - shoulder_w // 2, shoulder_y),
    ]

    nib_mask = Image.new('L', (NIB_W, NIB_H), 0)
    nm_draw = ImageDraw.Draw(nib_mask)
    nm_draw.polygon(nib_poly, fill=255)

    nib_grad = Image.new('RGBA', (NIB_W, NIB_H), (0, 0, 0, 0))
    ng_draw = ImageDraw.Draw(nib_grad)
    for y in range(top_y, tip_y + 1):
        t = (y - top_y) / (tip_y - top_y)
        if t < 0.4:
            st = t / 0.4
            r = int(245 * (1 - st) + 255 * st)
            g = int(230 * (1 - st) + 250 * st)
            b = int(255 * (1 - st) + 255 * st)
        else:
            st = (t - 0.4) / 0.6
            r = int(255 * (1 - st) + 205 * st)
            g = int(250 * (1 - st) + 155 * st)
            b = int(255 * (1 - st) + 230 * st)
        ng_draw.line([(0, y), (NIB_W, y)], fill=(r, g, b, 255))

    nib_body = Image.composite(nib_grad, Image.new('RGBA', (NIB_W, NIB_H), (0, 0, 0, 0)), nib_mask)

    # Breather & Slit cutout
    cutout = Image.new('RGBA', (NIB_W, NIB_H), (0, 0, 0, 0))
    cdraw = ImageDraw.Draw(cutout)
    br = int(NIB_W * 44 / 640)
    cdraw.ellipse([ncx - br, shoulder_y - br, ncx + br, shoulder_y + br], fill=(24, 19, 32, 255), outline=(255, 255, 255, 240), width=int(NIB_W * 6 / 640))
    cdraw.line([(ncx, shoulder_y + br), (ncx, tip_y)], fill=(24, 19, 32, 255), width=int(NIB_W * 16 / 640))

    # Engraved wings
    cdraw.line([(ncx - int(NIB_W * 36 / 640), shoulder_y - int(NIB_H * 20 / 1040)), (ncx - int(NIB_W * 150 / 640), shoulder_y - int(NIB_H * 100 / 1040)), (ncx - int(NIB_W * 170 / 640), shoulder_y - int(NIB_H * 210 / 1040))], fill=(24, 19, 32, 210), width=int(NIB_W * 11 / 640))
    cdraw.line([(ncx + int(NIB_W * 36 / 640), shoulder_y - int(NIB_H * 20 / 1040)), (ncx + int(NIB_W * 150 / 640), shoulder_y - int(NIB_H * 100 / 1040)), (ncx + int(NIB_W * 170 / 640), shoulder_y - int(NIB_H * 210 / 1040))], fill=(24, 19, 32, 210), width=int(NIB_W * 11 / 640))

    nib_composite = Image.alpha_composite(nib_body, cutout)

    # Rotate nib by 28 degrees
    rot_nib = nib_composite.rotate(28, resample=Image.Resampling.BICUBIC, expand=True)

    # Drop shadow for rotated nib
    shadow_nib = Image.new('RGBA', rot_nib.size, (0, 0, 0, 0))
    shadow_nib.paste((0, 0, 0, 200), mask=rot_nib.split()[3])
    shadow_nib = shadow_nib.filter(ImageFilter.GaussianBlur(int(size * 35 / 2048)))

    nx = (size - rot_nib.width) // 2 + int(size * 50 / 2048)
    ny = (size - rot_nib.height) // 2 + int(size * 20 / 2048)

    img.paste(shadow_nib, (nx + int(size * 15 / 2048), ny + int(size * 35 / 2048)), shadow_nib)
    img.paste(rot_nib, (nx, ny), rot_nib)

    # Glowing ink pearl at nib tip
    cx_rot = rot_nib.width / 2
    cy_rot = rot_nib.height / 2
    rad_angle = math.radians(-28)
    tip_rel_y = tip_y - NIB_H / 2
    tip_rot_x = - tip_rel_y * math.sin(rad_angle)
    tip_rot_y = tip_rel_y * math.cos(rad_angle)
    final_tip_x = int(nx + cx_rot + tip_rot_x)
    final_tip_y = int(ny + cy_rot + tip_rot_y)

    glow_layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    g_draw = ImageDraw.Draw(glow_layer)
    g_draw.ellipse([final_tip_x - int(size * 32 / 2048), final_tip_y - int(size * 32 / 2048), final_tip_x + int(size * 32 / 2048), final_tip_y + int(size * 32 / 2048)], fill=(255, 255, 255, 255))
    g_draw.ellipse([final_tip_x - int(size * 70 / 2048), final_tip_y - int(size * 70 / 2048), final_tip_x + int(size * 70 / 2048), final_tip_y + int(size * 70 / 2048)], outline=(218, 178, 233, 190), width=int(size * 8 / 2048))

    img = Image.alpha_composite(img, glow_layer)
    return img

def make_round_icon(img):
    w, h = img.size
    mask = Image.new('L', (w, h), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([0, 0, w, h], fill=255)
    
    round_img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    round_img.paste(img, (0, 0), mask=mask)
    return round_img

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    print(f"Building icons for project at {root}...")

    master_img = generate_master_icon(2048)
    master_1024 = master_img.resize((1024, 1024), Image.Resampling.LANCZOS)

    # 1. Windows Assets
    win_assets = os.path.join(root, "Notein-win", "src", "Notein-win", "Assets")
    os.makedirs(win_assets, exist_ok=True)
    
    # Save PNG
    png_path = os.path.join(win_assets, "mojian.png")
    master_1024.resize((256, 256), Image.Resampling.LANCZOS).save(png_path, "PNG")
    print(f"Saved {png_path}")

    # Save Multi-size ICO
    ico_path = os.path.join(win_assets, "mojian.ico")
    ico_sizes = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]
    ico_images = [master_1024.resize(s, Image.Resampling.LANCZOS) for s in ico_sizes]
    ico_images[0].save(ico_path, format="ICO", sizes=ico_sizes)
    print(f"Saved {ico_path}")

    # 2. Android Assets
    android_res = os.path.join(root, "android", "app", "src", "main", "res")
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, dim in densities.items():
        dir_path = os.path.join(android_res, folder)
        os.makedirs(dir_path, exist_ok=True)
        
        # Standard icon
        icon_dim = master_1024.resize((dim, dim), Image.Resampling.LANCZOS)
        icon_path = os.path.join(dir_path, "ic_launcher.png")
        icon_dim.save(icon_path, "PNG")
        
        # Round icon
        round_dim = make_round_icon(icon_dim)
        round_path = os.path.join(dir_path, "ic_launcher_round.png")
        round_dim.save(round_path, "PNG")
        print(f"Saved Android {folder} ({dim}x{dim})")

    # Adaptive icon foreground (432x432 for xxxhdpi)
    drawable_dir = os.path.join(android_res, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    fg_path = os.path.join(drawable_dir, "ic_launcher_foreground.png")
    master_1024.resize((432, 432), Image.Resampling.LANCZOS).save(fg_path, "PNG")
    print(f"Saved {fg_path}")

    # Adaptive icon XMLs
    anydpi_dir = os.path.join(android_res, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)
    
    adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""
    with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml)
    with open(os.path.join(anydpi_dir, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml)

    # Launcher background color
    values_dir = os.path.join(android_res, "values")
    os.makedirs(values_dir, exist_ok=True)
    colors_xml_path = os.path.join(values_dir, "colors.xml")
    if os.path.exists(colors_xml_path):
        with open(colors_xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        if "ic_launcher_background" not in content:
            content = content.replace("</resources>", "    <color name=\"ic_launcher_background\">#16131D</color>\n</resources>")
            with open(colors_xml_path, "w", encoding="utf-8") as f:
                f.write(content)
    else:
        with open(colors_xml_path, "w", encoding="utf-8") as f:
            f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#16131D</color>
</resources>
""")

    print("All icons successfully built and placed!")

if __name__ == "__main__":
    main()
