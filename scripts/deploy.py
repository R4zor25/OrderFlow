#!/usr/bin/env python3
"""Validate and update the four ECS applications; never changes infrastructure counts."""
import argparse
import copy
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

SERVICES = ("catalog-pricing-service", "inventory-service", "order-service", "notification-service")
# Keycloak is published with each release but run by Terraform, never deployed by this script.
IMAGES = SERVICES + ("keycloak",)
REGISTER_FIELDS = ("family", "taskRoleArn", "executionRoleArn", "networkMode", "containerDefinitions",
                   "volumes", "placementConstraints", "requiresCompatibilities", "cpu", "memory",
                   "pidMode", "ipcMode", "proxyConfiguration", "inferenceAccelerators",
                   "ephemeralStorage", "runtimePlatform", "enableFaultInjection")


class DeployError(Exception):
    pass


class CommandError(DeployError):
    def __init__(self, code):
        self.code = code
        super().__init__(f"External command failed ({code}); inspect AWS deployment status.")


class CommandRunner:
    def run(self, args):
        try:
            return subprocess.run(args, check=True, capture_output=True, text=True,
                                  timeout=900).stdout
        except subprocess.TimeoutExpired:
            raise CommandError("Timeout") from None
        except subprocess.CalledProcessError as exc:
            match = re.search(r"An error occurred \(([A-Za-z0-9]+)\)", exc.stderr or "")
            raise CommandError(match[1] if match else "CommandFailed") from None
        except OSError:
            raise CommandError("UnavailableCommand") from None


@dataclass(frozen=True)
class Target:
    region: str
    account: str
    project: str
    cluster: str
    role_arn: str

    @classmethod
    def from_env(cls):
        names = ("AWS_REGION", "AWS_ACCOUNT_ID", "PROJECT_NAME", "ECS_CLUSTER", "AWS_DEPLOY_ROLE_ARN")
        if any(not os.environ.get(name) for name in names):
            raise DeployError("Set all five deployment repository variables before dispatch.")
        target = cls(*(os.environ[name] for name in names))
        target.validate()
        return target

    def validate(self):
        if (not re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-[0-9]+", self.region)
                or not re.fullmatch(r"[0-9]{12}", self.account)
                or not re.fullmatch(r"[a-z][a-z0-9]{1,19}", self.project)
                or self.cluster != self.project
                or self.role_arn != f"arn:aws:iam::{self.account}:role/{self.project}-github-deploy"):
            raise DeployError("Deployment variables do not identify the expected project target.")

    def repository(self, image):
        require_image(image)
        return f"{self.account}.dkr.ecr.{self.region}.amazonaws.com/{self.project}/{image}"

    def task_prefix(self, service):
        require_service(service)
        return f"arn:aws:ecs:{self.region}:{self.account}:task-definition/{self.project}-{service}:"


def require_service(service):
    if service not in SERVICES:
        raise DeployError("Choose one of the four application services.")


def require_image(image):
    if image not in IMAGES:
        raise DeployError("Choose one of the five release images.")


