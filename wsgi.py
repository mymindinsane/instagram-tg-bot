import os
import sys
import threading
from flask import Flask
from dotenv import load_dotenv
from pathlib import Path

# Load env from repo root if present (same behavior as bot.py)
script_dir = Path(__file__).parent
load_dotenv(dotenv_path=script_dir.parent / ".env")
os.environ.setdefault("PTB_NO_SIGNALS", "1")

app = Flask(__name__)

_bot_thread_started = False


def _start_bot():
    # Import here to avoid side effects on module import
    try:
        import bot
        print("[wsgi] Starting Telegram bot thread...", flush=True)
        bot.main()
    except Exception as e:
        print(f"[wsgi] Bot thread crashed: {e}", file=sys.stderr, flush=True)
        raise


def _ensure_bot_thread():
    global _bot_thread_started
    if not _bot_thread_started:
        t = threading.Thread(target=_start_bot, daemon=True)
        t.start()
        _bot_thread_started = True


# Start bot thread when the WSGI app is imported
_ensure_bot_thread()


@app.get("/")
def index():
    return "Telegram bot is running", 200


@app.get("/health")
def health():
    return "ok", 200
