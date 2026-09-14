import base64
import json
import pathlib
import shutil
import subprocess
import tempfile
import time
import unittest
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]


def claims(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))


@unittest.skipUnless(shutil.which('docker'), 'Docker is required')
class KeycloakRealmTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='orderflow-keycloak-')
        cls.project = 'orderflow-keycloak-' + uuid.uuid4().hex[:10]
        env = pathlib.Path(cls.temporary.name) / 'test.env'
        env.write_text('POSTGRES_PASSWORD=unused\nKC_BOOTSTRAP_ADMIN_USERNAME=bootstrap\n'
                       'KC_BOOTSTRAP_ADMIN_PASSWORD=bootstrap-' + uuid.uuid4().hex + '\n'
                       'ORDER_SERVICE_CLIENT_SECRET=secret-test\nDEMO_USER_PASSWORD=password-test\n')
        override = pathlib.Path(cls.temporary.name) / 'ports.yaml'
        override.write_text('services:\n  keycloak:\n    ports: !override ["127.0.0.1::8080"]\n')
        cls.compose = ['docker', 'compose', '--env-file', str(env), '-p', cls.project,
                       '-f', str(ROOT / 'infra/docker-compose.yaml'), '-f', str(override)]
        subprocess.run([*cls.compose, 'up', '-d', '--wait', '--wait-timeout', '180', 'keycloak'],
                       check=True, cwd=ROOT)
        port = subprocess.check_output([*cls.compose, 'port', 'keycloak', '8080'], text=True).strip().rsplit(':', 1)[1]
        cls.token_url = f'http://127.0.0.1:{port}/realms/orderflow/protocol/openid-connect/token'

    @classmethod
    def tearDownClass(cls):
        subprocess.run([*cls.compose, 'down', '-v', '--remove-orphans'], cwd=ROOT, check=False)
        cls.temporary.cleanup()

    def token(self, **form):
        data = urllib.parse.urlencode(form).encode()
        with urllib.request.urlopen(urllib.request.Request(self.token_url, data=data), timeout=10) as response:
            return json.load(response)['access_token']

    def password_token(self, username):
        return claims(self.token(grant_type='password', client_id='orderflow-cli',
                                 username=username, password='password-test'))

    def test_customer_token_carries_role_customer_id_subject_and_fixed_issuer(self):
        token = self.password_token('standard')
        self.assertEqual('http://localhost:8180/realms/orderflow', token['iss'])
        self.assertIn('CUSTOMER', token['roles'])
        self.assertNotIn('ADMIN', token['roles'])
        self.assertEqual('33333333-3333-4333-8333-333333333333', token['customer_id'])
        self.assertTrue(token['sub'])

    def test_every_customer_maps_to_its_seed_customer(self):
        expected = {'consumer.one': '11111111-1111-4111-8111-111111111111',
                    'consumer.two': '22222222-2222-4222-8222-222222222222',
                    'gold': '44444444-4444-4444-8444-444444444444'}
        for username, customer in expected.items():
            self.assertEqual(customer, self.password_token(username)['customer_id'], username)

    def test_admin_token_has_admin_role_and_no_customer_id(self):
        token = self.password_token('admin')
        self.assertIn('ADMIN', token['roles'])
        self.assertNotIn('customer_id', token)

    def test_service_token_has_pricing_scope_without_customer_role(self):
        token = claims(self.token(grant_type='client_credentials', client_id='order-service',
                                  client_secret='secret-test'))
        self.assertIn('pricing.quote', token['scope'].split())
        self.assertNotIn('CUSTOMER', token.get('roles', []))

    def test_wrong_client_secret_is_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.token(grant_type='client_credentials', client_id='order-service', client_secret='wrong')
        self.assertEqual(401, error.exception.code)

    def test_cli_client_cannot_use_client_credentials(self):
        with self.assertRaises(urllib.error.HTTPError):
            self.token(grant_type='client_credentials', client_id='orderflow-cli')


if __name__ == '__main__':
    unittest.main()
