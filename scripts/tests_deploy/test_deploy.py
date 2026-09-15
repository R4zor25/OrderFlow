import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("deploy", Path(__file__).resolve().parents[1] / "deploy.py")
d = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = d
spec.loader.exec_module(d)

class DeploymentTests(unittest.TestCase):
    def test_stable_old_revision_is_not_success(self):
        state = {"desiredCount": 1, "runningCount": 1, "pendingCount": 0,
                 "taskDefinition": "old:1", "deployments": [{"status": "PRIMARY",
                 "taskDefinition": "old:1", "rolloutState": "COMPLETED"}]}
        with self.assertRaises(d.DeployError):
            d.assert_stable(state, "new:2")

import copy
import json
import tempfile
from unittest.mock import patch

SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
TARGET = d.Target('eu-central-1', '123456789012', 'orderflow', 'orderflow',
                  'arn:aws:iam::123456789012:role/orderflow-github-deploy')


def recipe(service, revision=1):
    return {'taskDefinitionArn': TARGET.task_prefix(service) + str(revision),
            'family': 'orderflow-' + service, 'status': 'ACTIVE', 'revision': revision,
            'executionRoleArn': 'arn:aws:iam::123456789012:role/orderflow-task-execution',
            'taskRoleArn': 'arn:aws:iam::123456789012:role/orderflow-task',
            'networkMode': 'awsvpc', 'requiresCompatibilities': ['FARGATE'],
            'runtimePlatform': {'cpuArchitecture': 'ARM64', 'operatingSystemFamily': 'LINUX'},
            'cpu': '512', 'memory': '1024',
            'containerDefinitions': [{'name': service, 'image': TARGET.repository(service)+':'+SHA,
                                     'environment': [{'name':'FIXTURE', 'value':'private-fixture'}],
                                     'secrets': [{'name':'PASSWORD', 'valueFrom':'fixture-secret-arn'}],
                                     'portMappings':[{'containerPort':8081}],
                                     'logConfiguration':{'logDriver':'awslogs'}}]}


class FakeRunner:
    def __init__(self):
        self.calls = []
        self.images = True
        self.image_error = None
        self.account = TARGET.account
        self.arch = 'arm64'
        self.index = None
        self.zero_service = None
        self.unstable_service = None
        self.fail_update = None
        self.old_after_wait = False
        self.wait_error = None
        self.task_change = None
        self.state_revision = {s: 1 for s in d.SERVICES}

    def run(self, args):
        self.calls.append(args)
        if args[0] == 'docker':
            if '--raw' in args:
                return json.dumps(self.index or {'mediaType':'application/vnd.oci.image.manifest.v1+json'})
            return json.dumps({'architecture':self.arch,'os':'linux'})
        action = args[2]
        if action == 'get-caller-identity': return json.dumps({'Account':self.account})
        if action == 'describe-images':
            if self.image_error: raise d.CommandError(self.image_error)
            if not self.images: raise d.CommandError('ImageNotFoundException')
            return json.dumps({'imageDetails':[{'imageDigest':DIGEST}]})
        if action == 'describe-services':
            name = args[args.index('--services')+1]
            s = name.removeprefix('orderflow-')
            arn = TARGET.task_prefix(s)+str(self.state_revision[s])
            state = {'serviceArn': f'arn:aws:ecs:{TARGET.region}:{TARGET.account}:service/orderflow/{name}',
                     'status':'ACTIVE', 'taskDefinition':arn, 'desiredCount':0 if s==self.zero_service else 1,
                     'runningCount':1, 'pendingCount':0,
                     'deployments':[{'status':'PRIMARY','taskDefinition':arn,
                                     'rolloutState':'IN_PROGRESS' if s==self.unstable_service else 'COMPLETED'}]}
            return json.dumps({'services':[state], 'failures':[]})
        if action == 'describe-task-definition':
            arn = args[args.index('--task-definition')+1]
            s = next(s for s in d.SERVICES if arn.startswith(TARGET.task_prefix(s)))
            task = recipe(s,int(arn.rsplit(':',1)[1]))
            if self.task_change: self.task_change(task)
            return json.dumps({'taskDefinition':task})
        if action == 'register-task-definition':
            task = json.loads(args[args.index('--cli-input-json')+1])
            s = task['family'].removeprefix('orderflow-')
            return json.dumps({'taskDefinition':{'taskDefinitionArn':TARGET.task_prefix(s)+'2'}})
        if action == 'update-service':
            s = args[args.index('--service')+1].removeprefix('orderflow-')
            if s == self.fail_update: raise d.CommandError('InvalidParameterException')
            if not self.old_after_wait:
                self.state_revision[s] = int(args[args.index('--task-definition')+1].rsplit(':',1)[1])
            return '{}'
        if action == 'wait':
            if self.wait_error: raise d.CommandError(self.wait_error)
            return ''
        raise AssertionError(args)

    def mutations(self):
        return [a for a in self.calls if a[0]=='aws' and a[2] in ('register-task-definition','update-service')]


class HelperBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.runner = FakeRunner()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.summary = Path(self.tmp.name)/'summary.md'

    def test_render_preserves_recipe_and_does_not_mutate_input(self):
        original = recipe('order-service')
        original['containerDefinitions'].append({'name':'sidecar','image':'sidecar:v1'})
        before = copy.deepcopy(original)
        rendered = d.render_task(original,'order-service',TARGET.repository('order-service')+'@'+DIGEST,TARGET)
        self.assertEqual(original,before)
        self.assertNotIn('revision',rendered)
        self.assertNotIn('taskDefinitionArn',rendered)
        for key in ('secrets','environment','portMappings','logConfiguration'):
            self.assertEqual(rendered['containerDefinitions'][0][key],original['containerDefinitions'][0][key])
        self.assertEqual(rendered['containerDefinitions'][1]['image'],'sidecar:v1')
        self.assertEqual(rendered['executionRoleArn'],original['executionRoleArn'])
        self.assertEqual(rendered['memory'],'1024')

    def test_only_image_not_found_is_missing(self):
        for code in ('AccessDeniedException','Timeout','RepositoryNotFoundException','CommandFailed'):
            with self.subTest(code=code):
                self.runner.image_error = code
                with self.assertRaises(d.CommandError):
                    d.find_image(TARGET,'order-service',{'imageTag':SHA},self.runner)
        self.runner.image_error = 'ImageNotFoundException'
        self.assertIsNone(d.find_image(TARGET,'order-service',{'imageTag':SHA},self.runner))

    def test_immutable_image_is_reused(self):
        self.assertEqual(d.find_image(TARGET,'order-service',{'imageTag':SHA},self.runner),DIGEST)
        self.assertEqual(self.runner.mutations(),[])

    def test_arm64_single_and_index_with_attestation(self):
        uri = TARGET.repository('order-service')+'@'+DIGEST
        d.assert_arm64(uri,self.runner)
        self.runner.index = {'manifests':[
            {'digest':DIGEST,'platform':{'os':'linux','architecture':'arm64'}},
            {'digest':DIGEST,'platform':{'os':'unknown','architecture':'unknown'}}]}
        d.assert_arm64(uri,self.runner)

    def test_amd64_and_false_descriptor_rejected(self):
        uri = TARGET.repository('order-service')+'@'+DIGEST
        self.runner.index = {'manifests':[{'digest':DIGEST,'platform':{'os':'linux','architecture':'amd64'}}]}
        with self.assertRaises(d.DeployError): d.assert_arm64(uri,self.runner)
        self.runner.index['manifests'][0]['platform']['architecture']='arm64'
        self.runner.arch='amd64'
        with self.assertRaises(d.DeployError): d.assert_arm64(uri,self.runner)

    def test_invalid_task_recipe_is_rejected(self):
        changes = [lambda t:t.update(family='foreign'),lambda t:t.update(taskRoleArn='foreign'),
                   lambda t:t['containerDefinitions'].clear(),
                   lambda t:t['containerDefinitions'].append(copy.deepcopy(t['containerDefinitions'][0])),
                   lambda t:t.update(runtimePlatform={'cpuArchitecture':'X86_64','operatingSystemFamily':'LINUX'})]
        for change in changes:
            task=recipe('order-service'); change(task)
            with self.subTest(task=task):
                with self.assertRaises(d.DeployError):
                    d.render_task(task,'order-service',TARGET.repository('order-service')+'@'+DIGEST,TARGET)

    def test_invalid_target_and_sha_rejected(self):
        for field,value in [('cluster','other'),('account','123'),('project','a;rm'),('role_arn','other')]:
            values=TARGET.__dict__.copy(); values[field]=value
            with self.assertRaises(d.DeployError): d.Target(**values).validate()
        for sha in ('main', 'a'*39, 'A'*40, SHA+'\n', '$(echo x)'):
            with self.assertRaises(d.DeployError): d.require_sha(sha)

    def test_wrong_account_prevents_mutations(self):
        self.runner.account='000000000000'
        with self.assertRaises(d.DeployError): d.deploy_all(TARGET,SHA,self.runner,self.summary)
        self.assertEqual(self.runner.mutations(),[])

    def test_fourth_service_preflight_failure_prevents_every_mutation(self):
        self.runner.zero_service='notification-service'
        with self.assertRaisesRegex(d.DeployError,'activate Terraform'):
            d.deploy_all(TARGET,SHA,self.runner,self.summary)
        self.assertEqual(self.runner.mutations(),[])

    def test_unstable_service_prevents_every_mutation(self):
        self.runner.unstable_service='order-service'
        with self.assertRaises(d.DeployError): d.deploy_all(TARGET,SHA,self.runner,self.summary)
        self.assertEqual(self.runner.mutations(),[])

    def test_verify_images_checks_keycloak_image_too(self):
        images=d.verify_images(TARGET,SHA,self.runner)
        self.assertEqual(set(images),set(d.SERVICES)|{'keycloak'})
        repositories=[a[a.index('--repository-name')+1] for a in self.runner.calls if a[0]=='aws' and a[2]=='describe-images']
        self.assertIn('orderflow/keycloak',repositories)

    def test_deploy_never_updates_keycloak(self):
        d.deploy_all(TARGET,SHA,self.runner,self.summary)
        updated=[a[a.index('--service')+1] for a in self.runner.mutations() if a[2]=='update-service']
        self.assertNotIn('orderflow-keycloak',updated)
        self.assertEqual(updated,['orderflow-'+s for s in d.SERVICES])

    def test_keycloak_is_a_release_image_but_not_a_deployable_service(self):
        self.assertEqual(TARGET.repository('keycloak'),'123456789012.dkr.ecr.eu-central-1.amazonaws.com/orderflow/keycloak')
        with self.assertRaises(d.DeployError):
            TARGET.task_prefix('keycloak')

    def test_successful_deploy_in_dependency_order_and_preserves_counts(self):
        d.deploy_all(TARGET,SHA,self.runner,self.summary)
        updates=[a for a in self.runner.mutations() if a[2]=='update-service']
        self.assertEqual([a[a.index('--service')+1] for a in updates],['orderflow-'+s for s in d.SERVICES])
        self.assertTrue(all('--desired-count' not in a and '--force-new-deployment' in a for a in updates))
        text=self.summary.read_text()
        self.assertEqual(text.count('completed'),4)
        self.assertNotIn('private-fixture',text)
        self.assertNotIn('fixture-secret-arn',text)

    def test_failed_update_stops_later_services_and_records_partial_release(self):
        self.runner.fail_update='inventory-service'
        with self.assertRaises(d.DeployError): d.deploy_all(TARGET,SHA,self.runner,self.summary)
        updates=[a for a in self.runner.mutations() if a[2]=='update-service']
        self.assertEqual(len(updates),2)
        text=self.summary.read_text()
        self.assertIn('completed',text); self.assertIn('failed',text); self.assertIn('not attempted',text)

    def test_timeout_and_circuit_breaker_rollback_are_failures(self):
        for error in ('Timeout',None):
            self.runner=FakeRunner(); self.runner.wait_error=error; self.runner.old_after_wait=error is None
            with self.assertRaises(d.DeployError): d.deploy_all(TARGET,SHA,self.runner,self.summary)
            self.assertEqual(len(self.runner.mutations()),2)

    def test_valid_rollback_does_not_register_or_change_counts(self):
        d.rollback_one(TARGET,'order-service',TARGET.task_prefix('order-service')+'7',self.runner,self.summary)
        self.assertEqual([a[2] for a in self.runner.mutations()],['update-service'])
        self.assertNotIn('--desired-count',self.runner.mutations()[0])

    def test_wrong_rollback_family_inactive_target_and_deleted_image_do_not_update(self):
        for kind in ('family','inactive','missing','foreign-image'):
            self.runner=FakeRunner()
            arn=TARGET.task_prefix('order-service')+'7'
            if kind=='family': arn=TARGET.task_prefix('inventory-service')+'7'
            if kind=='inactive': self.runner.task_change=lambda t:t.update(status='INACTIVE')
            if kind=='missing': self.runner.images=False
            if kind=='foreign-image': self.runner.task_change=lambda t:t['containerDefinitions'][0].update(image='evil/image:tag')
            with self.assertRaises(d.DeployError): d.rollback_one(TARGET,'order-service',arn,self.runner,self.summary)
            self.assertEqual(self.runner.mutations(),[])

    def test_command_errors_do_not_print_raw_credentials(self):
        exc = d.subprocess.CalledProcessError(1,['aws'],output='secret-token',stderr='An error occurred (AccessDeniedException) secret-password')
        with patch.object(d.subprocess,'run',side_effect=exc):
            with self.assertRaises(d.CommandError) as caught: d.CommandRunner().run(['aws'])
        self.assertNotIn('secret',str(caught.exception))

    def test_bad_json_is_a_safe_error(self):
        class InvalidRunner:
            def run(self,args): return 'fixture-secret not json'
        with self.assertRaises(d.DeployError) as caught: d.validate_target(TARGET,InvalidRunner())
        self.assertNotIn('fixture-secret',str(caught.exception))

class DispatchTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(zip(('AWS_REGION','AWS_ACCOUNT_ID','PROJECT_NAME','ECS_CLUSTER','AWS_DEPLOY_ROLE_ARN'),
                            (TARGET.region,TARGET.account,TARGET.project,TARGET.cluster,TARGET.role_arn)))
        self.env.update(GITHUB_REF='refs/heads/main',RELEASE_OPERATION='publish')

    def test_validate_never_calls_external_cli(self):
        with patch.dict(d.os.environ,self.env,clear=True), patch.object(d.sys,'argv',['deploy.py','validate','--sha',SHA]), patch.object(d.subprocess,'run',side_effect=AssertionError('must not call AWS')):
            self.assertEqual(d.main(),0)

    def test_non_main_and_missing_target_fail_before_authentication(self):
        import io
        for change in ('branch','missing'):
            env=self.env.copy()
            if change=='branch': env['GITHUB_REF']='refs/heads/feature'
            else: del env['AWS_ACCOUNT_ID']
            with patch.dict(d.os.environ,env,clear=True), patch.object(d.sys,'argv',['deploy.py','validate','--sha',SHA]), patch.object(d.subprocess,'run',side_effect=AssertionError('must not call AWS')), patch.object(d.sys,'stderr',io.StringIO()):
                self.assertEqual(d.main(),1)

    def test_rollback_validate_requires_project_full_arn(self):
        import io
        env=self.env.copy(); env['RELEASE_OPERATION']='rollback'
        for arn in ('', TARGET.task_prefix('inventory-service')+'7', TARGET.task_prefix('order-service')+'0'):
            with patch.dict(d.os.environ,env,clear=True), patch.object(d.sys,'argv',['deploy.py','validate','--service','order-service','--task-definition',arn]), patch.object(d.sys,'stderr',io.StringIO()):
                self.assertEqual(d.main(),1)

    def test_rollback_validate_rejects_keycloak(self):
        import io
        env=self.env.copy(); env['RELEASE_OPERATION']='rollback'
        with patch.dict(d.os.environ,env,clear=True), patch.object(d.sys,'argv',['deploy.py','validate','--service','keycloak','--task-definition','arn:aws:ecs:eu-central-1:123456789012:task-definition/orderflow-keycloak:3']), patch.object(d.sys,'stderr',io.StringIO()):
            self.assertEqual(d.main(),1)

    def test_invalid_response_shape_is_a_safe_deployment_error(self):
        class UnexpectedRunner:
            def run(self,args): return '["private-fixture"]'
        with self.assertRaises(d.DeployError):
            d.validate_target(TARGET,UnexpectedRunner())
