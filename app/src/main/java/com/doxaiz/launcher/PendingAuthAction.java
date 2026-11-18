package com.doxaiz.launcher;

/**
 * Одна отложенная проверка защиты: действие «на успех», действие «на отмену»
 * и правило «ровно один раз».
 *
 * <h3>Зачем отдельный класс</h3>
 * На Android 8–10 (и на прошивках, где нет рабочей биометрии) подтверждение
 * уходит в системный экран PIN-кода, а ответ приходит отдельным
 * {@code onActivityResult}. Действие, ради которого запрашивалась проверка,
 * нельзя хранить в поле активности: там одно поле на все запросы, и отмена или
 * повторный запрос легко приводили к выполнению «чужого» действия — например,
 * PIN для скрытых приложений открывал экспорт защищённой копии.
 *
 * Здесь пара «успех/отмена» живёт внутри самой проверки и снимается при первом
 * же ответе. Логика чистая (без Android API), поэтому проверяется JVM-тестом
 * {@code Audit60Tests.pendingAuthActionDeliversOnce()}.
 */
final class PendingAuthAction {

    private Runnable onSuccess;
    private Runnable onCancel;

    /** Новый запрос заменяет предыдущий: старый ответ больше не сработает. */
    synchronized void set(Runnable success, Runnable cancel) {
        this.onSuccess = success;
        this.onCancel = cancel;
    }

    /** Забыть запрос, ничего не выполняя (уход активности, сбой диалога). */
    synchronized void clear() {
        this.onSuccess = null;
        this.onCancel = null;
    }

    /** Есть ли незавершённая проверка. */
    synchronized boolean has() {
        return onSuccess != null || onCancel != null;
    }

    /**
     * Применяет ответ системного экрана и в любом случае забывает запрос:
     * повторный вызов того же результата не выполнит действие второй раз.
     *
     * @return true, если к моменту ответа запрос ещё существовал
     */
    boolean deliver(boolean success) {
        final Runnable toRun;
        synchronized (this) {
            toRun = success ? onSuccess : onCancel;
            final boolean had = has();
            onSuccess = null;
            onCancel = null;
            if (!had) return false;
            if (toRun == null) return true;
        }
        // Колбэк вызывается ВНЕ блокировки: он может снова запросить проверку
        // или уйти в системный диалог, и держать монитор в этот момент нельзя.
        try {
            toRun.run();
        } catch (Throwable t) {
            Diagnostics.record("PendingAuthAction.deliver", t);
        }
        return true;
    }
}
