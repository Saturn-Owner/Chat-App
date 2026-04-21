import json
import secrets
from datetime import datetime, UTC, timedelta
from pathlib import Path
from uuid import uuid4

from app.models import MessageItem, OPKItem, SPKData

CODES_FILE = Path(__file__).parent.parent.parent.parent / "tim-test" / "codes.json"
PREKEYS_FILE = Path(__file__).parent.parent.parent.parent / "tim-test" / "prekeys.json"
CONTACTS_FILE = Path(__file__).parent.parent.parent.parent / "tim-test" / "contacts.json"
GROUPS_FILE = Path(__file__).parent.parent.parent.parent / "tim-test" / "groups.json"
TOKEN_TTL_DAYS = 30


def _load_codes() -> dict:
    if CODES_FILE.exists():
        content = CODES_FILE.read_text().strip()
        if content:
            return json.loads(content)
    return {}


def _save_codes(codes: dict) -> None:
    CODES_FILE.write_text(json.dumps(codes, indent=2))


def _load_prekeys() -> dict:
    if PREKEYS_FILE.exists():
        content = PREKEYS_FILE.read_text().strip()
        if content:
            return json.loads(content)
    return {}


def _save_prekeys(data: dict) -> None:
    PREKEYS_FILE.write_text(json.dumps(data, indent=2))


def _load_contacts() -> dict:
    if CONTACTS_FILE.exists():
        content = CONTACTS_FILE.read_text().strip()
        if content:
            return json.loads(content)
    return {}


def _save_contacts(data: dict) -> None:
    CONTACTS_FILE.write_text(json.dumps(data, indent=2))


def _load_groups() -> dict:
    if GROUPS_FILE.exists():
        content = GROUPS_FILE.read_text().strip()
        if content:
            return json.loads(content)
    return {}


def _save_groups(data: dict) -> None:
    GROUPS_FILE.write_text(json.dumps(data, indent=2))


