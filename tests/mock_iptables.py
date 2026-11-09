#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host-only iptables/ip6tables mock used by test_scripts.py."""
import json
import os
from pathlib import Path
import sys

family = sys.argv[1]
args = sys.argv[2:]
state_path = Path(os.environ["MOCK_STATE"])
state = json.loads(state_path.read_text()) if state_path.exists() else {"4": {"chains": {}, "output": []}, "6": {"chains": {}, "output": []}}
fam = state[family]
with open(os.environ["MOCK_LOG"], "a", encoding="utf-8") as log:
    log.write(f"{family} {json.dumps(args)}\n")

fail_family = os.environ.get("MOCK_FAIL_FAMILY")
fail_substring = os.environ.get("MOCK_FAIL_SUBSTRING")
if fail_family == family and fail_substring and fail_substring in " ".join(args):
    sys.exit(1)

def save():
    state_path.write_text(json.dumps(state))

def rule_line(chain, rule):
    return f"-A {chain} {' '.join(rule)}"

if args == ["-S"]:
    print("-P OUTPUT ACCEPT")
    for chain, rules in fam["chains"].items():
        print(f"-N {chain}")
        for rule in rules:
            print(rule_line(chain, rule))
    for rule in fam["output"]:
        print(rule_line("OUTPUT", rule))
elif len(args) == 2 and args[0] == "-S":
    target = args[1]
    if target == "OUTPUT":
        print("-P OUTPUT ACCEPT")
        for rule in fam["output"]:
            print(rule_line("OUTPUT", rule))
    elif target in fam["chains"]:
        print(f"-N {target}")
        for rule in fam["chains"][target]:
            print(rule_line(target, rule))
    else:
        sys.exit(1)
elif len(args) == 2 and args[0] == "-N":
    if args[1] in fam["chains"]:
        sys.exit(1)
    fam["chains"][args[1]] = []
    save()
elif len(args) >= 3 and args[0] == "-A":
    chain = args[1]
    if chain == "OUTPUT":
        fam["output"].append(args[2:])
    elif chain in fam["chains"]:
        fam["chains"][chain].append(args[2:])
    else:
        sys.exit(1)
    save()
elif len(args) >= 4 and args[0] == "-I" and args[1] == "OUTPUT":
    fam["output"].insert(0, args[3:])
    save()
elif len(args) >= 3 and args[0] == "-C" and args[1] == "OUTPUT":
    sys.exit(0 if args[2:] in fam["output"] else 1)
elif len(args) >= 3 and args[0] == "-D" and args[1] == "OUTPUT":
    if args[2:] not in fam["output"]:
        sys.exit(1)
    fam["output"].remove(args[2:])
    save()
elif len(args) == 2 and args[0] == "-F":
    if args[1] not in fam["chains"]:
        sys.exit(1)
    fam["chains"][args[1]] = []
    save()
elif len(args) == 2 and args[0] == "-X":
    if args[1] not in fam["chains"] or fam["chains"][args[1]]:
        sys.exit(1)
    del fam["chains"][args[1]]
    save()
else:
    sys.exit(2)
