#!/usr/bin/env python3
"""Run against a dedicated Supabase test project and two confirmed test accounts."""
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
    def __init__(self, url, key):
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname:
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

    def rpc(self, name, access, body=None, expected=200):
        return self.request("/rest/v1/rpc/" + name, body or {}, access, expected)

    def apply(self, access, kind, budget, identifier, version, body, operation=None, expected=200):
        return self.rpc("ledger_apply", access, {
            "p_kind": kind, "p_budget": budget, "p_id": identifier,
            "p_base": version, "p_operation": operation or uid(), "p_body": body,
        }, expected)

    def rows(self, table, filter_name, filter_value, access):
        query = urlencode({filter_name: "eq." + filter_value, "select": "id"})
        return self.request("/rest/v1/" + table + "?" + query, access=access)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def verify(cloud, owner, editor):
    owner_token = owner["access_token"]
    editor_token = editor["access_token"]
    editor_id = editor["user"]["id"]
    require(owner["user"]["id"] != editor_id, "The test requires two different users.")
    shared, private, wallet, category, entry = (uid() for _ in range(5))
    budget_model = {"name": "SharedLedger integration test", "currency": "RSD", "icon": "wallet", "color": 1}
    created = []
    try:
        for budget_id, budget_type in ((shared, "SHARED"), (private, "PERSONAL")):
            details = {**budget_model, "type": budget_type}
            result = cloud.apply(owner_token, "budget", budget_id, budget_id, 0, details)
            require(result["status"] == "ok", "Budget creation failed.")
            created.append((budget_id, result["version"], details))
        require(cloud.rows("budgets", "id", private, editor_token) == [], "RLS exposed a private budget.")
        invite = cloud.rpc("ledger_invite", owner_token, {"p_budget": shared, "p_role": "EDITOR"})
        joined = cloud.rpc("ledger_redeem", editor_token, {"p_token": invite["token"]})
        require(joined.get("budgetId") == shared, "Invitation redemption failed.")
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
    cloud = Cloud(required("SUPABASE_URL"), required("SUPABASE_PUBLISHABLE_KEY"))
    owner = cloud.sign_in(required("LEDGER_TEST_OWNER_EMAIL"), required("LEDGER_TEST_OWNER_PASSWORD"))
    editor = cloud.sign_in(required("LEDGER_TEST_EDITOR_EMAIL"), required("LEDGER_TEST_EDITOR_PASSWORD"))
    verify(cloud, owner, editor)
    print("Hosted Supabase integration checks passed.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1) from None
