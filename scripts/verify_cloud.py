#!/usr/bin/env python3
"""Run against a disposable local Supabase stack or two accounts in a dedicated test project."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import json
import os
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def uid():
    return str(uuid.uuid4())


def required(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"Set {name} for the dedicated test project.")
    return value


class Cloud:
    def __init__(self, url, key, local=False):
        parsed = urlsplit(url)
        if local and (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost")):
            raise SystemExit("--local requires an HTTP loopback URL.")
        if not local and (parsed.scheme != "https" or not parsed.hostname):
            raise SystemExit("Use the HTTPS URL of a dedicated Supabase test project.")
        self.url = url.rstrip("/")
        self.key = key

    def request(self, path, body=None, access=None, expected=200):
        headers = {"apikey": self.key, "Accept": "application/json"}
        if access:
            headers["Authorization"] = f"Bearer {access}"
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.url + path, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(request, timeout=20) as response:
                if response.status != expected:
                    raise AssertionError(f"{path} returned HTTP {response.status}, expected {expected}")
                raw = response.read()
                return json.loads(raw) if raw else None
        except HTTPError as error:
            if error.code == expected:
                return None
            raise AssertionError(f"{path} returned HTTP {error.code}, expected {expected}") from None

    def sign_in(self, email, password):
        return self.request("/auth/v1/token?grant_type=password", {"email": email, "password": password})

    def create_test_account(self):
        email, password = f"ledger-{uuid.uuid4().hex}@example.com", f"Local-test-{uuid.uuid4().hex}"
        self.request("/auth/v1/signup", {"email": email, "password": password})
        session = self.sign_in(email, password)
        require(session["user"].get("email_confirmed_at"), "The local test account must be confirmed.")
        return session

    def rpc(self, name, access, body=None, expected=200):
        return self.request("/rest/v1/rpc/" + name, body or {}, access, expected)

    def apply(self, access, kind, budget, identifier, version, body, operation=None, expected=200):
        return self.rpc("ledger_apply", access, {
            "p_kind": kind, "p_budget": budget, "p_id": identifier,
            "p_base": version, "p_operation": operation or uid(), "p_body": body,
        }, expected)

    def rows(self, table, filter_name, filter_value, access, select="id"):
        query = urlencode({filter_name: "eq." + filter_value, "select": select})
        return self.request("/rest/v1/" + table + "?" + query, access=access)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def verify(cloud, owner, editor, contender=None):
    owner_token = owner["access_token"]
    refreshed = cloud.request("/auth/v1/token?grant_type=refresh_token", {"refresh_token": editor["refresh_token"]})
    editor_token = refreshed["access_token"]
    editor_id = editor["user"]["id"]
    require(owner["user"]["id"] != editor_id and refreshed["user"]["id"] == editor_id, "Session identity is incorrect.")
    require(all(session["user"].get("email_confirmed_at") for session in (owner, editor)), "Both accounts must be email-confirmed.")
    cloud.rpc("ledger_redeem", None, {"p_token": ""}, expected=401)
    cloud.rpc("ledger_redeem", "invalid-test-jwt", {"p_token": ""}, expected=401)
    shared, private, wallet, category, entry, owner_entry = (uid() for _ in range(6))
    budget_model = {"name": "SharedLedger integration test", "currency": "RSD", "icon": "wallet", "color": 1}
    created = []
    try:
        for budget_id, budget_type in ((shared, "SHARED"), (private, "PERSONAL")):
            details = {**budget_model, "type": budget_type}
            result = cloud.apply(owner_token, "budget", budget_id, budget_id, 0, details)
            require(result["status"] == "ok", "Budget creation failed.")
            created.append((budget_id, result["version"], details))
        require(cloud.rows("budgets", "id", private, editor_token) == [], "RLS exposed a private budget.")
        cloud.apply(owner_token, "account", shared, wallet, 0, {
            "name": "Cash", "type": "CASH", "currency": "RSD", "opening": "0",
            "icon": "wallet", "color": 1, "order": 0, "archived": False,
        })
        cloud.apply(owner_token, "category", shared, category, 0, {
            "name": "Groceries", "type": "EXPENSE", "icon": "basket", "color": 1, "order": 0, "archived": False,
        })
        day = "2025-01-15"
        draft = {
            "type": "EXPENSE", "accountId": wallet, "categoryId": category, "currency": "EUR", "amount": "20",
            "accountCurrency": "RSD", "accountAmount": "2343.4", "accountRate": "117.17", "accountRateDate": day,
            "accountRateSource": "Manual", "budgetCurrency": "RSD", "budgetAmount": "2343.4",
            "budgetRate": "117.17", "budgetRateDate": day, "budgetRateSource": "Manual",
            "note": "Integration test", "date": day, "payer": editor_id,
        }
        first_expense = cloud.apply(owner_token, "entry", shared, owner_entry, 0, {**draft, "payer": owner["user"]["id"]})
        require(first_expense["status"] == "ok", "Owner expense failed.")
        require(cloud.rows("budget_memberships", "budget_id", shared, editor_token, select="user_id") == [],
                "A nonmember saw memberships.")
        invite = cloud.rpc("ledger_invite", owner_token, {"p_budget": shared, "p_role": "EDITOR"})
        require(invite["code"] == "CREATED" and invite["budgetId"] == shared, "Invitation creation failed.")
        listed = cloud.rpc("ledger_invitations", owner_token, {"p_budget": shared})
        require(any(row["id"] == invite["id"] and row["used"] == 0 for row in listed), "The server did not persist the invitation.")
        # Uppercase and whitespace are valid for the stored hex format.
        token = invite["token"]
        formatted = token[:32].upper() + "-\n" + token[32:].upper()
        joined = cloud.rpc("ledger_redeem", editor_token, {"p_token": formatted})
        require(joined == {"code": "JOINED", "budgetId": shared}, "Invitation redemption failed.")
        joined_retry = cloud.rpc("ledger_redeem", editor_token, {"p_token": token})
        require(joined_retry == {"code": "ALREADY_MEMBER", "budgetId": shared}, "Same-user retry failed.")
        manifest = cloud.rpc("ledger_manifest", editor_token)
        require(any(row["id"] == shared and row["role"] == "EDITOR" for row in manifest), "The joined budget is missing from the manifest.")
        initial = cloud.rpc("ledger_snapshot", editor_token, {"p_budget": shared})
        require(any(member["userId"] == editor_id and member["active"] and member["role"] == "EDITOR"
                    for member in initial["members"]), "No active editor membership was persisted.")
        require({record["body"]["id"] for record in initial["records"]} >= {shared, wallet, category, owner_entry},
                "The initial snapshot omitted the budget or its financial records.")
        operation = uid()
        first = cloud.apply(editor_token, "entry", shared, entry, 0, draft, operation)
        retry = cloud.apply(editor_token, "entry", shared, entry, 0, draft, operation)
        require(first["version"] == retry["version"] == 1, "Retry changed the record version.")
        pending_edit = cloud.apply(owner_token, "entry", shared, entry, 1, {**draft, "note": "Owner edit"})
        require(pending_edit["version"] == 2, "Owner update failed.")
        conflict = cloud.apply(editor_token, "entry", shared, entry, 1, {**draft, "note": "Editor edit"})
        require(conflict["status"] == "conflict" and conflict["version"] == 2, "Stale update was accepted.")
        owner_snapshot = cloud.rpc("ledger_snapshot", owner_token, {"p_budget": shared})
        editor_snapshot = cloud.rpc("ledger_snapshot", editor_token, {"p_budget": shared})
        require(owner_snapshot == editor_snapshot, "Member snapshots differ.")
        entries = [record["body"] for record in editor_snapshot["records"] if record["kind"] == "entry"]
        require(len(entries) == 2 and sum(Decimal(row["budgetAmount"]) for row in entries) == Decimal("4686.80"),
                "The member transaction histories or total differ.")
        if contender:
            third_token = contender["access_token"]
            rejected = cloud.rpc("ledger_redeem", third_token, {"p_token": token})
            require(rejected["code"] == "INVITATION_ALREADY_USED", "A different account reused a one-use invitation.")
            race_budget = uid()
            details = {**budget_model, "type": "SHARED"}
            result = cloud.apply(owner_token, "budget", race_budget, race_budget, 0, details)
            created.append((race_budget, result["version"], details))
            race = cloud.rpc("ledger_invite", owner_token, {"p_budget": race_budget, "p_role": "EDITOR"})
            with ThreadPoolExecutor(max_workers=2) as pool:
                attempts = [pool.submit(cloud.rpc, "ledger_redeem", access, {"p_token": race["token"]})
                            for access in (editor_token, third_token)]
                require(sorted(attempt.result()["code"] for attempt in attempts) == ["INVITATION_ALREADY_USED", "JOINED"],
                        "Concurrent requests did not enforce a single use.")
            memberships = cloud.rpc("ledger_snapshot", owner_token, {"p_budget": race_budget})["members"]
            require(sum(member["active"] and member["role"] == "EDITOR" for member in memberships) == 1,
                    "The race created an incorrect number of memberships.")
        require(cloud.rpc("ledger_member_access", owner_token, {
            "p_budget": shared, "p_user": editor_id, "p_role": "VIEWER",
        }), "Viewer role change failed.")
        cloud.apply(editor_token, "entry", shared, entry, 2, draft, expected=403)
        require(cloud.rpc("ledger_member_access", owner_token, {
            "p_budget": shared, "p_user": editor_id, "p_role": "REVOKE",
        }), "Revocation failed.")
        require(cloud.rows("transactions", "budget_id", shared, editor_token) == [], "Revoked data is still visible.")
    finally:
        for budget_id, version, model in created:
            cloud.apply(owner_token, "budget", budget_id, budget_id, version, {**model, "deletedAt": 1})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", help="Create disposable accounts on the loopback test stack.")
    args = parser.parse_args()
    cloud = Cloud(required("SUPABASE_URL"), required("SUPABASE_PUBLISHABLE_KEY"), local=args.local)
    if args.local:
        verify(cloud, *(cloud.create_test_account() for _ in range(3)))
    else:
        owner = cloud.sign_in(required("LEDGER_TEST_OWNER_EMAIL"), required("LEDGER_TEST_OWNER_PASSWORD"))
        editor = cloud.sign_in(required("LEDGER_TEST_EDITOR_EMAIL"), required("LEDGER_TEST_EDITOR_PASSWORD"))
        verify(cloud, owner, editor)
    print("Supabase Auth, invitation, membership, and snapshot integration checks passed.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1) from None
