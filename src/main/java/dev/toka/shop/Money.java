package dev.toka.shop;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The economy plugin, reached by reflection so that the shop loads without it. */
final class Money {
    /** {@code ok} tells whether the call went through; {@code status} is the economy's own word; {@code balance} is the balance after it. */
    record Outcome(boolean ok, String status, long balance) {
        static final Outcome UNAVAILABLE = new Outcome(false, "UNAVAILABLE", 0);
    }

    private final String currency;

    Money(String currency) { this.currency = currency; }

    boolean present() {
        try { Class.forName("dev.toka.economy.Economy"); return true; } catch (ClassNotFoundException | LinkageError absent) { return false; }
    }

    CompletableFuture<Outcome> withdraw(UUID id, long amount, String reason, String operation) {
        return call("withdrawOnce", id, amount, reason, operation);
    }

    CompletableFuture<Outcome> deposit(UUID id, long amount, String reason, String operation) {
        return call("depositOnce", id, amount, reason, operation);
    }

    private CompletableFuture<Outcome> call(String method, UUID id, long amount, String reason, String operation) {
        try {
            Class<?> economy = Class.forName("dev.toka.economy.Economy"), cause = Class.forName("dev.toka.economy.Cause");
            Object why = cause.getConstructor(String.class, String.class).newInstance("shop", "tokaShop");
            Object future = economy.getMethod(method, UUID.class, String.class, long.class, cause, String.class).invoke(null, id, currency, amount, why, operation);
            return outcome(future);
        } catch (ReflectiveOperationException | LinkageError error) { return CompletableFuture.completedFuture(Outcome.UNAVAILABLE); }
    }

    private static CompletableFuture<Outcome> outcome(Object future) {
        return ((CompletableFuture<?>) future).handle((result, fault) -> {
            if (fault != null || result == null) return new Outcome(false, "FAILED", 0);
            try {
                boolean ok = Boolean.TRUE.equals(result.getClass().getMethod("ok").invoke(result));
                Object status = result.getClass().getMethod("status").invoke(result);
                long balance = (Long) result.getClass().getMethod("balance").invoke(result);
                return new Outcome(ok, String.valueOf(status), balance);
            } catch (ReflectiveOperationException error) { return new Outcome(false, "FAILED", 0); }
        });
    }
}