class InMemoryStore:
    def __init__(self) -> None:
        self.messages: dict[str, MessageItem] = {}
        self.pings: dict[str, datetime] = {}
        self.devices: dict[str, dict] = {}
        self.tokens: dict[str, str] = {}
        self.token_expiry: dict[str, datetime] = {}
        self.prekey_bundles: dict[str, dict] = {}
        self.contacts: dict[str, list[str]] = {}   # device_id → list of contact device_ids
        self.invites: dict[str, dict] = {}          # code → {owner_id, expires_at}
        self.groups: dict[str, dict] = {}           # group_id → group data
        self.files: dict[str, bytes] = {}           # file_id → encrypted bytes
        self._restore_from_codes()
        self._restore_prekey_bundles()
        self._restore_contacts()
        self._restore_groups()

    def _restore_from_codes(self) -> None:
        for entry in _load_codes().values():
            if not (entry.get("used") and entry.get("device_id") and entry.get("token")):
                continue
            device_id = entry["device_id"]
            expires_at_str = entry.get("token_expires_at")
            if not expires_at_str:
                continue
            expires_at = datetime.fromisoformat(expires_at_str)
            if expires_at < datetime.now(UTC):
                continue
            self.devices[device_id] = {
                "device_id": device_id,
                "display_name": entry.get("display_name", ""),
                "registered_at": entry.get("registered_at", ""),
            }
            self.tokens[device_id] = entry["token"]
            self.token_expiry[device_id] = expires_at

    def _restore_prekey_bundles(self) -> None:
        self.prekey_bundles = _load_prekeys()

    def _restore_contacts(self) -> None:
        self.contacts = _load_contacts()

    def _restore_groups(self) -> None:
        self.groups = _load_groups()

    # ── Auth ──────────────────────────────────────────────────────────────────

    def ping(self, device_id: str) -> None:
        self.pings[device_id] = datetime.now(UTC)

    def is_online(self, device_id: str) -> bool:
        last = self.pings.get(device_id)
        return last is not None and datetime.now(UTC) - last < timedelta(seconds=30)

    def is_registered(self, device_id: str) -> bool:
        return device_id in self.devices

    def get_display_name(self, device_id: str) -> str:
        return self.devices.get(device_id, {}).get("display_name", device_id)

    def verify_token(self, device_id: str, token: str) -> bool:
        if not secrets.compare_digest(self.tokens.get(device_id, ""), token):
            return False
        expiry = self.token_expiry.get(device_id)
        return expiry is not None and expiry > datetime.now(UTC)

    def check_code(self, code: str) -> bool:
        entry = _load_codes().get(code)
        return bool(entry and not entry.get("used"))

    def redeem_code(self, code: str, device_id: str, display_name: str) -> str | None:
        codes = _load_codes()
        entry = codes.get(code)
        if not entry or entry.get("used"):
            return None

        token = secrets.token_hex(32)
        now = datetime.now(UTC)
        expires_at = now + timedelta(days=TOKEN_TTL_DAYS)

        codes[code].update({
            "used": True,
            "device_id": device_id,
            "display_name": display_name,
            "token": token,
            "token_expires_at": expires_at.isoformat(),
            "registered_at": now.isoformat(),
        })
        _save_codes(codes)

        self.devices[device_id] = {"device_id": device_id, "display_name": display_name, "registered_at": now.isoformat()}
        self.tokens[device_id] = token
        self.token_expiry[device_id] = expires_at
        return token

    def refresh_token(self, device_id: str) -> tuple[str, datetime]:
        codes = _load_codes()
        token = secrets.token_hex(32)
        now = datetime.now(UTC)
        expires_at = now + timedelta(days=TOKEN_TTL_DAYS)
        for entry in codes.values():
            if entry.get("device_id") == device_id:
                entry["token"] = token
                entry["token_expires_at"] = expires_at.isoformat()
                break
        _save_codes(codes)
        self.tokens[device_id] = token
        self.token_expiry[device_id] = expires_at
        return token, expires_at

    # ── PreKey Bundle ──────────────────────────────────────────────────────────

    def upload_prekeys(self, device_id: str, iks_pub: str, ikd_pub: str,
                       spk: dict, opks: list[dict]) -> None:
        self.prekey_bundles[device_id] = {
            "iks_pub": iks_pub,
            "ikd_pub": ikd_pub,
            "spk": spk,
            "opks": opks,
        }
        _save_prekeys(self.prekey_bundles)

    def get_prekey_bundle(self, device_id: str) -> dict | None:
        bundle = self.prekey_bundles.get(device_id)
        if not bundle:
            return None
        opk = None
        if bundle["opks"]:
            opk = bundle["opks"].pop(0)
            _save_prekeys(self.prekey_bundles)
        return {
            "device_id": device_id,
            "iks_pub": bundle["iks_pub"],
            "ikd_pub": bundle["ikd_pub"],
            "spk": bundle["spk"],
            "opk": opk,
        }

    def replenish_opks(self, device_id: str, new_opks: list[dict]) -> None:
        if device_id in self.prekey_bundles:
            self.prekey_bundles[device_id]["opks"].extend(new_opks)
            _save_prekeys(self.prekey_bundles)

    def get_opk_count(self, device_id: str) -> int:
        return len(self.prekey_bundles.get(device_id, {}).get("opks", []))

    def rotate_spk(self, device_id: str, spk: dict) -> None:
        if device_id in self.prekey_bundles:
            self.prekey_bundles[device_id]["spk"] = spk
            _save_prekeys(self.prekey_bundles)

    # ── Messages ──────────────────────────────────────────────────────────────

    def create_message(self, sender_device_id: str, recipient_device_id: str,
                       ciphertext: str, nonce: str, ratchet_pub: str,
                       message_index: int, prev_send_index: int,
                       x3dh_header: dict | None,
                       group_id: str | None = None,
                       message_type: str = "text") -> MessageItem:
        message = MessageItem(
            message_id=str(uuid4()),
            sender_device_id=sender_device_id,
            recipient_device_id=recipient_device_id,
            ciphertext=ciphertext,
            nonce=nonce,
            ratchet_pub=ratchet_pub,
            message_index=message_index,
            prev_send_index=prev_send_index,
            created_at=datetime.now(UTC),
            x3dh_header=x3dh_header,
            group_id=group_id,
            message_type=message_type,
        )
        self.messages[message.message_id] = message
        return message

    def get_pending_messages(self, recipient_device_id: str) -> list[MessageItem]:
        return [m for m in self.messages.values()
                if m.recipient_device_id == recipient_device_id]

    def ack_message(self, message_id: str, device_id: str) -> bool:
        message = self.messages.get(message_id)
        if not message or message.recipient_device_id != device_id:
            return False
        del self.messages[message_id]
        return True

    # ── Contacts ──────────────────────────────────────────────────────────────

    def create_invite(self, device_id: str) -> str:
        code = secrets.token_hex(4).upper()  # 8-char hex code e.g. "A3F2B1C0"
        self.invites[code] = {
            "owner_id": device_id,
            "expires_at": (datetime.now(UTC) + timedelta(hours=24)).isoformat(),
        }
        return code

    def accept_invite(self, code: str, acceptor_id: str) -> tuple[str, str] | None:
        invite = self.invites.get(code)
        if not invite:
            return None
        expires_at = datetime.fromisoformat(invite["expires_at"])
        if expires_at < datetime.now(UTC):
            del self.invites[code]
            return None
        owner_id = invite["owner_id"]
        if owner_id == acceptor_id:
            return None
        del self.invites[code]
        if owner_id not in self.contacts:
            self.contacts[owner_id] = []
        if acceptor_id not in self.contacts[owner_id]:
            self.contacts[owner_id].append(acceptor_id)
        if acceptor_id not in self.contacts:
            self.contacts[acceptor_id] = []
        if owner_id not in self.contacts[acceptor_id]:
            self.contacts[acceptor_id].append(owner_id)
        _save_contacts(self.contacts)
        return owner_id, self.get_display_name(owner_id)

    # ── Groups ────────────────────────────────────────────────────────────────

    def create_group(self, admin_id: str, name: str, member_ids: list[str]) -> str:
        group_id = str(uuid4())
        members = list({admin_id} | set(member_ids))
        self.groups[group_id] = {
            "group_id": group_id,
            "name": name,
            "admin_id": admin_id,
            "members": members,
            "created_at": datetime.now(UTC).isoformat(),
        }
        _save_groups(self.groups)
        return group_id

    def get_group(self, group_id: str) -> dict | None:
        return self.groups.get(group_id)

    def get_my_groups(self, device_id: str) -> list[dict]:
        return [g for g in self.groups.values() if device_id in g["members"]]

    def fanout_group_message(self, sender_id: str, group_id: str,
                             ciphertext: str, nonce: str, message_type: str) -> list[MessageItem]:
        group = self.groups.get(group_id)
        if not group:
            return []
        created = []
        for member_id in group["members"]:
            if member_id == sender_id:
                continue
            msg = MessageItem(
                message_id=str(uuid4()),
                sender_device_id=sender_id,
                recipient_device_id=member_id,
                ciphertext=ciphertext,
                nonce=nonce,
                ratchet_pub="",
                message_index=0,
                prev_send_index=0,
                created_at=datetime.now(UTC),
                group_id=group_id,
                message_type=message_type,
            )
            self.messages[msg.message_id] = msg
            created.append(msg)
        return created

    # ── Files ─────────────────────────────────────────────────────────────────

    def save_file(self, data: bytes) -> str:
        file_id = str(uuid4())
        self.files[file_id] = data
        return file_id

    def get_file(self, file_id: str) -> bytes | None:
        return self.files.get(file_id)

    # ── Contacts ──────────────────────────────────────────────────────────────

    def get_contacts(self, device_id: str) -> list[dict]:
        return [
            {"device_id": cid, "display_name": self.get_display_name(cid)}
            for cid in self.contacts.get(device_id, [])
        ]
