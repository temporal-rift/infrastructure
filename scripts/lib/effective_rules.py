#!/usr/bin/env python3
"""Resolve a simulation lane's game.rules into the shared EffectiveRules document.

The config repository serves application.yml plus one file per service. Each service's
view is application.yml deep-merged with its own file, and every game.rules leaf from
those views forms one document. Values that disagree across services are refused.

Subcommands:
  resolve          Write the canonical document and print its effective rules digest.
  digest           Print the effective rules digest of the resolved document.
  document-digest  Print the effective rules digest of an existing document file.
"""

import argparse
import hashlib
import json
import math
import sys
from decimal import Decimal
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - exercised by the shell probe
    sys.stderr.write("effective rules resolution requires PyYAML (pip install pyyaml)\n")
    sys.exit(2)

SERVICES = ("game-service", "timeline-service", "read-service")


def fail(message):
    sys.stderr.write(message + "\n")
    sys.exit(1)


def load_yaml(path):
    """Load a YAML mapping, returning an empty mapping for an empty file."""
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"cannot read {path}: {error}")
    except yaml.YAMLError as error:
        fail(f"cannot parse {path}: {error}")
    if data is None:
        return {}
    if not isinstance(data, dict):
        fail(f"{path} must contain a mapping at the top level")
    return data


def deep_merge(base, override):
    """Merge one mapping over another recursively."""
    if not isinstance(base, dict) or not isinstance(override, dict):
        return override
    merged = dict(base)
    for key, value in override.items():
        if key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def flatten(node, prefix, flattened, service):
    """Flatten a mapping into dotted leaf paths, rejecting colliding paths."""
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(value, f"{prefix}.{key}", flattened, service)
    else:
        if prefix in flattened:
            fail(f"RULES_CONFLICT: multiple rule values resolve to {prefix} in {service}")
        flattened[prefix] = node


def resolved_rules(config_repo, service):
    """Return the game.rules leaves one service resolves."""
    shared = load_yaml(config_repo / "application.yml")
    own = load_yaml(config_repo / f"{service}.yml")
    game = deep_merge(shared, own).get("game", {})
    rules = game.get("rules") if isinstance(game, dict) else None
    if rules is None:
        return {}
    if not isinstance(rules, dict):
        fail(f"game.rules in {config_repo / f'{service}.yml'} must be a mapping")
    flattened = {}
    flatten(rules, "game.rules", flattened, service)
    return flattened


def assert_supported(key, value):
    """Reject a value outside the EffectiveRules value grammar."""
    if isinstance(value, (bool, int, float, str)):
        return
    if isinstance(value, list):
        for item in value:
            if not isinstance(item, (bool, int, float, str)):
                fail(f"UNSUPPORTED_RULES_VALUE: {key} contains a non-scalar array value")
        return
    fail(f"UNSUPPORTED_RULES_VALUE: {key} is not a number, boolean, string, or array of those")


def canonical_number(value):
    """Serialize a finite number as RFC 8785 / ECMAScript does."""
    if not math.isfinite(value):
        fail(f"UNSUPPORTED_RULES_VALUE: {value} is not a finite number")
    if value == 0:
        return "0"
    if value == int(value) and abs(value) < 1e21:
        return str(int(value))

    sign = "-" if value < 0 else ""
    decimal = Decimal(repr(abs(value)))
    _, digit_tuple, exponent = decimal.as_tuple()
    digits = "".join(str(digit) for digit in digit_tuple)
    point = len(digits) + exponent

    if 1e-6 <= abs(value) < 1e21:
        if point <= 0:
            body = "0." + "0" * (-point) + digits
        elif point >= len(digits):
            body = digits + "0" * (point - len(digits))
        else:
            body = digits[:point] + "." + digits[point:]
        return sign + body

    exponent_form = point - 1
    mantissa = digits[0] + ("." + digits[1:] if len(digits) > 1 else "")
    return f"{sign}{mantissa}e{'+' if exponent_form >= 0 else '-'}{abs(exponent_form)}"


def canonical_json(value):
    """Serialize a JSON value in RFC 8785 canonical form."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return canonical_number(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda key: key.encode("utf-16-be"))
        return "{" + ",".join(
            canonical_json(key) + ":" + canonical_json(value[key]) for key in keys
        ) + "}"
    fail(f"UNSUPPORTED_RULES_VALUE: cannot canonicalise {type(value).__name__}")


def effective_rules_digest(properties):
    """Return the SHA-256 digest of canonical properties."""
    digest = hashlib.sha256(canonical_json(properties).encode("utf-8"))
    return digest.hexdigest()


def resolve_properties(config_repo):
    """Resolve every service view and merge the rules, rejecting conflicts."""
    per_service = {service: resolved_rules(config_repo, service) for service in SERVICES}
    by_key = {}
    for service, rules in per_service.items():
        for key, value in rules.items():
            by_key.setdefault(key, {})[service] = value

    properties = {}
    for key in sorted(by_key):
        values = by_key[key]
        canonical = {service: canonical_json(value) for service, value in values.items()}
        if len(set(canonical.values())) > 1:
            detail = ", ".join(
                f"{value} by {service}" for service, value in sorted(canonical.items())
            )
            fail(f"RULES_CONFLICT: {key} resolves differently across services ({detail})")
        value = next(iter(values.values()))
        assert_supported(key, value)
        properties[key] = value
    return properties


def resolve_document(config_repo):
    """Build the schemaVersion 1 EffectiveRules document."""
    return {"schemaVersion": 1, "properties": resolve_properties(config_repo)}


def main():
    """Run the resolver CLI."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    resolve = commands.add_parser("resolve", help="write the canonical document")
    resolve.add_argument("--config-repo", required=True, type=Path)
    resolve.add_argument("--output", required=True, type=Path)

    digest = commands.add_parser("digest", help="print the resolved rules digest")
    digest.add_argument("--config-repo", required=True, type=Path)

    document_digest = commands.add_parser(
        "document-digest", help="print an existing document's rules digest"
    )
    document_digest.add_argument("--file", required=True, type=Path)

    arguments = parser.parse_args()

    if arguments.command == "resolve":
        doc = resolve_document(arguments.config_repo)
        arguments.output.write_text(canonical_json(doc), encoding="utf-8")
        print(effective_rules_digest(doc["properties"]))
        return

    if arguments.command == "digest":
        print(effective_rules_digest(resolve_properties(arguments.config_repo)))
        return

    try:
        doc = json.loads(arguments.file.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"cannot read {arguments.file}: {error}")
    except json.JSONDecodeError as error:
        fail(f"cannot parse {arguments.file}: {error}")
    if (
        not isinstance(doc, dict)
        or not isinstance(doc.get("schemaVersion"), int)
        or isinstance(doc.get("schemaVersion"), bool)
        or doc.get("schemaVersion") != 1
        or not isinstance(doc.get("properties"), dict)
    ):
        fail(f"{arguments.file} is not a schemaVersion 1 EffectiveRules document")
    print(effective_rules_digest(doc["properties"]))


if __name__ == "__main__":
    main()
