#!/usr/bin/env python3
"""Import the authoritative 27- and 45-degree Georgian wall models."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = (
    PROJECT_ROOT
    / "src/main/resources/assets/erydon/authoring_models/block/wall/georgian"
)
MODEL_SPECS = {
    "27_upper": (106, 312, 72, 188),
    "27_lower": (106, 636, 72, 432),
    "27_lower_onramp": (110, 347, 72, 202),
    "27_upper_offramp": (111, 348, 72, 202),
    "45": (106, 318, 0, 0),
    "45_onramp": (110, 350, 0, 0),
    "45_offramp": (110, 348, 0, 0),
}
ALL_FACES = ("north", "east", "south", "west", "up", "down")
CIRCULAR_DETAIL_NAME = "circular detail"
STALE_OUTPUTS = (
    "wall_georgian_27_lower_stub.json",
    "wall_georgian_27_upper_stub.json",
)


def canonical_model(
    source: Path,
    expected_elements: int,
    expected_faces: int,
    expected_circular_details: int,
    expected_circular_detail_faces: int,
) -> dict:
    with source.open("r", encoding="utf-8-sig") as handle:
        model = json.load(handle)

    elements = model.get("elements")
    if not isinstance(elements, list) or len(elements) != expected_elements:
        raise ValueError(
            f"{source} must contain exactly {expected_elements} elements"
        )

    face_count = 0
    circular_detail_count = 0
    circular_detail_faces = 0
    for element in elements:
        faces = element.get("faces")
        if not isinstance(faces, dict):
            raise ValueError(f"{source} contains an element without faces")
        for direction, face in faces.items():
            if direction not in ALL_FACES:
                raise ValueError(f"{source} contains unknown face {direction!r}")
            if not isinstance(face, dict):
                raise ValueError(f"{source} contains an invalid face")
            face_count += 1
            face.pop("uv", None)
            face["texture"] = "#wall"
        if element.get("name") == CIRCULAR_DETAIL_NAME:
            circular_detail_count += 1
            circular_detail_faces += len(faces)

    if face_count != expected_faces:
        raise ValueError(f"{source} must contain exactly {expected_faces} faces")
    if circular_detail_count != expected_circular_details:
        raise ValueError(
            f"{source} must contain exactly {expected_circular_details} "
            f"elements named {CIRCULAR_DETAIL_NAME!r}"
        )
    if circular_detail_faces != expected_circular_detail_faces:
        raise ValueError(
            f"{source} must contain exactly {expected_circular_detail_faces} "
            f"faces on {CIRCULAR_DETAIL_NAME!r} elements"
        )

    return model


def encoded(model: dict) -> str:
    return json.dumps(model, ensure_ascii=False, indent=2) + "\n"


def import_model(
    source: Path,
    target: Path,
    check: bool,
    expected_elements: int,
    expected_faces: int,
    expected_circular_details: int,
    expected_circular_detail_faces: int,
) -> None:
    expected = encoded(canonical_model(
        source,
        expected_elements,
        expected_faces,
        expected_circular_details,
        expected_circular_detail_faces,
    ))
    if check:
        if not target.is_file() or target.read_text(encoding="utf-8") != expected:
            raise SystemExit(f"Out of date: {target}")
        return

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(expected, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upper", type=Path, required=True)
    parser.add_argument("--lower", type=Path, required=True)
    parser.add_argument("--lower-onramp", type=Path, required=True)
    parser.add_argument("--upper-offramp", type=Path, required=True)
    parser.add_argument("--model-45", type=Path, required=True)
    parser.add_argument("--model-45-onramp", type=Path, required=True)
    parser.add_argument("--model-45-offramp", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    sources = {
        "27_upper": args.upper,
        "27_lower": args.lower,
        "27_lower_onramp": args.lower_onramp,
        "27_upper_offramp": args.upper_offramp,
        "45": args.model_45,
        "45_onramp": args.model_45_onramp,
        "45_offramp": args.model_45_offramp,
    }
    for model_name, source in sources.items():
        (
            expected_elements,
            expected_faces,
            expected_circular_details,
            expected_circular_detail_faces,
        ) = MODEL_SPECS[model_name]
        import_model(
            source,
            OUTPUT_ROOT / f"wall_georgian_{model_name}.json",
            args.check,
            expected_elements,
            expected_faces,
            expected_circular_details,
            expected_circular_detail_faces,
        )

    for stale_name in STALE_OUTPUTS:
        stale = OUTPUT_ROOT / stale_name
        if args.check and stale.exists():
            raise SystemExit(f"Obsolete model still present: {stale}")
        if not args.check and stale.exists():
            stale.unlink()

    action = "verified" if args.check else "imported"
    element_total = sum(spec[0] for spec in MODEL_SPECS.values())
    face_total = sum(spec[1] for spec in MODEL_SPECS.values())
    print(
        f"Georgian wall slope models {action}: "
        f"elements={element_total}, faces={face_total}"
    )


if __name__ == "__main__":
    main()
