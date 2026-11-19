"""Generates the SVG posters, banners and pictures used by the demo data.

Run from the project root:  python3 scripts/generate_demo_images.py
The movies are invented for the demo, so every picture is drawn here instead of copied.
"""
import math
import os
import random

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "static", "images")
FONT = "'Be Vietnam Pro', 'Segoe UI', Arial, sans-serif"


def write(folder, name, svg):
    path = os.path.join(ROOT, folder)
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, name), "w", encoding="utf-8") as f:
        f.write(svg)


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


POSTERS = [
    # slug, title lines, tagline, colors (top, bottom, accent), motif, rating
    ("vet-sang", ["VỆT SÁNG", "CUỐI CÙNG"], "48 giờ trước khi trạm rơi", ("#0b1d3a", "#020611", "#ffb400"), "beam", "T13"),
    ("mua-he", ["MÙA HÈ", "Ở PHỐ CỔ"], "Một mùa hè thắp sáng ký ức", ("#ffcf7a", "#e8693c", "#fff6e0"), "lanterns", "P"),
    ("can-nha", ["CĂN NHÀ", "SỐ 13"], "Căn phòng không có trong bản vẽ", ("#1a1a1a", "#3b0a0a", "#d7263d"), "door", "T18"),
    ("meo-hoang", ["BIỆT ĐỘI", "MÈO HOANG"], "Nhỏ mà có võ", ("#4cc9f0", "#4361ee", "#ffd60a"), "cats", "P"),
    ("dinh-gio", ["ĐỈNH", "GIÓ BẮC"], "Không ai bị bỏ lại", ("#cfe3f2", "#3a5a78", "#ffffff"), "mountain", "T13"),
    ("gac-dem", ["KẺ", "GÁC ĐÊM"], "Trời sáng là quá muộn", ("#10151f", "#1f2b3d", "#59c3c3"), "building", "T16"),
    ("tieng-dan", ["TIẾNG ĐÀN", "TRÊN SÔNG"], "Giữ lấy câu hò của cha", ("#2b9348", "#0b3d2e", "#f9dc5c"), "river", "K"),
    ("hanh-tinh", ["HÀNH TINH", "LẶNG IM"], "Ở đây không ai nghe thấy bạn", ("#2d1b4e", "#07020f", "#a78bfa"), "planet", "T13"),
    ("lop-hoc", ["LỚP HỌC", "SIÊU QUẬY"], "Quậy hết mình, học hết sức", ("#ff6b9a", "#ffb400", "#1b1f3b"), "doodles", "T13"),
    ("ngoi-den", ["NGÔI ĐỀN", "CÁT"], "Bản đồ cuối cùng của Chăm Pa", ("#f4a259", "#8c3b1b", "#fff1d0"), "temple", "T16"),
]


