#!/usr/bin/env python3
# ============================================================================
#  Panda Fit - generar_iconos.py
#  Genera los recursos del icono de la app a partir de una imagen.
#
#  Uso:
#     python generar_iconos.py <imagen_original.png>
#
#  Que produce (en app/src/main/res):
#     mipmap-mdpi/ic_launcher.png       48x48
#     mipmap-hdpi/ic_launcher.png       72x72
#     mipmap-xhdpi/ic_launcher.png      96x96
#     mipmap-xxhdpi/ic_launcher.png     144x144
#     mipmap-xxxhdpi/ic_launcher.png    192x192
#     ... y sus versiones ic_launcher_round (recortadas en circulo)
#
#  Criterio de diseno: la imagen se conserva INTACTA (sin recortes). Se ajusta
#  dentro del lienzo manteniendo la proporcion y el espacio sobrante se rellena
#  con el propio azul del icono, para que el resultado se vea como el original.
# ============================================================================
import sys
import os
from PIL import Image, ImageDraw

DENSIDADES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# El dibujo ocupa el 96% del lienzo: casi a sangre, sin recortarlo.
MARGEN = 0.96


def color_de_fondo(img: Image.Image):
    """Color medio de las cuatro esquinas: el fondo natural del icono."""
    w, h = img.size
    esquinas = [img.getpixel((0, 0)), img.getpixel((w - 1, 0)),
                img.getpixel((0, h - 1)), img.getpixel((w - 1, h - 1))]
    r = sum(c[0] for c in esquinas) // 4
    g = sum(c[1] for c in esquinas) // 4
    b = sum(c[2] for c in esquinas) // 4
    return (r, g, b, 255)


def encajar(img: Image.Image, lado: int) -> Image.Image:
    """
    Ajusta la imagen dentro de un cuadrado de `lado` px SIN recortarla:
    conserva la proporcion y centra el resultado.
    """
    ancho, alto = img.size
    interior = lado * MARGEN
    escala = min(interior / ancho, interior / alto)
    nuevo = (max(1, int(ancho * escala)), max(1, int(alto * escala)))
    reducida = img.resize(nuevo, Image.LANCZOS)

    lienzo = Image.new("RGBA", (lado, lado), color_de_fondo(img))
    x = (lado - nuevo[0]) // 2
    y = (lado - nuevo[1]) // 2
    lienzo.paste(reducida, (x, y), reducida)
    return lienzo


def recortar_circulo(img: Image.Image, escala: float = 1.0) -> Image.Image:
    """
    Devuelve una version REDONDA del icono.

    El dibujo NO se recorta: se dibuja completo y solo se rellenan las esquinas
    con su propio color de fondo, para que el circulo del lanzador no corte el
    anillo blanco del diseno. Si [escala] es menor que 1, el circulo queda algo
    mas pequeno dejando un margen limpio alrededor.
    """
    lado = img.size[0]
    factor = 4
    mascara = Image.new("L", (lado * factor, lado * factor), 0)
    dibujo = ImageDraw.Draw(mascara)
    if escala < 1.0:
        margen = int(lado * factor * (1.0 - escala) / 2.0)
        caja = (margen, margen, lado * factor - margen - 1, lado * factor - margen - 1)
    else:
        caja = (0, 0, lado * factor - 1, lado * factor - 1)
    dibujo.ellipse(caja, fill=255)
    mascara = mascara.resize((lado, lado), Image.LANCZOS)

    # Relleno exterior con el color de las esquinas del propio icono: asi el
    # circulo se integra con el fondo en lugar de cortar el diseno.
    fondo = Image.new("RGBA", (lado, lado), color_de_fondo(img))
    salida = Image.composite(img, fondo, mascara)
    return salida


def generar(origen: str, res_dir: str) -> None:
    base = Image.open(origen).convert("RGBA")
    fondo = color_de_fondo(base)
    print(f"Color de fondo detectado: {fondo}")

    for carpeta, tam in sorted(DENSIDADES.items(), key=lambda kv: kv[1]):
        destino = os.path.join(res_dir, carpeta)
        os.makedirs(destino, exist_ok=True)

        cuadrada = encajar(base, tam)
        cuadrada.save(os.path.join(destino, "ic_launcher.png"), "PNG", optimize=True)

        # Version redonda: circulo un poco mas pequeno para que el marco del
        # dibujo no quede cortado por el borde.
        redonda = recortar_circulo(cuadrada, escala=0.94)
        redonda.save(os.path.join(destino, "ic_launcher_round.png"), "PNG", optimize=True)

        print(f"  {carpeta}: ic_launcher.png + ic_launcher_round.png ({tam}x{tam})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Uso: python generar_iconos.py <imagen.png>")
        sys.exit(2)

    imagen = sys.argv[1]
    raiz = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    recursos = os.path.join(raiz, "app", "src", "main", "res")

    if not os.path.isfile(imagen):
        print(f"No existe la imagen: {imagen}")
        sys.exit(2)

    print("Generando iconos de Panda Fit...")
    generar(imagen, recursos)
    print("Listo.")
