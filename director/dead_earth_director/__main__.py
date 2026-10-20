import argparse
from pathlib import Path
import sys
from .bridge import BridgeClient, BridgeError, Descriptor
from .mcp_server import create_server


def main():
    parser = argparse.ArgumentParser(description="Local bounded DEAD EARTH MCP gateway")
    parser.add_argument("--bridge", required=True, type=Path, help="World's dead_earth_bridge.json; never pass a token on the command line")
    parser.add_argument("--profile", choices=("modern","srp"), default="modern", help="Expose only operations implemented by the selected coordinator")
    args = parser.parse_args()
    try:
        descriptor = Descriptor.load(args.bridge)
    except BridgeError as error:
        print(f"Dead Earth: {error}", file=sys.stderr)
        return 2
    create_server(BridgeClient(descriptor),profile=args.profile).run(transport="stdio")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