def motif_svg(kind, accent, rnd):
    parts = []
    if kind == "beam":
        parts.append(f'<polygon points="200,0 230,600 170,600" fill="{accent}" opacity="0.25"/>')
        parts.append(f'<circle cx="200" cy="210" r="70" fill="none" stroke="{accent}" stroke-width="3"/>')
        parts.append(f'<circle cx="200" cy="210" r="8" fill="{accent}"/>')
        for i in range(60):
            parts.append(f'<circle cx="{rnd.randint(0,400)}" cy="{rnd.randint(0,420)}" r="{rnd.random()*1.6:.1f}" fill="#fff" opacity="0.8"/>')
    elif kind == "lanterns":
        for i in range(7):
            x = 40 + i * 55
            y = 60 + (i % 3) * 50
            parts.append(f'<line x1="{x}" y1="0" x2="{x}" y2="{y-30}" stroke="#7a2e10" stroke-width="2"/>')
            parts.append(f'<ellipse cx="{x}" cy="{y}" rx="22" ry="30" fill="#d62828" opacity="0.9"/>')
            parts.append(f'<ellipse cx="{x}" cy="{y}" rx="12" ry="30" fill="#f77f00" opacity="0.7"/>')
        parts.append('<path d="M0 360 Q100 320 200 360 T400 360 V420 H0Z" fill="#7a2e10" opacity="0.35"/>')
    elif kind == "door":
        parts.append('<rect x="130" y="90" width="140" height="260" fill="#0d0d0d" stroke="#d7263d" stroke-width="4"/>')
        parts.append('<rect x="140" y="100" width="120" height="250" fill="#2a0505"/>')
        parts.append('<polygon points="140,100 200,130 200,330 140,350" fill="#000" opacity="0.6"/>')
        parts.append(f'<text x="200" y="200" text-anchor="middle" font-family="{FONT}" font-size="64" font-weight="800" fill="#d7263d">13</text>')
        parts.append('<circle cx="245" cy="230" r="5" fill="#d7263d"/>')
    elif kind == "cats":
        for i, (x, s) in enumerate([(80, 1.0), (160, 1.2), (250, 1.1), (330, 0.9)]):
            y = 300
            parts.append(f'<g transform="translate({x},{y}) scale({s})">'
                         f'<ellipse cx="0" cy="0" rx="34" ry="40" fill="#1b1f3b"/>'
                         f'<circle cx="0" cy="-48" r="26" fill="#1b1f3b"/>'
                         f'<polygon points="-22,-62 -14,-88 -4,-68" fill="#1b1f3b"/><polygon points="22,-62 14,-88 4,-68" fill="#1b1f3b"/>'
                         f'<circle cx="-9" cy="-50" r="5" fill="#ffd60a"/><circle cx="9" cy="-50" r="5" fill="#ffd60a"/></g>')
        parts.append('<circle cx="320" cy="90" r="40" fill="#ffd60a" opacity="0.9"/>')
    elif kind == "mountain":
        parts.append('<polygon points="0,420 140,150 230,300 290,200 400,420" fill="#ffffff" opacity="0.85"/>')
        parts.append('<polygon points="0,420 140,150 180,230 120,260 60,420" fill="#9fb8cf"/>')
        for i in range(70):
            parts.append(f'<circle cx="{rnd.randint(0,400)}" cy="{rnd.randint(0,420)}" r="{1+rnd.random()*2:.1f}" fill="#fff" opacity="0.9"/>')
    elif kind == "building":
        for i in range(6):
            for j in range(9):
                lit = rnd.random() < 0.18
                parts.append(f'<rect x="{90+i*38}" y="{70+j*36}" width="24" height="22" fill="{accent if lit else "#2b3a52"}" opacity="{0.95 if lit else 0.7}"/>')
        parts.append('<rect x="70" y="50" width="260" height="360" fill="none" stroke="#2b3a52" stroke-width="6"/>')
    elif kind == "river":
        parts.append('<path d="M0 300 Q100 260 200 300 T400 300 V420 H0Z" fill="#0b3d2e" opacity="0.7"/>')
        parts.append('<path d="M120 290 Q200 270 280 290 L260 310 H140Z" fill="#5c3d1e"/>')
        parts.append('<line x1="200" y1="290" x2="200" y2="200" stroke="#5c3d1e" stroke-width="4"/>')
        parts.append(f'<circle cx="300" cy="110" r="46" fill="{accent}" opacity="0.85"/>')
        for i in range(5):
            parts.append(f'<path d="M{60+i*70} 200 q10 -20 20 0" stroke="{accent}" stroke-width="3" fill="none" opacity="0.7"/>')
    elif kind == "planet":
        parts.append(f'<circle cx="200" cy="220" r="120" fill="{accent}" opacity="0.3"/>')
        parts.append('<circle cx="200" cy="220" r="100" fill="#4c1d95"/>')
        parts.append(f'<ellipse cx="200" cy="220" rx="180" ry="30" fill="none" stroke="{accent}" stroke-width="4" transform="rotate(-15 200 220)"/>')
        for i in range(80):
            parts.append(f'<circle cx="{rnd.randint(0,400)}" cy="{rnd.randint(0,420)}" r="{rnd.random()*1.5:.1f}" fill="#fff"/>')
    elif kind == "doodles":
        for i in range(14):
            x, y = rnd.randint(20, 380), rnd.randint(20, 400)
            shape = i % 3
            if shape == 0:
                parts.append(f'<circle cx="{x}" cy="{y}" r="{rnd.randint(8,20)}" fill="none" stroke="#1b1f3b" stroke-width="4"/>')
            elif shape == 1:
                parts.append(f'<path d="M{x} {y} l12 -18 l12 18 z" fill="#fff" stroke="#1b1f3b" stroke-width="3"/>')
            else:
                parts.append(f'<path d="M{x-14} {y} q7 -14 14 0 t14 0" fill="none" stroke="#fff" stroke-width="4"/>')
        parts.append('<rect x="120" y="170" width="160" height="110" rx="8" fill="#1b1f3b"/>')
        parts.append(f'<text x="200" y="235" text-anchor="middle" font-family="{FONT}" font-size="34" font-weight="800" fill="#ffb400">11A7</text>')
    elif kind == "temple":
        parts.append('<circle cx="300" cy="100" r="50" fill="#fff1d0" opacity="0.8"/>')
        parts.append('<polygon points="200,120 260,260 140,260" fill="#6b2a12"/>')
        parts.append('<rect x="130" y="260" width="140" height="70" fill="#6b2a12"/>')
        parts.append('<rect x="185" y="285" width="30" height="45" fill="#2b0f05"/>')
        parts.append('<path d="M0 360 Q120 320 220 350 T400 340 V420 H0Z" fill="#c9733a"/>')
    return "\n".join(parts)


