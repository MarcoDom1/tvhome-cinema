#!/usr/bin/env python3
"""Set up the Cinema theme for one TV: asks for your city (and, if you want, finds your WiZ lights), writes the
result into a temporary copy of themes/cinema and pushes it with tools/theme.sh.

Usage:  TV_HOST=192.168.1.50 python3 tools/cinema_setup.py

Nothing is stored in the repository: the city, its coordinates and your light MACs go only into the temporary copy
that is pushed to the TV. The city is looked up on open-meteo.com (no key); the lights are found with a UDP
broadcast on your own network.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEME = os.path.join(ROOT, "themes", "cinema")


def ask(prompt, default=None):
    suffix = f" [{default}]" if default else ""
    while True:
        value = input(f"{prompt}{suffix}: ").strip()
        if value:
            return value
        if default is not None:
            return default


def find_city():
    while True:
        name = ask("Which city is your TV in (for the weather)? Type it, or press Enter to skip", "")
        if not name:
            return None
        url = "https://geocoding-api.open-meteo.com/v1/search?" + urllib.parse.urlencode({"name": name, "count": 5, "format": "json"})
        try:
            with urllib.request.urlopen(url, timeout=10) as r:
                results = json.load(r).get("results") or []
        except OSError as e:
            print(f"  Could not reach the geocoder ({e}). Try again, or press Enter to skip.")
            continue
        if not results:
            print("  No match, try another spelling or add the country.")
            continue
        for i, place in enumerate(results, 1):
            print(f"  {i}) {place['name']}, {place.get('admin1', '')}, {place.get('country', '')}")
        pick = ask("Number", "1")
        try:
            place = results[int(pick) - 1]
        except (ValueError, IndexError):
            print("  Not a valid number.")
            continue
        return place["name"], place["latitude"], place["longitude"]


def local_prefix():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 80))
        ip = s.getsockname()[0]
    finally:
        s.close()
    return ip.rsplit(".", 1)[0] + "."


def ask_pilot(sock, host, method):
    try:
        sock.sendto(json.dumps({"method": method, "params": {}}).encode(), (host, 38899))
    except OSError:
        pass   # an address that cannot be reached is simply not a light


def find_wiz():
    """Read-only discovery: getPilot and getSystemConfig on UDP 38899, never a setPilot."""
    prefix = local_prefix()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(0.4)
    found = {}
    for _ in range(2):
        for i in range(1, 255):
            ask_pilot(sock, prefix + str(i), "getPilot")
        t = time.time()
        while time.time() - t < 2.5:
            try:
                data, addr = sock.recvfrom(4096)
                res = json.loads(data.decode()).get("result", {})
                if res.get("mac"):
                    found[addr[0]] = res["mac"]
            except (socket.timeout, ValueError):
                pass
    kinds = {}
    for host in found:
        module = ""
        ask_pilot(sock, host, "getSystemConfig")
        for _ in range(8):   # skip late answers to the earlier getPilot until the system config arrives
            try:
                data, addr = sock.recvfrom(4096)
                res = json.loads(data.decode()).get("result", {})
            except (socket.timeout, ValueError):
                break
            if addr[0] == host and res.get("moduleName"):
                module = res["moduleName"]
                break
        kinds[host] = "plug" if "SOCKET" in module.upper() else "rgb" if "RGB" in module.upper() else "bulb"
    return [(host, found[host], kinds.get(host, "bulb")) for host in sorted(found, key=lambda h: tuple(map(int, h.split("."))))]


def main():
    tv = os.environ.get("TV_HOST")
    if not tv:
        sys.exit("set TV_HOST to the TV's address, e.g. TV_HOST=192.168.1.50")
    work = tempfile.mkdtemp(prefix="cinema-")
    target = os.path.join(work, "cinema")
    shutil.copytree(THEME, target)
    cfg_path = os.path.join(target, "config.json")
    cfg = json.load(open(cfg_path))

    place = find_city()
    if place:
        cfg.update({"weather_city": place[0], "weather_lat": round(place[1], 4), "weather_lon": round(place[2], 4)})
        if ask("Temperature in Celsius or Fahrenheit? (c/f)", "c").lower().startswith("f"):
            cfg["weather_unit"] = "f"
    else:
        print("  No weather line, then.")

    if ask("Do you have WiZ lights you want as buttons on Home? (y/n)", "n").lower().startswith("y"):
        print("  Looking for WiZ lights on your network (read-only)...")
        devices = find_wiz()
        if not devices:
            print("  None found. They must be on the same network as this computer (2.4 GHz lights often are not).")
        lights = []
        for host, mac, kind in devices:
            print(f"  Found a {kind} at {host}")
            name = ask("  Name for it (Enter to skip)", "")
            if name:
                lights.append({"name": name, "devices": [{"mac": mac, "kind": kind}]})
        if lights:
            cfg.update({"lights": lights, "lights_style": "round", "lights_capsule": True, "lights_scale_percent": 112})

    json.dump(cfg, open(cfg_path, "w"), indent=2)
    print("Pushing the theme...")
    code = subprocess.call([os.path.join(ROOT, "tools", "theme.sh"), target], env=dict(os.environ, TV_HOST=tv))
    shutil.rmtree(work, ignore_errors=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
