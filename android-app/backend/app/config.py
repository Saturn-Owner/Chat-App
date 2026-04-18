import os
from dotenv import load_dotenv

load_dotenv()

API_TITLE = os.getenv("API_TITLE", "Secure Chat MVP")
API_VERSION = os.getenv("API_VERSION", "0.1.0")

raw_ids = os.getenv("ALLOWED_DEVICE_IDS", "")
ALLOWED_DEVICE_IDS = {
    device_id.strip() for device_id in raw_ids.split(",") if device_id.strip()
}