def poster(slug, lines, tagline, colors, motif, rating, index):
    top, bottom, accent = colors
    rnd = random.Random(index)
    dark_text = slug in ("mua-he", "lop-hoc")
    text_color = "#1b1f3b" if dark_text else "#ffffff"
    title = "".join(
        f'<text x="200" y="{470 + i*52}" text-anchor="middle" font-family="{FONT}" font-size="46" font-weight="800" '
        f'letter-spacing="1" fill="{text_color}">{esc(line)}</text>' for i, line in enumerate(lines))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 600" width="400" height="600">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient></defs>
<rect width="400" height="600" fill="url(#g)"/>
{motif_svg(motif, accent, rnd)}
<rect x="0" y="420" width="400" height="180" fill="{bottom}" opacity="0.35"/>
{title}
<text x="200" y="580" text-anchor="middle" font-family="{FONT}" font-size="15" fill="{text_color}" opacity="0.85">{esc(tagline)}</text>
<rect x="320" y="18" width="62" height="30" rx="6" fill="#ffb400"/>
<text x="351" y="39" text-anchor="middle" font-family="{FONT}" font-size="16" font-weight="800" fill="#1b1f3b">{rating}</text>
<text x="20" y="38" font-family="{FONT}" font-size="14" font-weight="800" fill="{text_color}" opacity="0.8">BBCINEMA</text>
</svg>'''


def banner(name, title, subtitle, c1, c2, accent):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 420" width="1200" height="420">
<defs><linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>
<rect width="1200" height="420" fill="url(#b)"/>
<g opacity="0.12" fill="#ffffff">{''.join(f'<rect x="{x}" y="0" width="18" height="420" transform="skewX(-20)"/>' for x in range(700, 1500, 60))}</g>
<circle cx="980" cy="210" r="150" fill="{accent}" opacity="0.9"/>
<circle cx="980" cy="210" r="110" fill="none" stroke="#1b1f3b" stroke-width="10" stroke-dasharray="20 14"/>
<polygon points="950,160 950,260 1040,210" fill="#1b1f3b"/>
<text x="80" y="190" font-family="{FONT}" font-size="58" font-weight="800" fill="#ffffff">{esc(title)}</text>
<text x="80" y="250" font-family="{FONT}" font-size="26" fill="#ffffff" opacity="0.85">{esc(subtitle)}</text>
<rect x="80" y="290" width="200" height="54" rx="27" fill="{accent}"/>
<text x="180" y="325" text-anchor="middle" font-family="{FONT}" font-size="22" font-weight="800" fill="#1b1f3b">ĐẶT VÉ NGAY</text>
</svg>'''


