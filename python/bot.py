import os
import io
import asyncio
from pathlib import Path
from dotenv import load_dotenv
from telegram import Update, InputFile
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes
from typing import Set, Dict, Any
from mutuals import compute, normalize
from ig_service import IGService

script_dir = Path(__file__).parent
# load .env next to script
load_dotenv(dotenv_path=script_dir / ".env")
# also try repo root .env (when running from project root)
load_dotenv(dotenv_path=script_dir.parent / ".env")
TOKEN = os.getenv("TELEGRAM_TOKEN")
SESSION_PATH = os.getenv("IG_SESSION_PATH", "./python/sessions/session.json")

ig = IGService(SESSION_PATH)
state: Dict[int, Dict[str, Any]] = {}


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "Привет! Команды:\n/login — вход\n/2fa <код> — двухфакторка\n/scrape <username> — собрать и сравнить\n/why <username> — объяснить\n/find <pattern> — поиск"
    )


async def login_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    state[chat] = {"stage": "WAIT_USERNAME"}
    await update.message.reply_text("Введи username, затем пароль. Эти сообщения будут удалены.")


async def text_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    msg = update.message.text
    st = state.get(chat)
    if not st:
        return
    if st.get("stage") == "WAIT_USERNAME":
        st["login_username"] = normalize(msg)
        st["stage"] = "WAIT_PASSWORD"
        await update.message.reply_text("Теперь введи пароль.")
        return
    if st.get("stage") == "WAIT_PASSWORD":
        username = st.get("login_username")
        password = msg
        try:
            res = ig.login(username, password)
            try:
                await update.message.delete()
            except Exception:
                pass
            if res == "2FA":
                st["stage"] = "WAIT_2FA"
                await update.message.reply_text("Требуется 2FA. Пришли /2fa 123456")
            else:
                st["stage"] = None
                await update.message.reply_text("Логин успешен. Запускай /scrape <username>.")
        except Exception as e:
            st["stage"] = None
            await update.message.reply_text(f"Ошибка логина: {e}")
        return


async def twofa_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    st = state.get(chat, {})
    args = context.args
    if st.get("stage") != "WAIT_2FA":
        await update.message.reply_text("Сейчас 2FA не ожидается. Сначала /login.")
        return
    if not args:
        await update.message.reply_text("Использование: /2fa <код>")
        return
    code = args[0]
    try:
        ig.submit_2fa(code)
        st["stage"] = None
        await update.message.reply_text("2FA успешно. Теперь /scrape <username>.")
    except Exception as e:
        st["stage"] = None
        await update.message.reply_text(f"Ошибка 2FA: {e}")


async def scrape_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    args = context.args
    if not args:
        await update.message.reply_text("Использование: /scrape <username>")
        return
    target = normalize(args[0])
    try:
        followers, following = ig.fetch_all(target)
        # Save in state for diagnostics
        st = state.setdefault(chat, {})
        st["followers"] = followers
        st["following"] = following
        m, nfb, nfby = compute(followers, following)
        summary = f"Всего followers: {len(followers)}\nВсего following: {len(following)}\nВзаимные: {len(m)}\nНе взаимные (ты подписан, они нет): {len(nfb)}\nНе взаимные (они подписаны, ты нет): {len(nfby)}"
        await update.message.reply_text(summary)
        # Attach lists as files if large
        await send_list(update, "mutuals.txt", m)
        await send_list(update, "not_following_back.txt", nfb)
        await send_list(update, "not_followed_by_you.txt", nfby)
    except Exception as e:
        await update.message.reply_text(f"Ошибка скрейпа: {e}")


async def send_list(update: Update, filename: str, items):
    if not items:
        await update.message.reply_text(f"{filename}: пусто")
        return
    data = "\n".join(items).encode()
    await update.message.reply_document(document=InputFile(io.BytesIO(data), filename))


async def why_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    args = context.args
    if not args:
        await update.message.reply_text("Использование: /why <username>")
        return
    q = normalize(args[0])
    st = state.get(chat, {})
    followers: Set[str] = st.get("followers", set())
    following: Set[str] = st.get("following", set())
    in_f1 = q in {normalize(x) for x in followers}
    in_f2 = q in {normalize(x) for x in following}
    if in_f1 and in_f2:
        cat = "Взаимные"
    elif (not in_f1) and in_f2:
        cat = "Ты подписан, он(а) нет"
    elif in_f1 and (not in_f2):
        cat = "Он(а) подписан, ты нет"
    else:
        cat = "Не найден ни в followers, ни в following"
    await update.message.reply_text(f"Проверка @{q}:\nfollowers: {'да' if in_f1 else 'нет'}\nfollowing: {'да' if in_f2 else 'нет'}\nКатегория: {cat}")


async def find_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    args = context.args
    if not args:
        await update.message.reply_text("Использование: /find <pattern>")
        return
    pat = args[0].lower()
    st = state.get(chat, {})
    followers: Set[str] = st.get("followers", set())
    following: Set[str] = st.get("following", set())
    f1 = [x for x in followers if pat in x.lower()][:10]
    f2 = [x for x in following if pat in x.lower()][:10]
    await update.message.reply_text(f"Поиск '{pat}':\nfollowers ({len(f1)}): {', '.join(f1)}\nfollowing ({len(f2)}): {', '.join(f2)}")


def main():
    if not TOKEN:
        raise RuntimeError("TELEGRAM_TOKEN is not set")
    app = Application.builder().token(TOKEN).build()
    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("help", start))
    app.add_handler(CommandHandler("login", login_cmd))
    app.add_handler(CommandHandler("2fa", twofa_cmd))
    app.add_handler(CommandHandler("scrape", scrape_cmd))
    app.add_handler(CommandHandler("why", why_cmd))
    app.add_handler(CommandHandler("find", find_cmd))
    app.add_handler(MessageHandler(filters.TEXT & (~filters.COMMAND), text_handler))
    app.run_polling()


if __name__ == "__main__":
    main()
