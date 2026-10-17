import copy
import unittest

from verify_lan_compose import verify


def valid_config():
    return {
        "services": {
            "frontend": {"ports": [
                {"published": "3000", "target": 80, "host_ip": "127.0.0.1"},
                {"published": "3000", "target": 80, "host_ip": "192.168.0.20"},
            ]},
            "mysql": {"ports": [{"published": "3306", "target": 3306, "host_ip": "127.0.0.1"}]},
            "gateway-server": {
                "ports": [{"published": "8080", "target": 8080, "host_ip": "127.0.0.1"}],
                "environment": {"CORS_ALLOWED_ORIGINS": "http://192.168.0.20:3000,http://localhost:3000"},
            },
        }
    }


class LanComposeVerifierTest(unittest.TestCase):
    def test_accepts_frontend_only_on_private_ip(self):
        self.assertEqual("192.168.0.20", verify(valid_config()))

    def test_rejects_internal_service_on_lan(self):
        config = valid_config()
        config["services"]["mysql"]["ports"][0]["host_ip"] = "192.168.0.20"
        with self.assertRaisesRegex(ValueError, "mysql:3306"):
            verify(config)

    def test_rejects_all_interfaces_and_public_addresses(self):
        for address in ("0.0.0.0", "", "203.0.113.10", "169.254.1.5"):
            config = valid_config()
            config["services"]["frontend"]["ports"][1]["host_ip"] = address
            with self.subTest(address=address), self.assertRaises(ValueError):
                verify(config)

    def test_rejects_wrong_frontend_port_or_extra_binding(self):
        config = valid_config()
        config["services"]["frontend"]["ports"][1]["published"] = "80"
        with self.assertRaisesRegex(ValueError, "3000->80"):
            verify(config)
        config = valid_config()
        config["services"]["frontend"]["ports"].append(copy.deepcopy(config["services"]["frontend"]["ports"][1]))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify(config)

    def test_rejects_cors_that_allows_other_origins(self):
        config = valid_config()
        config["services"]["gateway-server"]["environment"]["CORS_ALLOWED_ORIGINS"] += ",http://evil.example"
        with self.assertRaisesRegex(ValueError, "CORS"):
            verify(config)

    def test_rejects_docker_socket_mount(self):
        config = valid_config()
        config["services"]["frontend"]["volumes"] = [
            {"type": "bind", "source": "/var/run/docker.sock", "target": "/var/run/docker.sock"}
        ]
        with self.assertRaisesRegex(ValueError, "Docker socket"):
            verify(config)


if __name__ == "__main__":
    unittest.main()
