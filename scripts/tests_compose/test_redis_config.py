"""Static check of the local Redis eviction settings."""

import re
import unittest
from pathlib import Path

COMPOSE = Path(__file__).resolve().parents[2] / 'infra/docker-compose.yaml'


class RedisConfigTest(unittest.TestCase):
    def test_redis_is_bounded_and_evicts_only_expiring_keys(self):
        service = re.search(r'\n  redis:\n(.*?)(?=\n  [a-z-]+:\n)', COMPOSE.read_text(), re.S)
        self.assertIsNotNone(service, 'redis service is missing')
        self.assertIn('"--maxmemory", "256mb"', service.group(1))
        self.assertIn('"--maxmemory-policy", "volatile-lru"', service.group(1))
        self.assertIn('"--save", ""', service.group(1))
        # Without snapshots nothing is written to disk; a mounted volume would only reload a stale dump.rdb.
        self.assertNotIn('volumes:', service.group(1))
