"""Build the production Keycloak image and run it against a disposable PostgreSQL."""

import base64
import http.client
import json
import shutil
import subprocess
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def docker(*args, check=True):
    return subprocess.run(['docker', *args], check=check, capture_output=True, text=True).stdout.strip()


def claims(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))


@unittest.skipUnless(shutil.which('docker'), 'Docker is required')
class KeycloakImageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        suffix = uuid.uuid4().hex[:10]
        cls.image = 'orderflow-keycloak-test:' + suffix
        cls.network = 'orderflow-keycloak-' + suffix
        cls.postgres = 'orderflow-keycloak-pg-' + suffix
        cls.keycloak = 'orderflow-keycloak-kc-' + suffix
        cls.created = []
        docker('build', '-t', cls.image, str(ROOT / 'infra/keycloak'))
        docker('network', 'create', cls.network)
        cls.created.append(('network', cls.network))
        docker('run', '-d', '--name', cls.postgres, '--network', cls.network, '--network-alias', 'postgres',
               '-e', 'POSTGRES_DB=orderflow', '-e', 'POSTGRES_USER=orderflow', '-e', 'POSTGRES_PASSWORD=pg-test',
               'postgres:17.11-bookworm')
        cls.created.append(('container', cls.postgres))
        cls.wait_for_postgres()
        docker('run', '-d', '--name', cls.keycloak, '--network', cls.network,
               '-p', '127.0.0.1::8080', '-p', '127.0.0.1::9000',
               '-e', 'KC_DB_URL=jdbc:postgresql://postgres:5432/orderflow',
               '-e', 'KC_DB_USERNAME=orderflow', '-e', 'KC_DB_PASSWORD=pg-test',
               '-e', 'KC_HTTP_ENABLED=true', '-e', 'KC_PROXY_HEADERS=xforwarded',
               '-e', 'KC_HOSTNAME=http://keycloak.test', '-e', 'KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true',
               '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=bootstrap-test',
               '-e', 'ORDER_SERVICE_CLIENT_SECRET=secret-test', '-e', 'DEMO_USER_PASSWORD=password-test',
               cls.image)
        cls.created.append(('container', cls.keycloak))
        cls.http_port = docker('port', cls.keycloak, '8080').splitlines()[0].rsplit(':', 1)[1]
        cls.management_port = docker('port', cls.keycloak, '9000').splitlines()[0].rsplit(':', 1)[1]
        cls.wait_for_keycloak()

    @classmethod
    def tearDownClass(cls):
        for kind, name in reversed(cls.created):
            if kind == 'container':
                docker('rm', '-f', '-v', name, check=False)
            else:
                docker('network', 'rm', name, check=False)
        docker('image', 'rm', '-f', cls.image, check=False)

    @classmethod
    def wait_for_postgres(cls):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if subprocess.run(['docker', 'exec', cls.postgres, 'pg_isready', '-U', 'orderflow', '-d', 'orderflow'],
                              capture_output=True).returncode == 0:
                return
            time.sleep(1)
        raise AssertionError('PostgreSQL did not become ready')

    @classmethod
    def wait_for_keycloak(cls):
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{cls.management_port}/health/ready', timeout=5) as response:
                    if json.load(response).get('status') == 'UP':
                        return
            except urllib.error.HTTPError as starting:
                starting.close()
            except (urllib.error.URLError, ConnectionError, ValueError):
                pass
            time.sleep(2)
        logs = subprocess.run(['docker', 'logs', '--tail', '80', cls.keycloak], capture_output=True, text=True)
        raise AssertionError('Keycloak did not become ready:\n' + logs.stdout + logs.stderr)

    def token(self, **form):
        url = f'http://127.0.0.1:{self.http_port}/realms/orderflow/protocol/openid-connect/token'
        request = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            return claims(json.load(response)['access_token'])

    def test_production_image_creates_tables_and_imports_realm(self):
        count = docker('exec', self.postgres, 'psql', '-U', 'orderflow', '-d', 'orderflow', '-tAc',
                       "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'realm'")
        self.assertEqual('1', count)
        realms = docker('exec', self.postgres, 'psql', '-U', 'orderflow', '-d', 'orderflow', '-tAc',
                        "SELECT name FROM public.realm WHERE name = 'orderflow'")
        self.assertEqual('orderflow', realms)

    def test_tokens_use_configured_hostname_issuer(self):
        token = self.token(grant_type='password', client_id='orderflow-cli',
                           username='standard', password='password-test')
        self.assertEqual('http://keycloak.test/realms/orderflow', token['iss'])
        self.assertEqual('33333333-3333-4333-8333-333333333333', token['customer_id'])
        self.assertIn('CUSTOMER', token['roles'])

    def test_service_client_gets_pricing_scope(self):
        token = self.token(grant_type='client_credentials', client_id='order-service', client_secret='secret-test')
        self.assertIn('pricing.quote', token['scope'].split())

    def test_admin_console_route_responds(self):
        connection = http.client.HTTPConnection('127.0.0.1', int(self.http_port), timeout=10)
        try:
            connection.request('GET', '/admin/')
            status = connection.getresponse().status
        finally:
            connection.close()
        self.assertIn(status, (200, 302, 303))


if __name__ == '__main__':
    unittest.main()
