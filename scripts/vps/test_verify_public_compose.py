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
