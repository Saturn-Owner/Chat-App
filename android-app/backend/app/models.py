from datetime import datetime
from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str


class CheckCodeRequest(BaseModel):
    code: str


class CheckCodeResponse(BaseModel):
    valid: bool


class RedeemCodeRequest(BaseModel):
    code: str
    device_id: str
    display_name: str


class RedeemCodeResponse(BaseModel):
    success: bool
    message: str
    token: str
    recipient_device_id: str


class RefreshTokenResponse(BaseModel):
    token: str
    expires_at: str


class PingRequest(BaseModel):
    device_id: str


class OnlineStatusResponse(BaseModel):
    online: bool


# ── PreKey Bundle ──────────────────────────────────────────────────────────────

class OPKItem(BaseModel):
    id: int
    pub: str  # base64 X25519


class SPKData(BaseModel):
    id: int
    pub: str   # base64 X25519
    sig: str   # base64 ECDSA-SHA256 over pub with IKS


class UploadPreKeysRequest(BaseModel):
    device_id: str
    iks_pub: str   # base64 EC P-256 SubjectPublicKeyInfo
    ikd_pub: str   # base64 X25519
    spk: SPKData
    opks: list[OPKItem]


class UploadPreKeysResponse(BaseModel):
    success: bool


class PreKeyBundleResponse(BaseModel):
    device_id: str
    iks_pub: str
    ikd_pub: str
    spk: SPKData
    opk: OPKItem | None  # None if no OPKs left


class ReplenishOPKsRequest(BaseModel):
    device_id: str
    opks: list[OPKItem]


class OPKCountResponse(BaseModel):
    count: int


class RotateSPKRequest(BaseModel):
    device_id: str
    spk: SPKData


# ── Messages ───────────────────────────────────────────────────────────────────

class X3DHHeader(BaseModel):
    ikd_pub: str        # base64 X25519
    ek_pub: str         # base64 X25519
    opk_id: int         # -1 if no OPK used
    iks_pub: str = ""   # base64 EC P-256, für Safety Numbers


class SendMessageRequest(BaseModel):
    sender_device_id: str
    recipient_device_id: str
    ciphertext: str        # base64 XSalsa20-Poly1305
    nonce: str             # base64 24 bytes
    ratchet_pub: str       # base64 X25519
    message_index: int
    prev_send_index: int
    x3dh_header: X3DHHeader | None = None
    group_id: str | None = None
    message_type: str = "text"   # "text" | "group_key"


class SendMessageResponse(BaseModel):
    success: bool
    message_id: str


class MessageItem(BaseModel):
    message_id: str
    sender_device_id: str
    recipient_device_id: str
    ciphertext: str
    nonce: str
    ratchet_pub: str
    message_index: int
    prev_send_index: int
    created_at: datetime
    x3dh_header: X3DHHeader | None = None
    group_id: str | None = None
    message_type: str = "text"


# ── Groups ─────────────────────────────────────────────────────────────────────

class GroupMember(BaseModel):
    device_id: str
    display_name: str


class CreateGroupRequest(BaseModel):
    name: str
    member_ids: list[str]


class CreateGroupResponse(BaseModel):
    group_id: str


class GroupInfo(BaseModel):
    group_id: str
    name: str
    admin_id: str
    members: list[GroupMember]


class GroupListResponse(BaseModel):
    groups: list[GroupInfo]


class PendingMessagesResponse(BaseModel):
    messages: list[MessageItem]


class AckMessageRequest(BaseModel):
    device_id: str = Field(..., min_length=3, max_length=100)
    message_id: str = Field(..., min_length=1)


class AckMessageResponse(BaseModel):
    success: bool
    message: str


# ── Contacts ───────────────────────────────────────────────────────────────────

class CreateInviteResponse(BaseModel):
    code: str
    expires_at: str


class AcceptInviteRequest(BaseModel):
    code: str


class AcceptInviteResponse(BaseModel):
    contact_device_id: str
    display_name: str


class ContactItem(BaseModel):
    device_id: str
    display_name: str


class ContactListResponse(BaseModel):
    contacts: list[ContactItem]


# ── Files ──────────────────────────────────────────────────────────────────────

class UploadFileResponse(BaseModel):
    file_id: str
