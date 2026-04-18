from datetime import datetime
from uuid import uuid4

from app.models import MessageItem


class InMemoryStore:
    def __init__(self) -> None:
        self.devices: dict[str, dict] = {}
        self.messages: dict[str, MessageItem] = {}

    def register_device(self, device_id: str, display_name: str, public_key: str | None) -> None:
        self.devices[device_id] = {
            "device_id": device_id,
            "display_name": display_name,
            "public_key": public_key,
            "registered_at": datetime.utcnow(),
        }

    def is_registered(self, device_id: str) -> bool:
        return device_id in self.devices

    def create_message(
        self,
        sender_device_id: str,
        recipient_device_id: str,
        ciphertext: str,
        nonce: str | None,
        message_type: str,
    ) -> MessageItem:
        message = MessageItem(
            message_id=str(uuid4()),
            sender_device_id=sender_device_id,
            recipient_device_id=recipient_device_id,
            ciphertext=ciphertext,
            nonce=nonce,
            message_type=message_type,
            created_at=datetime.utcnow(),
            delivered=False,
        )
        self.messages[message.message_id] = message
        return message

    def get_pending_messages(self, recipient_device_id: str) -> list[MessageItem]:
        return [
            message
            for message in self.messages.values()
            if message.recipient_device_id == recipient_device_id and not message.delivered
        ]

    def ack_message(self, message_id: str, device_id: str) -> bool:
        message = self.messages.get(message_id)
        if not message:
            return False

        if message.recipient_device_id != device_id:
            return False

        message.delivered = True
        self.messages[message_id] = message
        return True