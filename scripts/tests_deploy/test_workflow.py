import copy
import json
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read_workflow():
    code = ('require "yaml"; require "json"; x=YAML.load_file(ARGV[0]); '
            'x["on"]=x.delete(true) if x.key?(true); puts JSON.generate(x)')
    return json.loads(subprocess.check_output(
        ['ruby', '-e', code, str(ROOT / '.github/workflows/deploy.yml')], text=True))


def step(job, step_id):
    return next(s for s in job['steps'] if s.get('id') == step_id)


def action_step(job, action):
    return next(s for s in job['steps'] if s.get('uses', '').startswith(action + '@'))


def check_workflow_settings(w):
    assert w['permissions'] == {'contents': 'read'}
    assert w['concurrency']['cancel-in-progress'] is False
    assert 'AWS_ACCOUNT_ID' in w['concurrency']['group']
    inputs = w['on']['workflow_dispatch']['inputs']
    assert inputs['operation']['options'] == ['publish', 'deploy', 'rollback']
    for name, job in w['jobs'].items():
        assert 'environment' not in job
        if name not in ('release', 'publish'):
            assert job.get('permissions', {}).get('id-token') != 'write'
        for s in job['steps']:
            if 'uses' in s:
                assert re.search(r'@[0-9a-f]{40}$', s['uses'])


def check_release_job(w):
    release = w['jobs']['release']
    assert release['permissions'] == {'contents': 'read', 'id-token': 'write'}
    assert set(release['needs']) == {'validate', 'verify', 'publish'}
    assert "github.ref == 'refs/heads/main'" in release['if']
    assert "needs.validate.result == 'success'" in release['if']
    assert "needs.verify.result == 'success'" in release['if']
    assert "inputs.operation == 'rollback'" in release['if']
    assert "needs.verify.result == 'skipped'" in release['if']
    assert "needs.publish.result == 'success'" in release['if']
    assert "needs.publish.result == 'skipped'" in release['if']
    deploy = step(release, 'deploy')
    images_ready = step(release, 'images-ready')
    assert "inputs.operation == 'deploy'" in deploy['if']
    assert "verify-images" in images_ready['run']
    assert release['steps'].index(deploy) > release['steps'].index(images_ready)
    assert not any(s.get('uses', '').startswith('docker/build-push-action@')
                   for s in release['steps'])


def check_publish_job(w):
    publish = w['jobs']['publish']
    assert publish['permissions'] == {'contents': 'read', 'id-token': 'write'}
    assert set(publish['needs']) == {'validate', 'verify'}
    assert "inputs.operation != 'rollback'" in publish['if']
    assert "github.ref == 'refs/heads/main'" in publish['if']
    assert 'always()' not in publish['if']
    assert publish['strategy']['fail-fast'] is False
    images = {i['name']: i['context'] for i in publish['strategy']['matrix']['include']}
    assert images == {'catalog-pricing-service': 'services/catalog-pricing-service',
                      'inventory-service': 'services/inventory-service',
                      'order-service': 'services/order-service',
                      'notification-service': 'services/notification-service',
                      'keycloak': 'infra/keycloak'}
    assert any('image-status --service "$IMAGE_NAME"' in s.get('run', '')
               for s in publish['steps'])
    build = action_step(publish, 'docker/build-push-action')
    assert 'exists' in build['if'] and "'false'" in build['if']
    assert build['with']['platforms'] == 'linux/arm64' and build['with']['push'] is True
    credentials = action_step(publish, 'aws-actions/configure-aws-credentials')
    assert credentials['with']['role-duration-seconds'] <= 1800


def check_verify_job(w):
    verify = w['jobs']['verify']
    assert "inputs.operation != 'rollback'" in verify['if']
    assert verify['needs'] == 'validate'
    assert len(verify['strategy']['matrix']['service']) == 4
    assert any('./mvnw -B -ntp -Pcoverage verify' in s.get('run', '') for s in verify['steps'])


def check_contract(w):
    check_workflow_settings(w)
    check_release_job(w)
    check_publish_job(w)
    check_verify_job(w)


def update(job, **values):
    return lambda w: w['jobs'][job].update(values)


UNSAFE_MUTATIONS = {
    'release runs without verify and publish': update('release', needs=['validate']),
    'release token loses the id-token write': lambda w: w['jobs']['release']['permissions'].update(
        {'id-token': 'read'}),
    'release gets an environment': update('release', environment='production'),
    'release runs whatever happened before': update('release', **{'if': 'always()'}),
    'verify ignores the operation': update('verify', **{'if': 'true'}),
    'release runs without publish': update('release', needs=['validate', 'verify']),
    'publish gets an environment': update('publish', environment='production'),
    'publish runs whatever happened before': update('publish', **{'if': 'always()'}),
    'publish skips an image':
        lambda w: w['jobs']['publish']['strategy']['matrix']['include'].pop(),
    'deploy runs on publish': lambda w: step(w['jobs']['release'], 'deploy').update(
        {'if': "inputs.operation == 'publish'"}),
}


class WorkflowContractTests(unittest.TestCase):
    def test_workflow_contract(self):
        check_contract(read_workflow())

    def test_unsafe_workflow_mutations_are_rejected(self):
        original = read_workflow()
        for name, mutate in UNSAFE_MUTATIONS.items():
            with self.subTest(name):
                w = copy.deepcopy(original)
                mutate(w)
                with self.assertRaises(AssertionError):
                    check_contract(w)
