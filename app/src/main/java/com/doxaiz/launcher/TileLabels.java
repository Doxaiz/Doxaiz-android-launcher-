package com.doxaiz.launcher;

/**
 * Сравнение подписей плиток рабочего стола.
 *
 * <p>Вынесено из {@code MainActivity} намеренно: это чистая логика, и её нужно
 * проверять в JVM-тестах без загрузки Activity (у которой в тестовом окружении
 * заглушки Android). Здесь же решается, нужно ли вообще трогать View: если текст
 * совпал, лишняя работа не делается.
 *
 * <p>Сравнение идёт по {@link CharSequence#toString()}, а не по ссылке: подпись с
 * меткой «новое» — это отдельный объект, и сравнение по ссылке всегда давало бы
 * «разное», то есть текст переустанавливался бы на каждом возврате домой.
 */
final class TileLabels {

    private TileLabels() {
    }

    static boolean sameText(CharSequence a, CharSequence b) {
        if (a == null || b == null) return a == b;
        return a.toString().contentEquals(b);
    }
}
