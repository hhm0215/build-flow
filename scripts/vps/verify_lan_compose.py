"""Fail if the LAN Compose stack exposes anything beyond the frontend on one private IPv4."""

import ipaddress
import json
import sys

from verify_public_compose import verify_service_isolation


def private_lan_address(value: str) -> ipaddress.IPv4Address:
    try:
        address = ipaddress.IPv4Address(value)
    except ValueError as error:
        raise ValueError(f"LAN bind address must be one IPv4 address: {value!r}") from error
    if not address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified:
        raise ValueError(f"LAN bind address must be a private LAN IPv4, not {address}")
    return address


def verify(config: dict) -> str:
    services = config["services"]
    if "frontend" not in services or "gateway-server" not in services:
        raise ValueError("frontend or gateway is missing")

    lan_bindings = []
    for service_name, service in services.items():
        verify_service_isolation(service_name, service, config.get("volumes", {}))
        for port in service.get("ports", []):
            published = str(port["published"])
            host_ip = port.get("host_ip", "")
            if host_ip == "127.0.0.1":
                continue
            if service_name != "frontend":
                raise ValueError(f"{service_name}:{published} is exposed on {host_ip or 'all interfaces'}")
            if published != "3000" or str(port.get("target")) != "80":
                raise ValueError(f"frontend LAN port must be 3000->80, got {published}->{port.get('target')}")
            lan_bindings.append(str(private_lan_address(host_ip)))

    if len(lan_bindings) != 1:
        raise ValueError(f"expected exactly one frontend LAN binding, got {lan_bindings}")
    lan_address = lan_bindings[0]

    origins = services["gateway-server"].get("environment", {}).get("CORS_ALLOWED_ORIGINS", "").split(",")
    expected = {f"http://{lan_address}:3000", "http://localhost:3000"}
    if set(origins) != expected or len(origins) != len(expected):
        raise ValueError("Gateway CORS must contain only the LAN frontend and localhost origins")
    return lan_address


if __name__ == "__main__":
    address = verify(json.load(sys.stdin))
    print(f"LAN Compose verified: only frontend is published on {address}:3000; other ports stay on 127.0.0.1")
