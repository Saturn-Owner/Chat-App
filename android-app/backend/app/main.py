from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.responses import Response as RawResponse

from app.config import API_TITLE, API_VERSION
from app.models import (
    AcceptInviteRequest,
    AcceptInviteResponse,
    AckMessageRequest,
    AckMessageResponse,
    CheckCodeRequest,
    CheckCodeResponse,
    ContactListResponse,
    ContactItem,
    CreateGroupRequest,
    CreateGroupResponse,
    CreateInviteResponse,
    GroupInfo,
    GroupListResponse,
    GroupMember,
    HealthResponse,
    OPKCountResponse,
    OnlineStatusResponse,
    PendingMessagesResponse,
    PingRequest,
    PreKeyBundleResponse,
    RedeemCodeRequest,
    RedeemCodeResponse,
    RefreshTokenResponse,
    ReplenishOPKsRequest,
    RotateSPKRequest,
    SendMessageRequest,
    SendMessageResponse,
    SPKData,
    OPKItem,
    UploadFileResponse,
    UploadPreKeysRequest,
    UploadPreKeysResponse,
    X3DHHeader,
)
from app.store import InMemoryStore

app = FastAPI(title=API_TITLE, version=API_VERSION)
store = InMemoryStore()


def require_auth(device_id: str, token: str) -> None:
    if not store.verify_token(device_id, token):
        raise HTTPException(status_code=401, detail="Ungültiger Token")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/activation/check", response_model=CheckCodeResponse)
def check_code(payload: CheckCodeRequest) -> CheckCodeResponse:
    return CheckCodeResponse(valid=store.check_code(payload.code))


@app.post("/activation/redeem", response_model=RedeemCodeResponse)
def redeem_code(payload: RedeemCodeRequest) -> RedeemCodeResponse:
    token = store.redeem_code(
        code=payload.code,
        device_id=payload.device_id,
        display_name=payload.display_name,
    )
    if not token:
        raise HTTPException(status_code=403, detail="Ungültiger oder bereits verwendeter Code")
    return RedeemCodeResponse(success=True, message="Code eingelöst", token=token, recipient_device_id="")


@app.post("/auth/refresh", response_model=RefreshTokenResponse)
def refresh_token(x_device_id: str = Header(...), x_device_token: str = Header(...)) -> RefreshTokenResponse:
    require_auth(x_device_id, x_device_token)
    token, expires_at = store.refresh_token(x_device_id)
    return RefreshTokenResponse(token=token, expires_at=expires_at.isoformat())


@app.post("/devices/prekeys", response_model=UploadPreKeysResponse)
def upload_prekeys(
    payload: UploadPreKeysRequest,
    x_device_token: str = Header(...),
) -> UploadPreKeysResponse:
    require_auth(payload.device_id, x_device_token)
    store.upload_prekeys(
        device_id=payload.device_id,
        iks_pub=payload.iks_pub,
        ikd_pub=payload.ikd_pub,
        spk=payload.spk.model_dump(),
        opks=[o.model_dump() for o in payload.opks],
    )
    return UploadPreKeysResponse(success=True)


