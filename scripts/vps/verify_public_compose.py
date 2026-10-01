"""Fail CI if the public VPS Compose stack exposes a non-web service."""

import json
import sys


def verify(config: dict) -> None:
    services = config["services"]
    if "caddy" not in services or "frontend" not in services:
        raise ValueError("public proxy or frontend is missing")

    public_ports = set()
    for service_name, service in services.items():
        for port in service.get("ports", []):
            published = str(port["published"])
            host_ip = port.get("host_ip", "")
            if service_name == "caddy":
                if host_ip not in ("", "0.0.0.0", "::"):
                    raise ValueError(f"Caddy port {published} is not publicly bound")
                public_ports.add(published)
            elif host_ip != "127.0.0.1":
                raise ValueError(f"{service_name}:{published} is exposed on {host_ip or 'all interfaces'}")

    if public_ports != {"80", "443"}:
        raise ValueError(f"unexpected public ports: {sorted(public_ports)}")

    public_host = services["caddy"]["environment"].get("PUBLIC_HOST")
    if not public_host:
        raise ValueError("Caddy public hostname is missing")

    gateway_origins = services["gateway-server"]["environment"]["CORS_ALLOWED_ORIGINS"].split(",")
    if f"https://{public_host}" not in gateway_origins:
        raise ValueError("public HTTPS origin is missing from Gateway CORS")

    mounts = {mount["target"] for mount in services["caddy"].get("volumes", [])}
    if not {"/etc/caddy/Caddyfile", "/data", "/config"}.issubset(mounts):
        raise ValueError("Caddy config or certificate storage is not mounted")


if __name__ == "__main__":
    verify(json.load(sys.stdin))
    print("public Compose ports, CORS, and Caddy persistence verified")