def require_sha(sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise DeployError("Image tag must be a full lowercase Git commit SHA.")


def aws(target, runner, service, action, *args):
    raw = runner.run(["aws", service, action, "--region", target.region, *args, "--output", "json"])
    if action == "wait":
        return None
    try:
        response = json.loads(raw)
        if not isinstance(response, dict):
            raise ValueError("Expected JSON object")
        return response
    except (ValueError, TypeError):
        raise DeployError("AWS returned an invalid JSON response.") from None


def validate_target(target, runner):
    target.validate()
    identity = aws(target, runner, "sts", "get-caller-identity")
    if identity.get("Account") != target.account:
        raise DeployError("Authenticated AWS account differs from the configured target.")


def find_image(target, service, image_id, runner):
    require_image(service)
    try:
        response = aws(target, runner, "ecr", "describe-images", "--repository-name",
                       f"{target.project}/{service}", "--image-ids", json.dumps(image_id))
    except CommandError as exc:
        if exc.code == "ImageNotFoundException":
            return None
        raise
    details = response.get("imageDetails", [])
    if len(details) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", details[0].get("imageDigest", "")):
        raise DeployError("Registry response must contain exactly one valid image digest.")
    return details[0]["imageDigest"]


def assert_arm64(image_uri, runner):
    try:
        manifest = json.loads(runner.run(["docker", "buildx", "imagetools", "inspect", image_uri, "--raw"]))
        ref = image_uri
        if "manifests" in manifest:
            executable = [m for m in manifest["manifests"]
                          if m.get("platform", {}).get("os") == "linux"
                          and m.get("platform", {}).get("architecture") == "arm64"]
            if len(executable) != 1:
                raise DeployError("Image index must have exactly one Linux ARM64 manifest.")
            digest = executable[0].get("digest", "")
            if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                raise DeployError("ARM64 descriptor has no valid digest.")
            ref = image_uri.split("@")[0] + "@" + digest
        elif manifest.get("mediaType") not in (
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.v2+json"):
            raise DeployError("Unsupported registry manifest type.")
        config = json.loads(runner.run(["docker", "buildx", "imagetools", "inspect", ref,
                                        "--format", "{{json .Image}}"] ))
        if config.get("os") != "linux" or config.get("architecture") != "arm64":
            raise DeployError("Image config is not Linux ARM64.")
    except (ValueError, TypeError, AttributeError):
        raise DeployError("Registry inspection returned invalid manifest/config JSON.") from None


def validate_task(task, service, target):
    containers = task.get("containerDefinitions", [])
    matches = [c for c in containers if c.get("name") == service]
    if (task.get("family") != f"{target.project}-{service}" or len(matches) != 1
            or task.get("executionRoleArn") != f"arn:aws:iam::{target.account}:role/{target.project}-task-execution"
            or task.get("taskRoleArn") != f"arn:aws:iam::{target.account}:role/{target.project}-task"
            or task.get("runtimePlatform") != {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"}
            or task.get("networkMode") != "awsvpc"
            or task.get("requiresCompatibilities") != ["FARGATE"]):
        raise DeployError("Task recipe does not match the project family, roles, container or ARM64 runtime.")
    return matches[0]


def render_task(task, service, image_uri, target):
    require_service(service)
    validate_task(task, service, target)
    if not image_uri.startswith(target.repository(service) + "@sha256:"):
        raise DeployError("Replacement image must be a digest from this application repository.")
    rendered = {key: copy.deepcopy(task[key]) for key in REGISTER_FIELDS if key in task}
    next(c for c in rendered["containerDefinitions"] if c["name"] == service)["image"] = image_uri
    rendered["tags"] = [{"key": "Project", "value": target.project},
                        {"key": "ManagedBy", "value": "GitHubActions"}]
    return rendered


def assert_stable(service, expected_arn):
    deployments = service.get("deployments", [])
    if service.get("desiredCount", 0) <= 0:
        raise DeployError("Runtime is disabled: publish images, then activate Terraform with runtime_enabled=true and the same SHA.")
    if (service.get("taskDefinition") != expected_arn or len(deployments) != 1
            or deployments[0].get("status") != "PRIMARY"
            or deployments[0].get("taskDefinition") != expected_arn
            or deployments[0].get("rolloutState") != "COMPLETED"
            or service.get("runningCount") != service["desiredCount"]
            or service.get("pendingCount") != 0):
        raise DeployError("Service is not stable on the requested revision; deployment may have failed or rolled back.")


def describe_service(target, service, runner):
    name = f"{target.project}-{service}"
    response = aws(target, runner, "ecs", "describe-services", "--cluster", target.cluster, "--services", name)
    states = response.get("services", [])
    expected = f"arn:aws:ecs:{target.region}:{target.account}:service/{target.cluster}/{name}"
    if response.get("failures") or len(states) != 1 or states[0].get("serviceArn") != expected or states[0].get("status") != "ACTIVE":
        raise DeployError("Expected active application service is missing from the target cluster.")
    return states[0]


def describe_task(target, service, arn, runner):
    prefix = target.task_prefix(service)
    if not arn.startswith(prefix) or not re.fullmatch(r"[1-9][0-9]*", arn[len(prefix):]):
        raise DeployError("Task definition ARN must identify this project's application and a positive revision.")
    task = aws(target, runner, "ecs", "describe-task-definition", "--task-definition", arn).get("taskDefinition", {})
    if task.get("status") != "ACTIVE" or task.get("taskDefinitionArn") != arn:
        raise DeployError("Requested task definition is missing or inactive.")
    validate_task(task, service, target)
    return task


def checked_image(target, service, image_id, runner):
    digest = find_image(target, service, image_id, runner)
    if digest is None:
        raise DeployError("Required application image is missing; publish it before deployment or choose an available rollback revision.")
    uri = target.repository(service) + "@" + digest
    assert_arm64(uri, runner)
    return uri


def verify_images(target, sha, runner):
    require_sha(sha)
    return {s: checked_image(target, s, {"imageTag": sha}, runner) for s in IMAGES}


def update_and_wait(target, service, arn, runner):
    aws(target, runner, "ecs", "update-service", "--cluster", target.cluster,
        "--service", f"{target.project}-{service}", "--task-definition", arn, "--force-new-deployment")
    aws(target, runner, "ecs", "wait", "services-stable", "--cluster", target.cluster,
        "--services", f"{target.project}-{service}")
    assert_stable(describe_service(target, service, runner), arn)


def write_summary(rows, path):
    def clean(value):
        return re.sub(r"[^a-zA-Z0-9_./:@ =-]", "?", str(value))
    with path.open("a") as output:
        output.write("\n## Application release\n\n| Service | Digest | Previous revision | Requested revision | Status |\n|---|---|---|---|---|\n")
        for row in rows:
            output.write("| " + " | ".join(clean(row.get(k, "-")) for k in
                         ("service", "digest", "previous", "requested", "status")) + " |\n")


def deploy_all(target, sha, runner, summary_path):
    rows = [{"service": s, "status": "not attempted"} for s in SERVICES]
    current = None
    try:
        require_sha(sha)
        validate_target(target, runner)
        images = verify_images(target, sha, runner)
        recipes = {}
        for row in rows:
            s = row["service"]
            state = describe_service(target, s, runner)
            arn = state.get("taskDefinition", "")
            assert_stable(state, arn)
            recipes[s] = render_task(describe_task(target, s, arn, runner), s, images[s], target)
            row.update(previous=arn, digest=images[s].split("@")[1])
        for row in rows:
            current = row
            s = row["service"]
            row["status"] = "registering"
            response = aws(target, runner, "ecs", "register-task-definition", "--cli-input-json", json.dumps(recipes[s]))
            arn = response.get("taskDefinition", {}).get("taskDefinitionArn", "")
            # Validate AWS's registration result before selecting the revision.
            if not arn.startswith(target.task_prefix(s)) or not re.fullmatch(r"[1-9][0-9]*", arn[len(target.task_prefix(s)):]):
                raise DeployError("AWS registration returned an unexpected task revision.")
            row.update(requested=arn, status="updating")
            update_and_wait(target, s, arn, runner)
            row["status"] = "completed"
    except DeployError:
        if current is not None:
            current["status"] = "failed; inspect ECS"
        else:
            rows[0]["status"] = "preflight failed; no updates"
        raise
    finally:
        write_summary(rows, summary_path)


def rollback_one(target, service, arn, runner, summary_path):
    require_service(service)
    row = {"service": service, "status": "preflight"}
    try:
        validate_target(target, runner)
        state = describe_service(target, service, runner)
        assert_stable(state, state.get("taskDefinition", ""))
        task = describe_task(target, service, arn, runner)
        image = validate_task(task, service, target)["image"]
        repo = target.repository(service)
        if image.startswith(repo + "@") and re.fullmatch(r"sha256:[0-9a-f]{64}", image[len(repo)+1:]):
            image_id = {"imageDigest": image[len(repo)+1:]}
        elif image.startswith(repo + ":") and re.fullmatch(r"[0-9a-f]{40}", image[len(repo)+1:]):
            image_id = {"imageTag": image[len(repo)+1:]}
        else:
            raise DeployError("Rollback image must identify this application's SHA or digest in ECR.")
        checked = checked_image(target, service, image_id, runner)
        row.update(previous=state["taskDefinition"], requested=arn, digest=checked.split("@")[1], status="updating")
        update_and_wait(target, service, arn, runner)
        row["status"] = "completed"
    except DeployError:
        row["status"] = "failed; inspect ECS"
        raise
    finally:
        write_summary([row], summary_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("validate", "image-status", "verify-images", "deploy", "rollback"))
    parser.add_argument("--service", choices=IMAGES)
    parser.add_argument("--sha")
    parser.add_argument("--task-definition")
    args = parser.parse_args()
    try:
        target = Target.from_env()
        runner = CommandRunner()
        if args.operation == "validate":
            if os.environ.get("GITHUB_REF") != "refs/heads/main":
                raise DeployError("Dispatch this workflow from main only.")
            operation = os.environ.get("RELEASE_OPERATION")
            if operation not in ("publish", "deploy", "rollback"):
                raise DeployError("Choose publish, deploy or rollback.")
            if operation == "rollback":
                require_service(args.service)
                prefix = target.task_prefix(args.service)
                if not args.task_definition or not args.task_definition.startswith(prefix) or not re.fullmatch(r"[1-9][0-9]*", args.task_definition[len(prefix):]):
                    raise DeployError("Rollback requires a full project task-definition ARN with revision.")
            else:
                require_sha(args.sha or "")
            return 0
        path = Path(os.environ.get("GITHUB_STEP_SUMMARY", "deployment-summary.md"))
        if args.operation == "deploy":
            deploy_all(target, args.sha or "", runner, path)
        elif args.operation == "rollback":
            rollback_one(target, args.service, args.task_definition or "", runner, path)
        else:
            validate_target(target, runner)
            require_sha(args.sha or "")
            if args.operation == "image-status":
                digest = find_image(target, args.service, {"imageTag": args.sha}, runner)
                if digest:
                    assert_arm64(target.repository(args.service) + "@" + digest, runner)
                with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                    output.write(f"exists={str(digest is not None).lower()}\nimage_uri={target.repository(args.service)}:{args.sha}\n")
            else:
                images = verify_images(target, args.sha, runner)
                write_summary([{"service": s, "digest": uri.split("@")[1], "status": "published"}
                               for s, uri in images.items()], path)
        return 0
    except DeployError as exc:
        print(f"Deployment error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
