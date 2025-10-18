package com.example.igbot;

public class App {
    public static void main(String[] args) {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Env TELEGRAM_BOT_TOKEN is required");
            System.exit(1);
        }

        TelegramBotService service = new TelegramBotService(token);
        Runtime.getRuntime().addShutdownHook(new Thread(service::shutdown));
        service.start();
    }
}
