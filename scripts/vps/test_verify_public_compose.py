import copy
import unittest

from verify_public_compose import verify


def valid_config():
    return {
        "services": {
            "caddy": {
                "ports": [{"published": "80"}, {"published": "443"}],
                "environment": {"PUBLIC_HOST": "example.org"},
                "volumes": [
                    {"target": "/etc/caddy/Caddyfile"},
                    {"target": "/data"},
                    {"target": "/config"},
                ],
            },
            "frontend": {"ports": [{"published": "3000", "host_ip": "127.0.0.1"}]},
            "mysql": {"ports": [{"published": "3306", "host_ip": "127.0.0.1"}]},
            "gateway-server": {
                "ports": [{"published": "8080", "host_ip": "127.0.0.1"}],
                "environment": {"CORS_ALLOWED_ORIGINS": "https://example.org,http://localhost:13000"},
            },
        }
    }


class PublicComposeVerifierTest(unittest.TestCase):
    def test_accepts_web_only_public_ports(self):
        verify(valid_config())

    def test_rejects_public_database(self):
        config = valid_config()
        config["services"]["mysql"]["ports"][0]["host_ip"] = "0.0.0.0"
        with self.assertRaisesRegex(ValueError, "mysql:3306"):
            verify(config)

    def test_rejects_host_networking_without_published_ports(self):
        config = valid_config()
        config["services"]["mysql"] = {"network_mode": "host"}
        with self.assertRaisesRegex(ValueError, "mysql uses host networking"):
            verify(config)

    def test_rejects_privileged_service(self):
        config = valid_config()
        config["services"]["frontend"]["privileged"] = True
        with self.assertRaisesRegex(ValueError, "frontend is privileged"):
            verify(config)

    def test_rejects_docker_socket_mounts_even_read_only_or_renamed(self):
        mounts = [
            {"type": "bind", "source": "/var/run/docker.sock", "target": "/socket", "read_only": True},
            {"type": "bind", "source": "/run/docker.sock", "target": "/socket"},
            {"type": "bind", "source": "/custom/socket", "target": "/var/run/docker.sock"},
            {"type": "bind", "source": "/var/run/../run/docker.sock", "target": "/socket"},
        ]
        for mount in mounts:
            with self.subTest(mount=mount):
                config = valid_config()
                config["services"]["frontend"]["volumes"] = [mount]
                with self.assertRaisesRegex(ValueError, "frontend mounts the Docker socket"):
                    verify(config)

    def test_rejects_host_socket_parent_directory_mounts(self):
        for source in ("/", "/var", "/var/run/", "/run", "/var/run/../run"):
            with self.subTest(source=source):
                config = valid_config()
                config["services"]["frontend"]["volumes"] = [
                    {"type": "bind", "source": source, "target": "/host", "read_only": True}
                ]
                with self.assertRaisesRegex(ValueError, "frontend mounts the Docker socket"):
                    verify(config)

    def test_accepts_unprivileged_service_and_regular_mounts(self):
        config = valid_config()
        config["services"]["frontend"].update({
            "privileged": False,
            "volumes": [
                {"type": "bind", "source": "/opt/buildflow/frontend/nginx.conf", "target": "/etc/nginx/conf.d/default.conf", "read_only": True},
                {"type": "volume", "source": "frontend_data", "target": "/data"},
            ],
        })
        verify(config)

    def test_rejects_named_volume_binding_socket_or_parent_directory(self):
        for device in ("/var/run/docker.sock", "/run", "/var/run"):
            with self.subTest(device=device):
                config = valid_config()
                config["volumes"] = {
                    "host_socket": {"driver": "local", "driver_opts": {"type": "none", "o": "bind", "device": device}}
                }
                config["services"]["frontend"]["volumes"] = [
                    {"type": "volume", "source": "host_socket", "target": "/data", "read_only": True}
                ]
                with self.assertRaisesRegex(ValueError, "frontend mounts the Docker socket"):
                    verify(config)

    def test_rejects_extra_public_port(self):
        config = valid_config()
        config["services"]["caddy"]["ports"].append({"published": "2019"})
        with self.assertRaisesRegex(ValueError, "unexpected public ports"):
            verify(config)

    def test_rejects_missing_https_origin(self):
        config = copy.deepcopy(valid_config())
        config["services"]["gateway-server"]["environment"]["CORS_ALLOWED_ORIGINS"] = "http://localhost:13000"
        with self.assertRaisesRegex(ValueError, "HTTPS origin"):
            verify(config)

    def test_accepts_real_host_without_ci_literal(self):
        config = valid_config()
        config["services"]["caddy"]["environment"]["PUBLIC_HOST"] = "pilot.example.net"
        config["services"]["gateway-server"]["environment"]["CORS_ALLOWED_ORIGINS"] = (
            "https://pilot.example.net,http://localhost:13000"
        )
        verify(config)


if __name__ == "__main__":
    unittest.main()