def combo(name, cups, buckets, color):
    items = []
    x = 200 - (buckets * 70 + cups * 50) / 2
    for _ in range(buckets):
        items.append(f'<g transform="translate({x},70)"><polygon points="0,30 60,30 52,130 8,130" fill="#d62828"/>'
                     f'<rect x="12" y="30" width="8" height="100" fill="#fff" opacity="0.8"/><rect x="36" y="30" width="8" height="100" fill="#fff" opacity="0.8"/>'
                     + "".join(f'<circle cx="{8+i*11}" cy="{24 - (i%2)*8}" r="10" fill="#fff4d6"/>' for i in range(5)) + '</g>')
        x += 70
    for _ in range(cups):
        items.append(f'<g transform="translate({x},90)"><polygon points="0,20 40,20 34,110 6,110" fill="{color}"/>'
                     f'<rect x="-2" y="12" width="44" height="10" rx="3" fill="#1b1f3b"/><line x1="24" y1="12" x2="32" y2="-14" stroke="#1b1f3b" stroke-width="4"/></g>')
        x += 50
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 240" width="400" height="240">
<rect width="400" height="240" rx="16" fill="#fff6e0"/>
{''.join(items)}
<text x="200" y="225" text-anchor="middle" font-family="{FONT}" font-size="18" font-weight="800" fill="#1b1f3b">{esc(name)}</text>
</svg>'''


def post_image(title, c1, c2, symbol):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 360" width="640" height="360">
<defs><linearGradient id="p" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>
<rect width="640" height="360" fill="url(#p)"/>
<text x="520" y="220" text-anchor="middle" font-family="{FONT}" font-size="140" font-weight="800" fill="#ffffff" opacity="0.25">{esc(symbol)}</text>
<text x="40" y="300" font-family="{FONT}" font-size="32" font-weight="800" fill="#ffffff">{esc(title)}</text>
<text x="40" y="60" font-family="{FONT}" font-size="18" font-weight="800" fill="#ffb400">BBCINEMA</text>
</svg>'''


def main():
    for i, p in enumerate(POSTERS):
        write("posters", p[0] + ".svg", poster(*p, i))
    write("banners", "banner-1.svg", banner("1", "Đặt vé nhanh, chọn ghế đẹp", "Giữ ghế 5 phút, thanh toán trong một bước", "#1b1f3b", "#2e3a87", "#ffb400"))
    write("banners", "banner-2.svg", banner("2", "Thứ Tư vui vẻ", "Đồng giá 50.000đ cho suất 2D trước 17h", "#ff6b35", "#c1121f", "#ffd60a"))
    write("banners", "banner-3.svg", banner("3", "Hành Tinh Lặng Im", "Suất chiếu sớm 23h, chỉ trong 2 đêm", "#2d1b4e", "#07020f", "#a78bfa"))
    write("combos", "popcorn.svg", combo("BẮP NGỌT", 0, 1, "#4361ee"))
    write("combos", "solo.svg", combo("SOLO", 1, 1, "#4361ee"))
    write("combos", "couple.svg", combo("COUPLE", 2, 1, "#ff6b9a"))
    write("combos", "family.svg", combo("GIA ĐÌNH", 3, 2, "#2b9348"))
    write("posts", "wednesday.svg", post_image("Thứ Tư vui vẻ 50K", "#ff6b35", "#c1121f", "50K"))
    write("posts", "points.svg", post_image("Tích điểm 5% mỗi đơn", "#1b1f3b", "#2e3a87", "5%"))
    write("posts", "opening.svg", post_image("Phòng chiếu 3D mới", "#0b3d2e", "#2b9348", "3D"))
    write("posts", "premiere.svg", post_image("Suất chiếu sớm", "#2d1b4e", "#07020f", "23h"))
    write("", "no-image.svg", post_image("BBCinema", "#1b1f3b", "#2e3a87", "BB"))


if __name__ == "__main__":
    main()