@app.get("/devices/{device_id}/prekey_bundle", response_model=PreKeyBundleResponse)
def get_prekey_bundle(
    device_id: str,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> PreKeyBundleResponse:
    require_auth(x_device_id, x_device_token)
    bundle = store.get_prekey_bundle(device_id)
    if not bundle:
        raise HTTPException(status_code=404, detail="PreKey Bundle nicht gefunden")
    return PreKeyBundleResponse(
        device_id=bundle["device_id"],
        iks_pub=bundle["iks_pub"],
        ikd_pub=bundle["ikd_pub"],
        spk=SPKData(**bundle["spk"]),
        opk=OPKItem(**bundle["opk"]) if bundle["opk"] else None,
    )


@app.post("/devices/{device_id}/prekeys/replenish", response_model=UploadPreKeysResponse)
def replenish_opks(
    device_id: str,
    payload: ReplenishOPKsRequest,
    x_device_token: str = Header(...),
) -> UploadPreKeysResponse:
    require_auth(device_id, x_device_token)
    store.replenish_opks(device_id, [o.model_dump() for o in payload.opks])
    return UploadPreKeysResponse(success=True)


@app.get("/devices/{device_id}/opk_count", response_model=OPKCountResponse)
def get_opk_count(
    device_id: str,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> OPKCountResponse:
    require_auth(x_device_id, x_device_token)
    return OPKCountResponse(count=store.get_opk_count(device_id))


@app.post("/keys/spk_rotate", response_model=UploadPreKeysResponse)
def rotate_spk(
    payload: RotateSPKRequest,
    x_device_token: str = Header(...),
) -> UploadPreKeysResponse:
    require_auth(payload.device_id, x_device_token)
    store.rotate_spk(payload.device_id, payload.spk.model_dump())
    return UploadPreKeysResponse(success=True)


@app.post("/devices/ping")
def ping(payload: PingRequest, x_device_token: str = Header(...)) -> dict:
    require_auth(payload.device_id, x_device_token)
    store.ping(payload.device_id)
    return {"ok": True}


@app.get("/devices/online/{device_id}", response_model=OnlineStatusResponse)
def online_status(
    device_id: str,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> OnlineStatusResponse:
    require_auth(x_device_id, x_device_token)
    return OnlineStatusResponse(online=store.is_online(device_id))


@app.post("/messages/send", response_model=SendMessageResponse)
def send_message(payload: SendMessageRequest, x_device_token: str = Header(...)) -> SendMessageResponse:
    require_auth(payload.sender_device_id, x_device_token)

    # Group message: fan out to all members
    if payload.group_id:
        msgs = store.fanout_group_message(
            sender_id=payload.sender_device_id,
            group_id=payload.group_id,
            ciphertext=payload.ciphertext,
            nonce=payload.nonce,
            message_type=payload.message_type,
        )
        if not msgs:
            raise HTTPException(status_code=404, detail="Gruppe nicht gefunden")
        return SendMessageResponse(success=True, message_id=msgs[0].message_id)

    # Pairwise message (also used for group_key distribution)
    if not store.is_registered(payload.recipient_device_id):
        raise HTTPException(status_code=400, detail="Empfänger nicht registriert")
    if payload.sender_device_id == payload.recipient_device_id:
        raise HTTPException(status_code=400, detail="Sender und Empfänger müssen verschieden sein")
    message = store.create_message(
        sender_device_id=payload.sender_device_id,
        recipient_device_id=payload.recipient_device_id,
        ciphertext=payload.ciphertext,
        nonce=payload.nonce,
        ratchet_pub=payload.ratchet_pub,
        message_index=payload.message_index,
        prev_send_index=payload.prev_send_index,
        x3dh_header=payload.x3dh_header.model_dump() if payload.x3dh_header else None,
        group_id=payload.group_id,
        message_type=payload.message_type,
    )
    return SendMessageResponse(success=True, message_id=message.message_id)


@app.get("/messages/pending/{device_id}", response_model=PendingMessagesResponse)
def get_pending_messages(device_id: str, x_device_token: str = Header(...)) -> PendingMessagesResponse:
    require_auth(device_id, x_device_token)
    return PendingMessagesResponse(messages=store.get_pending_messages(device_id))


@app.post("/contacts/invite", response_model=CreateInviteResponse)
def create_invite(x_device_id: str = Header(...), x_device_token: str = Header(...)) -> CreateInviteResponse:
    require_auth(x_device_id, x_device_token)
    code = store.create_invite(x_device_id)
    from datetime import datetime, UTC, timedelta
    expires_at = (datetime.now(UTC) + timedelta(hours=24)).isoformat()
    return CreateInviteResponse(code=code, expires_at=expires_at)


@app.post("/contacts/accept", response_model=AcceptInviteResponse)
def accept_invite(
    payload: AcceptInviteRequest,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> AcceptInviteResponse:
    require_auth(x_device_id, x_device_token)
    result = store.accept_invite(payload.code, x_device_id)
    if not result:
        raise HTTPException(status_code=400, detail="Ungültiger oder abgelaufener Code")
    contact_id, display_name = result
    return AcceptInviteResponse(contact_device_id=contact_id, display_name=display_name)


@app.get("/contacts", response_model=ContactListResponse)
def get_contacts(x_device_id: str = Header(...), x_device_token: str = Header(...)) -> ContactListResponse:
    require_auth(x_device_id, x_device_token)
    contacts = store.get_contacts(x_device_id)
    return ContactListResponse(contacts=[ContactItem(**c) for c in contacts])


@app.post("/groups/create", response_model=CreateGroupResponse)
def create_group(
    payload: CreateGroupRequest,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> CreateGroupResponse:
    require_auth(x_device_id, x_device_token)
    group_id = store.create_group(
        admin_id=x_device_id,
        name=payload.name,
        member_ids=payload.member_ids,
    )
    return CreateGroupResponse(group_id=group_id)


@app.get("/groups", response_model=GroupListResponse)
def get_groups(x_device_id: str = Header(...), x_device_token: str = Header(...)) -> GroupListResponse:
    require_auth(x_device_id, x_device_token)
    groups = store.get_my_groups(x_device_id)
    result = []
    for g in groups:
        members = [GroupMember(device_id=m, display_name=store.get_display_name(m)) for m in g["members"]]
        result.append(GroupInfo(group_id=g["group_id"], name=g["name"], admin_id=g["admin_id"], members=members))
    return GroupListResponse(groups=result)


@app.get("/groups/{group_id}", response_model=GroupInfo)
def get_group(
    group_id: str,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> GroupInfo:
    require_auth(x_device_id, x_device_token)
    g = store.get_group(group_id)
    if not g or x_device_id not in g["members"]:
        raise HTTPException(status_code=404, detail="Gruppe nicht gefunden")
    members = [GroupMember(device_id=m, display_name=store.get_display_name(m)) for m in g["members"]]
    return GroupInfo(group_id=g["group_id"], name=g["name"], admin_id=g["admin_id"], members=members)


@app.post("/files/upload", response_model=UploadFileResponse)
async def upload_file(
    request: Request,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> UploadFileResponse:
    require_auth(x_device_id, x_device_token)
    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="Keine Daten")
    file_id = store.save_file(data)
    return UploadFileResponse(file_id=file_id)


@app.get("/files/{file_id}")
def download_file(
    file_id: str,
    x_device_id: str = Header(...),
    x_device_token: str = Header(...),
) -> RawResponse:
    require_auth(x_device_id, x_device_token)
    data = store.get_file(file_id)
    if not data:
        raise HTTPException(status_code=404, detail="Datei nicht gefunden")
    return RawResponse(content=data, media_type="application/octet-stream")


@app.post("/messages/ack", response_model=AckMessageResponse)
def ack_message(payload: AckMessageRequest, x_device_token: str = Header(...)) -> AckMessageResponse:
    require_auth(payload.device_id, x_device_token)
    success = store.ack_message(message_id=payload.message_id, device_id=payload.device_id)
    if not success:
        raise HTTPException(status_code=404, detail="Nachricht nicht gefunden")
    return AckMessageResponse(success=True, message="ACK")
