from datetime import datetime
from pydantic import BaseModel, Field


class RegisterDeviceRequest(BaseModel):
    device_id: str = Field(..., min_length=3, max_length=100)
    display_name: str = Field(..., min_length=1, max_length=50)
    public_key: str | None = None


class RegisterDeviceResponse(BaseModel):
    success: bool
    message: str


class SendMessageRequest(BaseModel):
    sender_device_id: str = Field(..., min_length=3, max_length=100)
    recipient_device_id: str = Field(..., min_length=3, max_length=100)
    ciphertext: str = Field(..., min_length=1)
    nonce: str | None = None
    message_type: str = Field(default="text", min_length=1, max_length=20)


class SendMessageResponse(BaseModel):
    success: bool
    message_id: str


class MessageItem(BaseModel):
    message_id: str
    sender_device_id: str
    recipient_device_id: str
    ciphertext: str
    nonce: str | None
    message_type: str
    created_at: datetime
    delivered: bool = False


class PendingMessagesResponse(BaseModel):
    messages: list[MessageItem]


class AckMessageRequest(BaseModel):
    device_id: str = Field(..., min_length=3, max_length=100)
    message_id: str = Field(..., min_length=1)


class AckMessageResponse(BaseModel):
    success: bool
    message: str


class HealthResponse(BaseModel):
    status: str