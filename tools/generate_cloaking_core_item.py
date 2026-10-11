"""Regenerate the 3D inventory model from the current Blockbench exports.

Run from the mod project root:
    py -3 tools/generate_cloaking_core_item.py

This never modifies the in-world casing or Flywheel mirror models.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BLOCKS = ROOT / "src/main/resources/assets/aerocloakingcore/models/block"

CASING = BLOCKS / "cloaking_core_casing.json"
MIRRORS = BLOCKS / "cloaking_core_mirrors.json"
SHAFT = BLOCKS / "cloaking_core_input_shaft.json"
OUTPUT = BLOCKS / "cloaking_core_item.json"

# Match CloakingCoreVisual.MIRROR_FORWARD_OFFSET_PIXELS.
MIRROR_OFFSET_Z = 9.0

# Center the 2-block-long machine on Minecraft's usual [8,8,8] item pivot.
# Casing Z 0..32 becomes -8..24, rather than offsetting the inventory image.
ITEM_CENTER_Z = -8.0


def add_model(output: dict, path: Path, z_offset: float) -> None:
    model = json.loads(path.read_text(encoding="utf-8"))
    for key, value in model.get("textures", {}).items():
        if key != "particle":
            old = output["textures"].get(key)
            if old is not None and old != value:
                raise ValueError(f"Texture key #{key} collision: {old} vs {value}")
            output["textures"][key] = value

    for original in model.get("elements", []):
        element = copy.deepcopy(original)
        element["from"][2] = round(element["from"][2] + z_offset, 5)
        element["to"][2] = round(element["to"][2] + z_offset, 5)
        if "rotation" in element:
            element["rotation"]["origin"][2] = round(
                element["rotation"]["origin"][2] + z_offset, 5
            )
        output["elements"].append(element)


def main() -> None:
    item = {
        "credit": "Aero Cloaking Core - item-only combined casing and stationary mirrors",
        "ambientocclusion": False,
        "gui_light": "side",
        "textures": {"particle": "aerocloakingcore:block/brass_casing"},
        "elements": [],
        "display": {
            "gui": {
                "rotation": [27, 135, 0],
                "translation": [0, 0, 0],
                "scale": [0.43, 0.43, 0.43]
            },
            "ground": {
                "rotation": [0, 0, 0],
                "translation": [0, 3, 0],
                "scale": [0.28, 0.28, 0.28]
            },
            "fixed": {
                "rotation": [0, 180, 0],
                "translation": [0, 0, 0],
                "scale": [0.45, 0.45, 0.45]
            },
            "thirdperson_righthand": {
                "rotation": [75, 45, 0],
                "translation": [0, 2.5, 0],
                "scale": [0.32, 0.32, 0.32]
            },
            "thirdperson_lefthand": {
                "rotation": [75, 45, 0],
                "translation": [0, 2.5, 0],
                "scale": [0.32, 0.32, 0.32]
            },
            "firstperson_righthand": {
                "rotation": [0, 45, 0],
                "translation": [1.13, 3.2, 1.13],
                "scale": [0.36, 0.36, 0.36]
            },
            "firstperson_lefthand": {
                "rotation": [0, 225, 0],
                "translation": [1.13, 3.2, 1.13],
                "scale": [0.36, 0.36, 0.36]
            }
        }
    }
    add_model(item, CASING, ITEM_CENTER_Z)
    add_model(item, MIRRORS, ITEM_CENTER_Z + MIRROR_OFFSET_Z)
    add_model(item, SHAFT, ITEM_CENTER_Z)

    for element in item["elements"]:
        for face in element.get("faces", {}).values():
            tex = face.get("texture")
            if tex is not None and tex.startswith("#"):
                assert tex[1:] in item["textures"], f"Undefined texture {tex}"

    OUTPUT.write_text(json.dumps(item, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {OUTPUT.relative_to(ROOT)}")
    print(f"Total geometry: {len(item['elements'])} elements")


if __name__ == "__main__":
    main()
