from fastapi import FastAPI, HTTPException

from app.config import ALLOWED_DEVICE_IDS, API_TITLE, API_VERSION
from app.models import (
    AckMessageRequest,
    AckMessageResponse,
    HealthResponse,
    PendingMessagesResponse,
    RegisterDeviceRequest,
    RegisterDeviceResponse,
    SendMessageRequest,
    SendMessageResponse,
)
from app.store import InMemoryStore

app = FastAPI(title=API_TITLE, version=API_VERSION)
store = InMemoryStore()


def ensure_allowed_device(device_id: str) -> None:
    if device_id not in ALLOWED_DEVICE_IDS:
        raise HTTPException(status_code=403, detail="Device is not allowed")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/devices/register", response_model=RegisterDeviceResponse)
def register_device(payload: RegisterDeviceRequest) -> RegisterDeviceResponse:
    ensure_allowed_device(payload.device_id)

    store.register_device(
        device_id=payload.device_id,
        display_name=payload.display_name,
        public_key=payload.public_key,
    )

    return RegisterDeviceResponse(
        success=True,
        message="Device registered successfully",
    )


@app.post("/messages/send", response_model=SendMessageResponse)
def send_message(payload: SendMessageRequest) -> SendMessageResponse:
    ensure_allowed_device(payload.sender_device_id)
    ensure_allowed_device(payload.recipient_device_id)

    if not store.is_registered(payload.sender_device_id):
        raise HTTPException(status_code=400, detail="Sender device is not registered")

    if not store.is_registered(payload.recipient_device_id):
        raise HTTPException(status_code=400, detail="Recipient device is not registered")

    if payload.sender_device_id == payload.recipient_device_id:
        raise HTTPException(status_code=400, detail="Sender and recipient must be different")

    message = store.create_message(
        sender_device_id=payload.sender_device_id,
        recipient_device_id=payload.recipient_device_id,
        ciphertext=payload.ciphertext,
        nonce=payload.nonce,
        message_type=payload.message_type,
    )

    return SendMessageResponse(success=True, message_id=message.message_id)


@app.get("/messages/pending/{device_id}", response_model=PendingMessagesResponse)
def get_pending_messages(device_id: str) -> PendingMessagesResponse:
    ensure_allowed_device(device_id)

    if not store.is_registered(device_id):
        raise HTTPException(status_code=400, detail="Device is not registered")

    messages = store.get_pending_messages(device_id)
    return PendingMessagesResponse(messages=messages)


@app.post("/messages/ack", response_model=AckMessageResponse)
def ack_message(payload: AckMessageRequest) -> AckMessageResponse:
    ensure_allowed_device(payload.device_id)

    if not store.is_registered(payload.device_id):
        raise HTTPException(status_code=400, detail="Device is not registered")

    success = store.ack_message(
        message_id=payload.message_id,
        device_id=payload.device_id,
    )

    if not success:
        raise HTTPException(status_code=404, detail="Message not found or not owned by device")

    return AckMessageResponse(success=True, message="Message acknowledged")