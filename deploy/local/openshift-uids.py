#!/usr/bin/env python3
"""A Helm post-renderer that does what OpenShift's restricted-v2 SCC does to IDs.

OpenShift admits a pod under restricted-v2 by assigning it a user ID and an
fsGroup from the namespace's range, typically seven digits and not present in
the image's /etc/passwd. An image that only works as the user it was built for
fails there and nowhere else. This stamps every pod the chart renders with such
an ID, so the same failure shows up on any cluster.

It only adds IDs. It does not make a manifest pass restricted-v2; the namespace
the check deploys into enforces the restricted Pod Security Standard for that.
"""
import sys

import yaml

UID = 1000680000
POD_TEMPLATES = {"Deployment", "StatefulSet", "DaemonSet", "Job", "ReplicaSet"}


def pod_spec(doc):
    kind = doc.get("kind")
    if kind == "Pod":
        return doc.setdefault("spec", {})
    if kind in POD_TEMPLATES:
        return doc.setdefault("spec", {}).setdefault("template", {}).setdefault("spec", {})
    if kind == "CronJob":
        job = doc.setdefault("spec", {}).setdefault("jobTemplate", {}).setdefault("spec", {})
        return job.setdefault("template", {}).setdefault("spec", {})
    return None


out = []
for doc in yaml.safe_load_all(sys.stdin):
    if not doc:
        continue
    spec = pod_spec(doc)
    if spec is not None:
        context = spec.setdefault("securityContext", {})
        # The chart must have left these out in OpenShift mode; if it did not,
        # the SCC would reject the pod, so say so rather than overwrite them.
        clashes = [k for k in ("runAsUser", "runAsGroup", "fsGroup") if k in context]
        if clashes:
            name = doc.get("metadata", {}).get("name")
            sys.exit(f"{doc['kind']}/{name} fixes {clashes}; restricted-v2 would reject it")
        context["runAsUser"] = UID
        context["fsGroup"] = UID
    out.append(doc)

yaml.safe_dump_all(out, sys.stdout, sort_keys=False)
