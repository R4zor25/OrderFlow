import os
from pathlib import Path
import re
import subprocess
import unittest


class DeploymentDependencyTest(unittest.TestCase):
    def test_application_service_waits_for_alb_forwarding_association(self):
        module = Path(__file__).resolve().parents[1]
        graph = subprocess.check_output(
            [os.environ.get("TERRAFORM_BIN", "terraform"), f"-chdir={module}", "graph"],
            text=True,
        )
        edges = {}
        for source, target in re.findall(r'"([^"]+)" -> "([^"]+)"', graph):
            edges.setdefault(source, set()).add(target)
        pending = ["aws_ecs_service.application"]
        visited = set()
        while pending:
            node = pending.pop()
            if node in visited:
                continue
            visited.add(node)
            pending.extend(edges.get(node, ()))
        self.assertIn("aws_lb_listener_rule.application", visited,
                      "ECS creation must wait until forwarding rules associate its target groups with the ALB.")
