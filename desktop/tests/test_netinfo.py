"""LAN address detection for the pairing QR code."""

from __future__ import annotations

from audiocool_desktop import netinfo


def test_private_ranges():
    for ip in ("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254"):
        assert netinfo.is_private_lan(ip), ip
    for ip in ("172.32.0.1", "8.8.8.8", "127.0.0.1", "169.254.1.1", "::1", "nonsense"):
        assert not netinfo.is_private_lan(ip), ip


def test_only_real_adapters_count(monkeypatch):
    monkeypatch.delenv("AUDIOCOOL_LAN_IPS", raising=False)
    pairs = [
        ("lo", "127.0.0.1"), ("lo", "10.255.255.254"),  # WSL's DNS tunnel address sits on lo
        ("docker0", "172.17.0.1"), ("br-fd077ee18b11", "172.18.0.1"), ("cni0", "10.42.0.1"), ("flannel.1", "10.42.0.0"),
        ("eth4", "192.168.23.160"), ("eth1", "10.1.2.3"), ("eth2", "8.8.4.4"),
    ]
    monkeypatch.setattr(netinfo, "_from_ip_command", lambda: pairs)
    monkeypatch.setattr(netinfo, "_default_route_ifname", lambda: "eth4")
    monkeypatch.setattr(netinfo, "_is_physical", lambda name: name.startswith("eth"))
    assert netinfo.lan_addresses() == ["192.168.23.160", "10.1.2.3"]  # default route first


def test_falls_back_to_the_route_source(monkeypatch):
    monkeypatch.delenv("AUDIOCOOL_LAN_IPS", raising=False)
    monkeypatch.setattr(netinfo, "_from_ip_command", lambda: [("docker0", "172.17.0.1")])
    monkeypatch.setattr(netinfo, "_route_source_ip", lambda: "192.168.0.42")
    assert netinfo.lan_addresses() == ["192.168.0.42"]


def test_virtual_interface_names_are_skipped():
    for name in ("docker0", "br-123", "veth9", "virbr0", "cni0", "flannel.1", "tailscale0", "wg0", "lo"):
        assert not netinfo._is_physical(name), name
