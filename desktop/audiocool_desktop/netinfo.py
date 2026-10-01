"""Finds the computer's LAN addresses, i.e. what the phone should connect to.

Only private IPv4 addresses (192.168/16, 10/8, 172.16/12) of real network adapters count:
loopback, Docker/Kubernetes/VM bridges and VPN tunnels are skipped. Under WSL2 with mirrored
networking the Windows adapters appear as eth*, so their addresses are the Windows host's.
"""

from __future__ import annotations

import ipaddress
import json
import os
import socket
import subprocess
from pathlib import Path

VIRTUAL_PREFIXES = (
    "lo", "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "flannel", "cni", "cali", "tun", "tap",
    "wg", "zt", "tailscale", "lxc", "lxd", "podman", "kube", "vxlan", "genev", "dummy", "bond", "nerdctl",
)


def is_private_lan(ip: str) -> bool:
    try:
        addr = ipaddress.IPv4Address(ip)
    except ValueError:
        return False
    return any(addr in net for net in (
        ipaddress.IPv4Network("192.168.0.0/16"),
        ipaddress.IPv4Network("10.0.0.0/8"),
        ipaddress.IPv4Network("172.16.0.0/12"),
    ))


def _is_physical(ifname: str) -> bool:
    if ifname.startswith(VIRTUAL_PREFIXES):
        return False
    # Real adapters (including WSL's mirrored Hyper-V NICs) have a backing device; bridges don't.
    return Path(f"/sys/class/net/{ifname}/device").exists() or not Path("/sys/class/net").exists()


def _from_ip_command() -> list[tuple[str, str]]:
    out = subprocess.run(["ip", "-j", "-4", "addr", "show", "up"], capture_output=True, text=True, timeout=3)
    pairs = []
    for iface in json.loads(out.stdout or "[]"):
        name = iface.get("ifname", "")
        for a in iface.get("addr_info", []):
            if a.get("family") == "inet" and a.get("local"):
                pairs.append((name, a["local"]))
    return pairs


def _default_route_ifname() -> str | None:
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) > 2 and parts[1] == "00000000":
                    return parts[0]
    except OSError:
        pass
    return None


def _route_source_ip() -> str | None:
    """The address the OS would use to reach the internet (no packet is sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # TEST-NET-1; connect() on UDP only picks a route
            return s.getsockname()[0]
    except OSError:
        return None


def lan_addresses() -> list[str]:
    """Private IPv4 addresses of real adapters, the one with the default route first."""
    if os.environ.get("AUDIOCOOL_LAN_IPS"):
        return [ip.strip() for ip in os.environ["AUDIOCOOL_LAN_IPS"].split(",") if ip.strip()]
    pairs: list[tuple[str, str]] = []
    try:
        pairs = _from_ip_command()
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    default_if = _default_route_ifname()
    found = [(name, ip) for name, ip in pairs if is_private_lan(ip) and _is_physical(name)]
    found.sort(key=lambda p: (p[0] != default_if, p[0]))
    ips = []
    for _, ip in found:
        if ip not in ips:
            ips.append(ip)
    if not ips:
        ip = _route_source_ip()
        if ip and is_private_lan(ip):
            ips.append(ip)
    return ips


def all_local_addresses() -> set[str]:
    """Every address of this machine (any interface), used to recognise requests from itself."""
    addrs = {"127.0.0.1", "::1"}
    try:
        addrs.update(ip for _, ip in _from_ip_command())
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    return addrs
