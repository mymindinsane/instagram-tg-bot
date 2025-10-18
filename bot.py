import os
import io
import asyncio
from pathlib import Path
from dotenv import load_dotenv
from telegram import Update, InputFile
from telegram.constants import ParseMode
import html as _html
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes
from typing import Set, Dict, Any
from mutuals import compute, normalize
from ig_service import IGService

script_dir = Path(__file__).parent
# Load .env from repository root and legacy python/.env
root = Path(".env")
legacy = Path("python/.env")
load_dotenv(dotenv_path=root)
load_dotenv(dotenv_path=legacy)
# also try repo root .env (when running from project root)
load_dotenv(dotenv_path=script_dir.parent / ".env")
TOKEN = os.getenv("TELEGRAM_TOKEN")
SESSION_PATH = os.getenv("IG_SESSION_PATH", "./sessions/session.json")

ig = IGService(SESSION_PATH)
state: Dict[int, Dict[str, Any]] = {}


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text = (
        "👋😺 Привет! Я помогу сравнить подписчиков и подписки в Instagram.\n\n"
        "Доступные команды:\n"
        "🐾 /login — вход (при необходимости 2FA)\n"
        "🐾 /2fa <код> — отправить код двухфакторки\n"
        "🐾 /scrape <username> — собрать followers/following и сравнить\n"
        "🐾 /why <username> — объяснить, почему ник попал в категорию\n"
        "🐾 /find <pattern> — найти ник по подстроке\n\n"
        "Поддерживаю приватность: логин и пароль нигде не сохраняю, сессия живёт ограниченное время. 😼"
    )
    await update.message.reply_text(text)


async def login_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    state[chat] = {"stage": "WAIT_USERNAME"}
    await update.message.reply_text("Введите ваш Instagram username, затем пароль. Пароль не сохраняется и будет удалён из чата. 😺")


async def text_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    msg = update.message.text
    st = state.get(chat)
    if not st or not st.get("stage"):
        await update.message.reply_text("Я пока не жду текст на этом этапе. Отправьте /login чтобы войти или /help для подсказки.")
        return
    if st.get("stage") == "WAIT_USERNAME":
        st["login_username"] = normalize(msg)
        st["stage"] = "WAIT_PASSWORD"
        await update.message.reply_text("Отлично! Теперь введите пароль. 🐾")
        return
    if st.get("stage") == "WAIT_PASSWORD":
        username = st.get("login_username")
        password = msg
        try:
            res = ig.login(username, password)
            if res == "2FA":
                st["stage"] = "WAIT_2FA"
                await update.message.reply_text(
                    "😺 Требуется двухфакторная проверка!\n"
                    "Код придёт в приложение-аутентификатор, по SMS или на e‑mail (в зависимости от настроек Instagram).\n\n"
                    "Отправьте код так: \n"
                    "🐾 /2fa 123456\n\n"
                    "Не делитесь кодом с кем-либо. Я использую его только для завершения входа. 😼"
                )
            else:
                st["stage"] = None
                await update.message.reply_text("✅😺 Готово! Логин успешен. Теперь можно запустить: /scrape <username>")
        except Exception as e:
            st["stage"] = None
            await update.message.reply_text("❌🙀 Не удалось войти. Проверьте логин/пароль/код 2FA и попробуйте ещё раз.")
        finally:
            try:
                await update.message.delete()
            except Exception:
                pass
        return


async def twofa_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat = update.effective_chat.id
    st = state.get(chat, {})
    args = context.args
    if st.get("stage") != "WAIT_2FA":
        await update.message.reply_text("Сейчас 2FA не ожидается. Сначала выполните /login. 😺")
        return
    if not args:
        await update.message.reply_text("Использование: /2fa <код>")
        return
    code = args[0]
    try:
        ig.submit_2fa(code)
        st["stage"] = None
        await update.message.reply_text("✅😺 2FA успешно. Теперь запустите: /scrape <username>.")
    except Exception as e:
        st["stage"] = None
        await update.message.reply_text("❌🙀 Не удалось подтвердить 2FA. Проверьте код и попробуйте ещё раз.")


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
        # Inline summary with top items (HTML, escaped)
        def preview_block(title: str, items: list, limit: int = 15) -> str:
            head = f"<b>{_html.escape(title)}</b> ({len(items)}) 😺"
            if not items:
                return head + "\n<i>пусто</i>\n"
            top = items[:limit]
            body = "\n".join(f"🐱 {_html.escape(x)}" for x in top)
            more = "\n<i>и ещё...</i>" if len(items) > limit else ""
            return f"{head}\n{body}{more}\n"

        summary = (
            f"<b>Сводка для @{_html.escape(target)}:</b> 😼\n"
            f"Всего followers: {len(followers)}\n"
            f"Всего following: {len(following)}\n\n"
            + preview_block("Взаимные", m)
            + preview_block("Ты подписан, они нет", nfb)
            + preview_block("Они подписаны, ты нет", nfby)
        )
        await update.message.reply_text(summary, parse_mode=ParseMode.HTML)
        # Attach lists as files if large
        await send_list(update, "mutuals.txt", m)
        await send_list(update, "not_following_back.txt", nfb)
        await send_list(update, "not_followed_by_you.txt", nfby)
    except Exception as e:
        reason = str(e)
        if reason:
            reason = reason.strip().replace('\n', ' ')
            if len(reason) > 200:
                reason = reason[:200] + '…'
            await update.message.reply_text(f"❌ Не удалось собрать данные: {reason}")
        else:
            await update.message.reply_text("❌ Не удалось собрать данные. Попробуйте позже или проверьте доступ к профилю.")


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


async def unknown_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("Неизвестная команда. Доступные: /login, /2fa, /scrape, /why, /find, /help.")


async def error_handler(update: object, context: ContextTypes.DEFAULT_TYPE):
    try:
        if isinstance(update, Update) and update.effective_message:
            await update.effective_message.reply_text("Произошла ошибка. Попробуйте ещё раз чуть позже.")
    except Exception:
        pass


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
    # Должен идти после всех команд, чтобы перехватывать неизвестные
    app.add_handler(MessageHandler(filters.COMMAND, unknown_cmd))
    app.add_error_handler(error_handler)
    # Allow running under WSGI thread without installing signal handlers
    if os.getenv("PTB_NO_SIGNALS") == "1":
        app.run_polling(stop_signals=None)
    else:
        app.run_polling()


if __name__ == "__main__":
    main()
