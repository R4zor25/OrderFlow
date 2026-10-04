import copy
import json
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read_workflow():
    code = 'require "yaml"; require "json"; x=YAML.load_file(ARGV[0]); x["on"]=x.delete(true) if x.key?(true); puts JSON.generate(x)'
    return json.loads(subprocess.check_output(['ruby','-e',code,str(ROOT/'.github/workflows/deploy.yml')],text=True))


def check_contract(w):
    assert w['permissions']=={'contents':'read'}
    assert w['concurrency']['cancel-in-progress'] is False
    assert 'AWS_ACCOUNT_ID' in w['concurrency']['group']
    inputs=w['on']['workflow_dispatch']['inputs']
    assert inputs['operation']['options']==['publish','deploy','rollback']
    jobs=w['jobs']; release=jobs['release']
    assert release['permissions']=={'contents':'read','id-token':'write'}
    assert set(release['needs'])=={'validate','verify','publish'}
    assert "github.ref == 'refs/heads/main'" in release['if']
    assert "needs.validate.result == 'success'" in release['if']
    assert "needs.verify.result == 'success'" in release['if']
    assert "inputs.operation == 'rollback'" in release['if']
    assert "needs.verify.result == 'skipped'" in release['if']
    assert "needs.publish.result == 'success'" in release['if']
    assert "needs.publish.result == 'skipped'" in release['if']
    assert "inputs.operation != 'rollback'" in jobs['verify']['if']
    assert jobs['verify']['needs']=='validate'
    for name,job in jobs.items():
        assert 'environment' not in job
        if name not in ('release','publish'): assert job.get('permissions',{}).get('id-token')!='write'
        for step in job['steps']:
            if 'uses' in step: assert re.search(r'@[0-9a-f]{40}$',step['uses'])
    deploy=next(s for s in release['steps'] if s.get('id')=='deploy')
    assert "inputs.operation == 'deploy'" in deploy['if']
    assert "verify-images" in next(s['run'] for s in release['steps'] if s.get('id')=='images-ready')
    assert release['steps'].index(deploy)>next(i for i,s in enumerate(release['steps']) if s.get('id')=='images-ready')
    assert not any(s.get('uses','').startswith('docker/build-push-action@') for s in release['steps'])
    publish=jobs['publish']
    assert publish['permissions']=={'contents':'read','id-token':'write'}
    assert set(publish['needs'])=={'validate','verify'}
    assert "inputs.operation != 'rollback'" in publish['if']
    assert "github.ref == 'refs/heads/main'" in publish['if']
    assert 'always()' not in publish['if']
    assert publish['strategy']['fail-fast'] is False
    images={i['name']:i['context'] for i in publish['strategy']['matrix']['include']}
    assert images=={'catalog-pricing-service':'services/catalog-pricing-service',
                    'inventory-service':'services/inventory-service',
                    'order-service':'services/order-service',
                    'notification-service':'services/notification-service',
                    'keycloak':'infra/keycloak'}
    assert any('image-status --service "$IMAGE_NAME"' in s.get('run','') for s in publish['steps'])
    build=next(s for s in publish['steps'] if s.get('uses','').startswith('docker/build-push-action@'))
    assert 'exists' in build['if'] and "'false'" in build['if']
    assert build['with']['platforms']=='linux/arm64' and build['with']['push'] is True
    creds=next(s for s in publish['steps'] if s.get('uses','').startswith('aws-actions/configure-aws-credentials@'))
    assert creds['with']['role-duration-seconds']<=1800
    verify=jobs['verify']
    assert len(verify['strategy']['matrix']['service'])==4
    assert any('./mvnw -B -ntp -Pcoverage verify' in s.get('run','') for s in verify['steps'])


class WorkflowContractTests(unittest.TestCase):
    def test_workflow_contract(self): check_contract(read_workflow())

    def test_unsafe_workflow_mutations_are_rejected(self):
        original=read_workflow()
        mutations=[lambda w:w['jobs']['release'].update(needs=['validate']),
                   lambda w:w['jobs']['release']['permissions'].update({'id-token':'read'}),
                   lambda w:w['jobs']['release'].update(environment='production'),
                   lambda w:w['jobs']['release'].update({'if':"always()"}),
                   lambda w:w['jobs']['verify'].update({'if':"true"}),
                   lambda w:w['jobs']['release'].update(needs=['validate','verify']),
                   lambda w:w['jobs']['publish'].update(environment='production'),
                   lambda w:w['jobs']['publish'].update({'if':"always()"}),
                   lambda w:w['jobs']['publish']['strategy']['matrix']['include'].pop(),
                   lambda w:next(s for s in w['jobs']['release']['steps'] if s.get('id')=='deploy').update({'if':"inputs.operation == 'publish'"})]
        for mutate in mutations:
            w=copy.deepcopy(original); mutate(w)
            with self.assertRaises(AssertionError): check_contract(w)
