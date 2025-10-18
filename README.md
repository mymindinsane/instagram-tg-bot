# Instagram Mutuals Bot (Python)

## Quick start
1. Create and fill .env in repo root:
```
TELEGRAM_TOKEN=YOUR_TELEGRAM_BOT_TOKEN
IG_SESSION_PATH=./sessions/session.json
```
2. Install deps:
```
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```
3. Run bot:
```
python bot.py
```

## Commands
- /login — enter Instagram username, then password (messages get deleted). 2FA may be required.
- /2fa <code> — send 2FA code when asked.
- /scrape <username> — fetch followers/following and compute mutuals.
- /why <username> — explain category for a nickname.
- /find <pattern> — search in followers/following sets.

## Notes
- Session is stored in `sessions/session.json`.
- Use only your own accounts and obey Instagram ToS.
